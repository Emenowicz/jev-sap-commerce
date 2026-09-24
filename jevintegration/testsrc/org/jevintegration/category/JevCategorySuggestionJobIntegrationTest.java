package org.jevintegration.category;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.internal.model.ServicelayerJobModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.core.model.type.ComposedTypeModel;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.variants.model.VariantProductModel;
import de.hybris.platform.variants.model.VariantTypeModel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jevintegration.JevClient;
import org.jevintegration.model.JevJudgmentModel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/** Runs the real job against the junit tenant, with a local fake of the Jev endpoint. */
@IntegrationTest
public class JevCategorySuggestionJobIntegrationTest extends ServicelayerTransactionalTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** What the fake Jev recognises in a product name, and the category names it then picks. */
	private static final Map<String, Set<String>> KEYWORDS = Map.of(
			"drill", Set.of("Hardware", "Tools", "Drills"),
			"saw", Set.of("Hardware", "Tools", "Saws"),
			"tap", Set.of("Hardware", "Plumbing", "Faucets"));
	private static final List<String> CONFIG = List.of("jev.category.catalog", "jev.category.roots");
	/** What the environment had configured, put back after each test. */
	private final Map<String, String> original = new LinkedHashMap<>();

	// looked up rather than @Resource-injected: that annotation is javax on 2211 and jakarta on 2211-jdk21
	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;

	private HttpServer jev;
	private CatalogVersionModel version;
	private ProductModel drill;
	private ProductModel saw;
	private ProductModel tap;
	private ProductModel gnome;
	private ProductModel drillVariant;
	private CategoryModel color;

	@Before
	public void setUp() throws Exception
	{
		createCoreData();
		modelService = getApplicationContext().getBean("modelService", ModelService.class);
		flexibleSearchService = getApplicationContext().getBean("flexibleSearchService", FlexibleSearchService.class);
		configurationService = getApplicationContext().getBean("configurationService", ConfigurationService.class);
		commonI18NService = getApplicationContext().getBean("commonI18NService", CommonI18NService.class);
		commonI18NService.setCurrentLanguage(commonI18NService.getLanguage("en"));

		final CatalogModel catalog = modelService.create(CatalogModel.class);
		catalog.setId("jevCategoryTest");
		version = modelService.create(CatalogVersionModel.class);
		version.setCatalog(catalog);
		version.setVersion("Staged");
		modelService.saveAll(catalog, version);
		final CategoryModel hardware = category("hw", "Hardware");
		final CategoryModel tools = category("tools", "Tools", hardware);
		final CategoryModel drills = category("drills", "Drills", tools);
		final CategoryModel saws = category("saws", "Saws", tools);
		final CategoryModel plumbing = category("plumbing", "Plumbing", hardware);
		category("faucets", "Faucets", plumbing);
		category("pipes", "Pipes", plumbing);
		drill = product("drill", "Cordless drill 18V", drills);
		saw = product("saw", "Circular saw 1400W", saws);
		tap = product("tap", "Kitchen tap, brushed chrome");
		gnome = product("gnome", "Garden gnome with lantern");
		drillVariant = variantOf(drill);

		CONFIG.forEach(key -> original.put(key, configurationService.getConfiguration().getString(key, null)));
		configurationService.getConfiguration().setProperty("jev.category.catalog", "jevCategoryTest");
		configurationService.getConfiguration().setProperty("jev.category.roots", "hw");

		jev = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		jev.createContext("/v1/systemone", this::answer);
		jev.start();
	}

	@After
	public void tearDown()
	{
		if (jev != null)
		{
			jev.stop(0);
		}
		original.forEach((key, value) -> {
			if (value == null)
			{
				configurationService.getConfiguration().clearProperty(key);
			}
			else
			{
				configurationService.getConfiguration().setProperty(key, value);
			}
		});
	}

	@Test
	public void dryRunComparesWithTheCategoryProductsAlreadyHave()
	{
		assertEquals(CronJobResult.SUCCESS, job(true).perform(cronJob()).getResult());

		assertEquals("only products that already have a category", 2, judgments().size());
		final JevJudgmentModel drillJudgment = judgmentOf(drill);
		assertEquals("drills", drillJudgment.getDecision());
		assertEquals("drills", drillJudgment.getHumanDecision());
		assertEquals("en", drillJudgment.getLanguage());
		assertTrue(drillJudgment.isDryRun());
		assertEquals("saws", judgmentOf(saw).getDecision());

		final CategoryTree tree = CategoryTree.load(List.of(getApplicationContext().getBean("categoryService", CategoryService.class)
				.getCategoryForCode(version, "hw")), Locale.ENGLISH);
		assertTrue(tree.siblings("drills", "saws"));
		assertTrue(!tree.siblings("drills", "faucets") && !tree.siblings("drills", "drills"));
		assertTrue(tree.sameBranch("tools", "drills") && !tree.sameBranch("tools", "faucets"));
	}

	@Test
	public void suggestRecordsSuggestionsForUncategorisedProductsAndChangesNothing()
	{
		assertEquals(CronJobResult.SUCCESS, job(false).perform(cronJob()).getResult());

		assertEquals("the tap and the gnome; not the variant, not the categorised products", 2, judgments().size());
		final JevJudgmentModel tapJudgment = judgmentOf(tap);
		assertEquals("faucets", tapJudgment.getDecision());
		assertTrue(tapJudgment.getAnswers().contains("Hardware > Plumbing > Faucets"));
		assertEquals("fits nowhere in the tree", CategoryTree.NONE, judgmentOf(gnome).getDecision());
		modelService.refresh(tap);
		assertTrue("suggestions never change a product", tap.getSupercategories().isEmpty());

		job(false).perform(cronJob());
		assertEquals("a product is judged once per mode and language", 2, judgments().size());
	}

	@Test
	public void variantsAreLeftToTheirBaseProduct()
	{
		Assume.assumeTrue("needs a concrete variant type, such as basecommerce's GenericVariantProduct", drillVariant != null);
		job(false).perform(cronJob());
		assertTrue(flexibleSearchService.<JevJudgmentModel> search(new FlexibleSearchQuery(
				"SELECT {pk} FROM {JevJudgment} WHERE {item} = ?item", Map.of("item", drillVariant))).getResult().isEmpty());
		final CategoryModel hardware = getApplicationContext().getBean("categoryService", CategoryService.class)
				.getCategoryForCode(version, "hw");
		assertEquals("the base product's label ignores its variant category", "drills",
				CategoryTree.load(List.of(hardware), Locale.ENGLISH).codesOf(drill));
	}

	@Test
	public void missingConfigurationIsAnErrorNotASilentSkip()
	{
		CONFIG.forEach(key -> configurationService.getConfiguration().setProperty(key, ""));
		assertEquals(CronJobResult.ERROR, job(false).perform(cronJob()).getResult());
		configurationService.getConfiguration().setProperty("jev.category.catalog", "noSuchCatalog");
		configurationService.getConfiguration().setProperty("jev.category.roots", "hw");
		assertEquals("a typo in the catalog finishes with an error instead of aborting", CronJobResult.ERROR,
				job(false).perform(cronJob()).getResult());
		assertTrue(judgments().isEmpty());
	}

	/** Fake Jev: for every question, puts 0.9 on the option named in the product's keyword path, else on "none". */
	private void answer(final HttpExchange exchange) throws IOException
	{
		final JsonNode request = MAPPER.readTree(exchange.getRequestBody());
		final String name = request.path("state").path("product").path("name").asText().toLowerCase(Locale.ROOT);
		final Set<String> target = KEYWORDS.entrySet().stream().filter(e -> name.contains(e.getKey())).map(Map.Entry::getValue)
				.findFirst().orElse(Set.of());
		final Map<String, Object> answers = new LinkedHashMap<>();
		request.path("questions").properties().forEach(question -> {
			final List<String> keys = new ArrayList<>();
			question.getValue().path("criteria").fieldNames().forEachRemaining(keys::add);
			final String pick = keys.stream().filter(key -> target.contains(question.getValue().path("criteria").path(key).asText()))
					.findFirst().orElse(keys.contains(CategoryTree.NONE) ? CategoryTree.NONE : keys.get(0));
			final Map<String, Double> probabilities = new LinkedHashMap<>();
			keys.forEach(key -> probabilities.put(key, key.equals(pick) ? 0.9 : 0.1 / (keys.size() - 1)));
			answers.put(question.getKey(), Map.of("type", "choice", "choice", pick, "probabilities", probabilities, "confidence", 0.8));
		});
		final byte[] bytes = MAPPER.writeValueAsBytes(Map.of("model", "jev-1.13.0", "answers", answers,
				"usage", Map.of("input_tokens", 500, "output_tokens", 50)));
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private JevCategorySuggestionJob job(final boolean dryRun)
	{
		final JevCategorySuggestionJob job = new JevCategorySuggestionJob();
		job.setModelService(modelService);
		job.setFlexibleSearchService(flexibleSearchService);
		job.setConfigurationService(configurationService);
		job.setCommonI18NService(commonI18NService);
		job.setCatalogVersionService(getApplicationContext().getBean("catalogVersionService", CatalogVersionService.class));
		job.setCategoryService(getApplicationContext().getBean("categoryService", CategoryService.class));
		job.setJevClient(new JevClient("http://127.0.0.1:" + jev.getAddress().getPort() + "/v1/systemone", "key",
				"jev-1.13.0", 2000, 1));
		job.setDryRun(dryRun);
		return job;
	}

	private CronJobModel cronJob()
	{
		final ServicelayerJobModel job = modelService.create(ServicelayerJobModel.class);
		job.setCode("jevTestJob" + System.nanoTime());
		job.setSpringId("jevCategoryDryRunJob");
		final CronJobModel cronJob = modelService.create(CronJobModel.class);
		cronJob.setCode("jevTestCronJob" + System.nanoTime());
		cronJob.setJob(job);
		modelService.saveAll(job, cronJob);
		return cronJob;
	}

	/**
	 * VariantProduct is abstract. The usual concrete type, basecommerce's GenericVariantProduct, is created by its code
	 * so this test compiles without basecommerce; returns null when the type is not there.
	 */
	private ProductModel variantOf(final ProductModel base)
	{
		final ComposedTypeModel type;
		try
		{
			type = getApplicationContext().getBean("typeService", TypeService.class).getComposedTypeForCode("GenericVariantProduct");
		}
		catch (final UnknownIdentifierException e)
		{
			return null;
		}
		// a generic variant needs a variant value category (red) under a variant category (color); SAP keeps variant
		// categories out of navigation trees, so color has no parent
		color = modelService.create("VariantCategory");
		color.setCode("color");
		color.setCatalogVersion(version);
		modelService.save(color); // saved alone first: SAP's value-category check reads its siblings from the saved parent
		final CategoryModel red = modelService.create("VariantValueCategory");
		red.setCode("red");
		red.setCatalogVersion(version);
		red.setSupercategories(List.of(color));
		modelService.setAttributeValue(red, "sequence", Integer.valueOf(0));
		modelService.save(red);
		final List<CategoryModel> baseCategories = new ArrayList<>(base.getSupercategories());
		baseCategories.add(color);
		base.setSupercategories(baseCategories);
		base.setVariantType((VariantTypeModel) type);
		modelService.save(base); // the variant is checked against the saved base product's variant type
		final VariantProductModel variant = modelService.create("GenericVariantProduct");
		variant.setCode(base.getCode() + "-red");
		variant.setCatalogVersion(version);
		variant.setBaseProduct(base);
		variant.setSupercategories(List.of(red));
		variant.setName(base.getName(Locale.ENGLISH) + ", red", Locale.ENGLISH);
		modelService.save(variant);
		return variant;
	}

	private CategoryModel category(final String code, final String name, final CategoryModel... parents)
	{
		final CategoryModel category = modelService.create(CategoryModel.class);
		category.setCode(code);
		category.setCatalogVersion(version);
		category.setName(name, Locale.ENGLISH);
		category.setSupercategories(List.of(parents));
		modelService.save(category);
		return category;
	}

	private ProductModel product(final String code, final String name, final CategoryModel... categories)
	{
		final ProductModel product = modelService.create(ProductModel.class);
		product.setCode(code);
		product.setCatalogVersion(version);
		product.setName(name, Locale.ENGLISH);
		product.setDescription("<p>" + name + "</p>", Locale.ENGLISH);
		product.setSupercategories(List.of(categories));
		modelService.save(product);
		return product;
	}

	private List<JevJudgmentModel> judgments()
	{
		return flexibleSearchService.<JevJudgmentModel> search(
				"SELECT {pk} FROM {JevJudgment} WHERE {useCase} = 'categorySuggestion'").getResult();
	}

	private JevJudgmentModel judgmentOf(final ProductModel product)
	{
		return flexibleSearchService.<JevJudgmentModel> searchUnique(
				new FlexibleSearchQuery("SELECT {pk} FROM {JevJudgment} WHERE {item} = ?item", Map.of("item", product)));
	}
}
