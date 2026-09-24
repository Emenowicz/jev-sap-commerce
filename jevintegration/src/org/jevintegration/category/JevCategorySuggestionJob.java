package org.jevintegration.category;

import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jevintegration.JevClient;
import org.jevintegration.JevClient.JevAnswers;
import org.jevintegration.category.CategoryBeamSearch.Candidate;
import org.jevintegration.category.CategoryBeamSearch.Result;
import org.jevintegration.category.CategorySuggestionQuestions.Question;
import org.jevintegration.category.CategoryTree.Node;
import org.jevintegration.model.JevJudgmentModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * Category suggestions with Jev. It only records suggestions ({@link JevJudgmentModel}) and never changes a product.
 * <ul>
 * <li>Suggest: products without a category from the configured tree get a suggestion for a merchandiser.</li>
 * <li>Dry run: products that already have one get a suggestion to compare with, to measure Jev on your catalog.</li>
 * </ul>
 * Texts and category names are read in the cronjob's session language; each language is judged separately.
 */
public class JevCategorySuggestionJob extends AbstractJobPerformable<CronJobModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(JevCategorySuggestionJob.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String USE_CASE = "categorySuggestion";

	// ponytail: the tree's categories go into the query as one IN list; fine for a few thousand categories
	// (SQL Server allows 2,100 parameters). Beyond that, mark the tree's categories and join on the mark instead.
	private static final String PRODUCTS = "SELECT {p.pk} FROM {Product AS p} WHERE {p.catalogVersion} = ?version"
			+ " AND NOT EXISTS ({{ SELECT {v.pk} FROM {VariantProduct AS v} WHERE {v.pk} = {p.pk} }})"
			+ " AND %s EXISTS ({{ SELECT {r.pk} FROM {CategoryProductRelation AS r} WHERE {r.target} = {p.pk} AND {r.source} IN (?categories) }})"
			+ " AND {p.pk} NOT IN ({{ SELECT {j.item} FROM {JevJudgment AS j}"
			+ " WHERE {j.useCase} = ?useCase AND {j.dryRun} = ?dryRun AND {j.language} = ?language }})"
			+ " ORDER BY {p.modifiedtime} DESC";

	private JevClient jevClient;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;
	private CatalogVersionService catalogVersionService;
	private CategoryService categoryService;
	private boolean dryRun;

	@Override
	public PerformResult perform(final CronJobModel cronJob)
	{
		final var config = configurationService.getConfiguration();
		final String catalog = config.getString("jev.category.catalog", "");
		final List<String> roots = Arrays.stream(config.getString("jev.category.roots", "").split(","))
				.map(String::trim).filter(code -> !code.isEmpty()).toList();
		if (catalog.isBlank() || roots.isEmpty())
		{
			LOG.error("Set jev.category.catalog and jev.category.roots to the category tree Jev should choose from");
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
		final LanguageModel language = commonI18NService.getCurrentLanguage();
		final Locale locale = commonI18NService.getLocaleForLanguage(language);
		final CatalogVersionModel version;
		final CategoryTree tree;
		try
		{
			version = catalogVersionService.getCatalogVersion(catalog, config.getString("jev.category.catalogVersion", "Staged"));
			tree = CategoryTree.load(roots.stream().map(code -> categoryService.getCategoryForCode(version, code)).toList(), locale);
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not load the category tree {} from {}: {}", roots, catalog, e.getMessage());
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
		final double minScore = config.getDouble("jev.category.min.score", 0.7);
		final int width = Math.max(1, config.getInt("jev.category.beam.width", 3));

		final List<ProductModel> products = find(version, tree.categories(), language.getIsocode(),
				config.getInt("jev.category.batch.size", 100));
		final Tally tally = new Tally();
		int unavailable = 0;
		for (final ProductModel product : products)
		{
			if (clearAbortRequestedIfNeeded(cronJob))
			{
				return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
			}
			try
			{
				final Calls calls = new Calls();
				final Map<String, Object> state = CategorySuggestionQuestions.state(product, locale);
				final Optional<Result> result = CategoryBeamSearch.search(tree.root(), width, paths -> ask(state, paths, tree, calls));
				if (result.isEmpty())
				{
					unavailable++; // nothing recorded, so the next run retries it
					continue;
				}
				final String decision = CategorySuggestionQuestions.decide(result.get(), minScore);
				final String humanDecision = dryRun ? tree.codesOf(product) : null;
				modelService.save(judgment(product, language, result.get(), decision, humanDecision, calls));
				tally.add(decision, humanDecision, tree);
			}
			catch (final RuntimeException e)
			{
				// ponytail: nothing recorded, so it is retried next run; if batch.size products keep failing they fill every batch
				LOG.error("Jev category suggestion failed [product={}]", product.getCode(), e);
			}
		}

		LOG.info("Jev category suggestions, {}, language {}: {}", dryRun ? "dry run" : "suggest", language.getIsocode(),
				tally.describe(dryRun));
		if (unavailable > 0)
		{
			LOG.warn("Jev was unavailable for {} of {} products; they are retried next run", unavailable, products.size());
		}
		final boolean jevDown = !products.isEmpty() && unavailable == products.size();
		return new PerformResult(jevDown ? CronJobResult.ERROR : CronJobResult.SUCCESS, CronJobStatus.FINISHED);
	}

	/** One request per level: a Choice for every kept path's category, all about the same product. */
	private Optional<List<Map<String, Double>>> ask(final Map<String, Object> state, final List<List<Node>> paths,
			final CategoryTree tree, final Calls calls)
	{
		final Map<String, Object> questions = new LinkedHashMap<>();
		final List<Question> asked = new ArrayList<>();
		for (int i = 0; i < paths.size(); i++)
		{
			final List<Node> path = paths.get(i);
			final Node node = path.isEmpty() ? tree.root() : path.get(path.size() - 1);
			final Question question = CategorySuggestionQuestions.question(node, path);
			questions.put("n" + i, question.choice());
			asked.add(question);
		}
		final Optional<JevAnswers> answers = jevClient.ask(state, questions);
		if (answers.isEmpty())
		{
			return Optional.empty();
		}
		calls.model = answers.get().model();
		calls.inputTokens += answers.get().inputTokens();
		final List<Map<String, Double>> byCode = new ArrayList<>();
		for (int i = 0; i < asked.size(); i++)
		{
			final Map<String, String> codeByKey = asked.get(i).codeByKey();
			final Map<String, Double> probabilities = new LinkedHashMap<>();
			answers.get().probabilities("n" + i).forEach((key, p) -> probabilities.put(codeByKey.getOrDefault(key, key), p));
			byCode.add(probabilities);
		}
		return Optional.of(byCode);
	}

	private List<ProductModel> find(final CatalogVersionModel version, final Set<CategoryModel> categories, final String language,
			final int count)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(String.format(PRODUCTS, dryRun ? "" : "NOT"),
				Map.of("version", version, "categories", categories, "useCase", USE_CASE, "dryRun", dryRun, "language", language));
		query.setCount(count);
		return flexibleSearchService.<ProductModel> search(query).getResult();
	}

	private JevJudgmentModel judgment(final ProductModel product, final LanguageModel language, final Result result, final String decision,
			final String humanDecision, final Calls calls)
	{
		final JevJudgmentModel judgment = modelService.create(JevJudgmentModel.class);
		judgment.setItem(product);
		judgment.setUseCase(USE_CASE);
		judgment.setDryRun(dryRun);
		judgment.setLanguage(language.getIsocode());
		judgment.setModel(calls.model);
		judgment.setAnswers(answersJson(result));
		judgment.setDecision(decision);
		judgment.setHumanDecision(humanDecision);
		judgment.setInputTokens(calls.inputTokens);
		return judgment;
	}

	/** The best path with names, the kept alternatives, and the top answers of every question: enough to see why. */
	private static String answersJson(final Result result)
	{
		final Map<String, Object> json = new LinkedHashMap<>();
		json.put("best", candidateJson(result.best()));
		json.put("beam", result.beam().stream().map(JevCategorySuggestionJob::candidateJson).toList());
		json.put("questions", result.rounds().stream().map(round -> Map.of("parent", round.parent(), "top", round.top())).toList());
		try
		{
			return MAPPER.writeValueAsString(json);
		}
		catch (final JsonProcessingException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Map<String, Object> candidateJson(final Candidate candidate)
	{
		final List<Node> path = candidate.path();
		return Map.of("code", path.get(path.size() - 1).code(), "path", CategorySuggestionQuestions.path(path), "score",
				Math.round(candidate.score() * 1000) / 1000.0);
	}

	@Override
	public boolean isAbortable()
	{
		return true;
	}

	public void setJevClient(final JevClient jevClient)
	{
		this.jevClient = jevClient;
	}

	public void setConfigurationService(final ConfigurationService configurationService)
	{
		this.configurationService = configurationService;
	}

	public void setCommonI18NService(final CommonI18NService commonI18NService)
	{
		this.commonI18NService = commonI18NService;
	}

	public void setCatalogVersionService(final CatalogVersionService catalogVersionService)
	{
		this.catalogVersionService = catalogVersionService;
	}

	public void setCategoryService(final CategoryService categoryService)
	{
		this.categoryService = categoryService;
	}

	public void setDryRun(final boolean dryRun)
	{
		this.dryRun = dryRun;
	}

	/** The model version and token count of one product's requests. */
	private static final class Calls
	{
		private String model;
		private int inputTokens;
	}

	/** What happened to the products of one run. */
	static final class Tally
	{
		private int judged;
		private int suggested;
		private int exact;
		private int sibling;
		private int sameBranch;
		private int furtherOff;

		void add(final String decision, final String humanDecision, final CategoryTree tree)
		{
			judged++;
			if (CategorySuggestionQuestions.PENDING.equals(decision) || CategoryTree.NONE.equals(decision))
			{
				return;
			}
			suggested++;
			if (humanDecision == null)
			{
				return;
			}
			final List<String> actual = Arrays.asList(humanDecision.split(","));
			if (actual.contains(decision))
			{
				exact++;
			}
			else if (actual.stream().anyMatch(code -> tree.siblings(code, decision)))
			{
				sibling++;
			}
			else if (actual.stream().anyMatch(code -> tree.sameBranch(code, decision)))
			{
				sameBranch++;
			}
			else
			{
				furtherOff++;
			}
		}

		String describe(final boolean dryRun)
		{
			final String base = judged + " judged, " + suggested + " suggested by Jev, " + (judged - suggested) + " left for a merchandiser";
			return dryRun ? base + "; of Jev's suggestions " + exact + " match the product's category, " + sibling
					+ " are a neighbouring category (same parent), " + sameBranch + " are a parent or child of it, " + furtherOff
					+ " are further off" : base;
		}
	}
}
