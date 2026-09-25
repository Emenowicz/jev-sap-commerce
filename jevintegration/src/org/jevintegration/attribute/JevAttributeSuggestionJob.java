package org.jevintegration.attribute;

import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.enums.ClassificationAttributeTypeEnum;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.catalog.model.classification.ClassAttributeAssignmentModel;
import de.hybris.platform.catalog.model.classification.ClassificationAttributeValueModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.classification.ClassificationService;
import de.hybris.platform.classification.features.Feature;
import de.hybris.platform.classification.features.FeatureValue;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jevintegration.JevClient;
import org.jevintegration.JevClient.JevAnswers;
import org.jevintegration.ProductText;
import org.jevintegration.attribute.AttributeQuestions.Question;
import org.jevintegration.attribute.AttributeQuestions.Target;
import org.jevintegration.attribute.AttributeQuestions.Value;
import org.jevintegration.model.JevJudgmentModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * Classification attribute suggestions with Jev: which allowed value of each enum attribute the product text states.
 * It only records suggestions ({@link JevJudgmentModel}, one per product) and never changes a product's features.
 * <ul>
 * <li>Suggest: enum attributes without a value get a suggestion for a merchandiser.</li>
 * <li>Dry run: enum attributes that have a value get a suggestion to compare with, to measure Jev on your catalog.</li>
 * </ul>
 * Only enum attributes with a list of allowed values are judged: numbers, dates and free text are left to code.
 * Texts and value names are read in the cronjob's session language.
 */
public class JevAttributeSuggestionJob extends AbstractJobPerformable<CronJobModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(JevAttributeSuggestionJob.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String USE_CASE = "attributeSuggestion";
	/** Decision of a product that had no attribute to judge; recorded so it is not fetched again. */
	public static final String NOTHING_TO_JUDGE = "-";
	// ponytail: a fixed number of questions per request keeps every request well inside Jev's 64k tokens; size it by tokens if
	// attributes with hundreds of values become common
	private static final int QUESTIONS_PER_REQUEST = 25;

	private static final String CLASSES = "SELECT {pk} FROM {ClassificationClass} WHERE {catalogVersion} = ?system";
	// ponytail: the classes and the categories below them go into the query as one IN list; fine for a few thousand
	// (SQL Server allows 2,100 parameters). Beyond that, mark those categories and join on the mark instead.
	private static final String PRODUCTS = "SELECT {p.pk} FROM {Product AS p} WHERE {p.catalogVersion} = ?version"
			+ " AND NOT EXISTS ({{ SELECT {v.pk} FROM {VariantProduct AS v} WHERE {v.pk} = {p.pk} }})"
			+ " AND EXISTS ({{ SELECT {r.pk} FROM {CategoryProductRelation AS r} WHERE {r.target} = {p.pk} AND {r.source} IN (?classified) }})"
			+ " AND {p.pk} NOT IN ({{ SELECT {j.item} FROM {JevJudgment AS j}"
			+ " WHERE {j.useCase} = ?useCase AND {j.dryRun} = ?dryRun AND {j.language} = ?language }})"
			+ " ORDER BY {p.modifiedtime} DESC";

	private JevClient jevClient;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;
	private CatalogVersionService catalogVersionService;
	private ClassificationService classificationService;
	private CategoryService categoryService;
	private boolean dryRun;

	@Override
	public PerformResult perform(final CronJobModel cronJob)
	{
		final var config = configurationService.getConfiguration();
		final String catalog = config.getString("jev.attribute.catalog", "");
		final String system = config.getString("jev.attribute.system", "");
		if (catalog.isBlank() || system.isBlank())
		{
			LOG.error("Set jev.attribute.catalog and jev.attribute.system to the products and the classification system Jev should use");
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
		final CatalogVersionModel version;
		final CatalogVersionModel systemVersion;
		try
		{
			version = catalogVersionService.getCatalogVersion(catalog, config.getString("jev.attribute.catalogVersion", "Staged"));
			systemVersion = catalogVersionService.getCatalogVersion(system, config.getString("jev.attribute.systemVersion", "1.0"));
		}
		catch (final RuntimeException e)
		{
			LOG.error("Could not find the catalog {} or the classification system {}: {}", catalog, system, e.getMessage());
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
		final LanguageModel language = commonI18NService.getCurrentLanguage();
		final Locale locale = commonI18NService.getLocaleForLanguage(language);
		final double minConfidence = config.getDouble("jev.attribute.min.confidence", 0.7);

		// a product gets classes directly or, usually, through a category below a class
		final Set<CategoryModel> classified = new LinkedHashSet<>();
		for (final CategoryModel classificationClass : flexibleSearchService.<CategoryModel> search(
				new FlexibleSearchQuery(CLASSES, Map.of("system", systemVersion))).getResult())
		{
			classified.add(classificationClass);
			classified.addAll(categoryService.getAllSubcategoriesForCategory(classificationClass));
		}
		if (classified.isEmpty())
		{
			LOG.error("The classification system {} has no classification classes", system);
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.FINISHED);
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery(PRODUCTS, Map.of("version", version, "classified", classified,
				"useCase", USE_CASE, "dryRun", dryRun, "language", language.getIsocode()));
		query.setCount(config.getInt("jev.attribute.batch.size", 100));
		final List<ProductModel> products = flexibleSearchService.<ProductModel> search(query).getResult();

		final Tally tally = new Tally();
		final Map<String, Integer> skipped = new TreeMap<>();
		int unavailable = 0;
		for (final ProductModel product : products)
		{
			if (clearAbortRequestedIfNeeded(cronJob))
			{
				return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
			}
			try
			{
				final List<Judged> judged = targets(product, systemVersion, locale, skipped);
				final Calls calls = new Calls();
				if (!judged.isEmpty() && !ask(product, locale, judged, calls))
				{
					unavailable++; // nothing recorded, so the next run retries it
					continue;
				}
				judged.forEach(j -> j.decision = AttributeQuestions.decide(j.choice, j.confidence, minConfidence));
				modelService.save(judgment(product, language, judged, calls));
				judged.forEach(tally::add);
				tally.products++;
			}
			catch (final RuntimeException e)
			{
				// ponytail: nothing recorded, so it is retried next run; if batch.size products keep failing they fill every batch
				LOG.error("Jev attribute suggestion failed [product={}]", product.getCode(), e);
			}
		}

		LOG.info("Jev attribute suggestions, {}, language {}: {}", dryRun ? "dry run" : "suggest", language.getIsocode(),
				tally.describe(dryRun));
		if (!skipped.isEmpty())
		{
			LOG.info("Jev attribute suggestions skipped these attributes (only enum attributes with at most {} values are judged): {}",
					AttributeQuestions.MAX_VALUES, skipped);
		}
		if (unavailable > 0)
		{
			LOG.warn("Jev was unavailable for {} of {} products; they are retried next run", unavailable, products.size());
		}
		final boolean jevDown = !products.isEmpty() && unavailable == products.size();
		return new PerformResult(jevDown ? CronJobResult.ERROR : CronJobResult.SUCCESS, CronJobStatus.FINISHED);
	}

	/** Enum attributes of the configured system: those with a value in a dry run, those without one when suggesting. */
	private List<Judged> targets(final ProductModel product, final CatalogVersionModel systemVersion, final Locale locale,
			final Map<String, Integer> skipped)
	{
		final List<Judged> targets = new ArrayList<>();
		for (final Feature feature : classificationService.getFeatures(product))
		{
			final ClassAttributeAssignmentModel assignment = feature.getClassAttributeAssignment();
			if (assignment == null || !systemVersion.equals(assignment.getClassificationClass().getCatalogVersion()))
			{
				continue;
			}
			if (!ClassificationAttributeTypeEnum.ENUM.equals(assignment.getAttributeType()) || assignment.getAttributeValues().isEmpty())
			{
				skipped.merge(String.valueOf(assignment.getAttributeType()), 1, Integer::sum);
				continue;
			}
			if (assignment.getAttributeValues().size() > AttributeQuestions.MAX_VALUES)
			{
				skipped.merge("more than " + AttributeQuestions.MAX_VALUES + " values", 1, Integer::sum);
				continue;
			}
			final List<String> existing = feature.getValues().stream().map(FeatureValue::getValue)
					.filter(ClassificationAttributeValueModel.class::isInstance)
					.map(value -> ((ClassificationAttributeValueModel) value).getCode()).toList();
			if (dryRun == existing.isEmpty())
			{
				continue;
			}
			final List<Value> values = assignment.getAttributeValues().stream()
					.map(value -> new Value(value.getCode(), Objects.toString(value.getName(locale), value.getCode()))).toList();
			final String code = assignment.getClassificationAttribute().getCode();
			targets.add(new Judged(new Target(code, Objects.toString(assignment.getClassificationAttribute().getName(locale), code),
					values), existing));
		}
		return targets;
	}

	/** One request per 25 attributes, all about the same product. False when Jev was unavailable. */
	private boolean ask(final ProductModel product, final Locale locale, final List<Judged> judged, final Calls calls)
	{
		final Map<String, Object> state = ProductText.state(product, locale);
		for (int start = 0; start < judged.size(); start += QUESTIONS_PER_REQUEST)
		{
			final List<Judged> chunk = judged.subList(start, Math.min(start + QUESTIONS_PER_REQUEST, judged.size()));
			final Map<String, Object> questions = new LinkedHashMap<>();
			final List<Question> asked = new ArrayList<>();
			for (int i = 0; i < chunk.size(); i++)
			{
				final Question question = AttributeQuestions.question(chunk.get(i).target);
				questions.put("a" + i, question.choice());
				asked.add(question);
			}
			final Optional<JevAnswers> answers = jevClient.ask(state, questions);
			if (answers.isEmpty())
			{
				return false;
			}
			calls.model = answers.get().model();
			calls.inputTokens += answers.get().inputTokens();
			for (int i = 0; i < chunk.size(); i++)
			{
				final Judged j = chunk.get(i);
				final Map<String, String> codeByKey = asked.get(i).codeByKey();
				j.choice = codeByKey.getOrDefault(answers.get().choice("a" + i), AttributeQuestions.NOT_STATED);
				j.confidence = answers.get().confidence("a" + i);
				j.top = new LinkedHashMap<>();
				final Map<String, Double> top = j.top;
				answers.get().probabilities("a" + i).entrySet().stream()
						.sorted(Map.Entry.<String, Double> comparingByValue().reversed()).limit(3)
						.forEach(e -> top.put(codeByKey.getOrDefault(e.getKey(), e.getKey()), e.getValue()));
			}
		}
		return true;
	}

	private JevJudgmentModel judgment(final ProductModel product, final LanguageModel language, final List<Judged> judged,
			final Calls calls)
	{
		final JevJudgmentModel judgment = modelService.create(JevJudgmentModel.class);
		judgment.setItem(product);
		judgment.setUseCase(USE_CASE);
		judgment.setDryRun(dryRun);
		judgment.setLanguage(language.getIsocode());
		judgment.setModel(calls.model);
		judgment.setInputTokens(calls.inputTokens);
		final long suggested = judged.stream().filter(Judged::isSuggestion).count();
		final long notStated = judged.stream().filter(j -> AttributeQuestions.NOT_STATED.equals(j.decision)).count();
		judgment.setDecision(judged.isEmpty() ? NOTHING_TO_JUDGE
				: suggested + " suggested, " + (judged.size() - suggested - notStated) + " unsure, " + notStated + " not stated");
		judgment.setHumanDecision(dryRun ? judged.size() + " existing values" : null);
		final List<Map<String, Object>> attributes = new ArrayList<>();
		for (final Judged j : judged)
		{
			final Map<String, Object> attribute = new LinkedHashMap<>();
			attribute.put("attribute", j.target.code());
			attribute.put("name", j.target.name());
			if (dryRun)
			{
				attribute.put("existing", j.existing);
			}
			attribute.put("decision", j.decision);
			attribute.put("confidence", Math.round(j.confidence * 1000) / 1000.0);
			attribute.put("top", j.top);
			attributes.add(attribute);
		}
		try
		{
			judgment.setAnswers(MAPPER.writeValueAsString(Map.of("attributes", attributes)));
		}
		catch (final JsonProcessingException e)
		{
			throw new IllegalStateException(e);
		}
		return judgment;
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

	public void setClassificationService(final ClassificationService classificationService)
	{
		this.classificationService = classificationService;
	}

	public void setCategoryService(final CategoryService categoryService)
	{
		this.categoryService = categoryService;
	}

	public void setDryRun(final boolean dryRun)
	{
		this.dryRun = dryRun;
	}

	/** One attribute of one product: what it may take, what it has, and what Jev said. */
	private static final class Judged
	{
		private final Target target;
		private final List<String> existing;
		private String choice;
		private double confidence;
		private Map<String, Double> top = Map.of();
		private String decision;

		Judged(final Target target, final List<String> existing)
		{
			this.target = target;
			this.existing = existing;
		}

		boolean isSuggestion()
		{
			return !AttributeQuestions.NOT_STATED.equals(decision) && !AttributeQuestions.PENDING.equals(decision);
		}
	}

	/** The model version and token count of one product's requests. */
	private static final class Calls
	{
		private String model;
		private int inputTokens;
	}

	/** What happened to the attributes of one run. */
	private static final class Tally
	{
		private int products;
		private int judged;
		private int suggested;
		private int notStated;
		private int matching;

		void add(final Judged j)
		{
			judged++;
			if (AttributeQuestions.NOT_STATED.equals(j.decision))
			{
				notStated++;
			}
			else if (j.isSuggestion())
			{
				suggested++;
				if (j.existing.contains(j.decision))
				{
					matching++;
				}
			}
		}

		String describe(final boolean dryRun)
		{
			final String base = products + " products, " + judged + " attributes judged, " + suggested + " suggested by Jev, "
					+ (judged - suggested) + " left for a merchandiser (" + notStated + " not stated in the text)";
			return dryRun ? base + "; of Jev's suggestions " + matching + " match the product's value, " + (suggested - matching)
					+ " differ" : base;
		}
	}
}
