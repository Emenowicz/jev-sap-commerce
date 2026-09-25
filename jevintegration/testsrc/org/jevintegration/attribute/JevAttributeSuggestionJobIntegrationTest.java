package org.jevintegration.attribute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.catalog.CatalogVersionService;
import de.hybris.platform.catalog.enums.ClassificationAttributeTypeEnum;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.catalog.model.classification.ClassAttributeAssignmentModel;
import de.hybris.platform.catalog.model.classification.ClassificationAttributeModel;
import de.hybris.platform.catalog.model.classification.ClassificationAttributeValueModel;
import de.hybris.platform.catalog.model.classification.ClassificationClassModel;
import de.hybris.platform.catalog.model.classification.ClassificationSystemModel;
import de.hybris.platform.catalog.model.classification.ClassificationSystemVersionModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.classification.ClassificationService;
import de.hybris.platform.classification.features.FeatureList;
import de.hybris.platform.classification.features.FeatureValue;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jevintegration.JevClient;
import org.jevintegration.JevSuggestionService;
import org.jevintegration.model.JevJudgmentModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/** Runs the real job against the junit tenant, with a local fake of the Jev endpoint. */
@IntegrationTest
public class JevAttributeSuggestionJobIntegrationTest extends ServicelayerTransactionalTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** What the fake Jev recognises in a product name, and the value name it then picks. */
	private static final Map<String, String> KEYWORDS = Map.of("cordless", "Battery-powered", "red", "Red", "white", "White",
			"hammer", "Manual");
	private static final List<String> CONFIG = List.of("jev.attribute.catalog", "jev.attribute.system", "jev.attribute.systemVersion");

	// looked up rather than @Resource-injected: that annotation is javax on 2211 and jakarta on 2211-jdk21
	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;
	private ClassificationService classificationService;
	private final Map<String, String> original = new LinkedHashMap<>();

	private HttpServer jev;
	private CatalogVersionModel version;
	private ClassificationSystemVersionModel system;
	private ClassAttributeAssignmentModel power;
	private ClassAttributeAssignmentModel color;
	private ProductModel drill;
	private ProductModel socket;
	private ProductModel hammer;
	private ProductModel screwdriver;

	@Before
	public void setUp() throws Exception
	{
		createCoreData();
		modelService = getApplicationContext().getBean("modelService", ModelService.class);
		flexibleSearchService = getApplicationContext().getBean("flexibleSearchService", FlexibleSearchService.class);
		configurationService = getApplicationContext().getBean("configurationService", ConfigurationService.class);
		commonI18NService = getApplicationContext().getBean("commonI18NService", CommonI18NService.class);
		classificationService = getApplicationContext().getBean("classificationService", ClassificationService.class);
		commonI18NService.setCurrentLanguage(commonI18NService.getLanguage("en"));

		final CatalogModel catalog = modelService.create(CatalogModel.class);
		catalog.setId("jevAttributeTest");
		version = modelService.create(CatalogVersionModel.class);
		version.setCatalog(catalog);
		version.setVersion("Staged");
		final ClassificationSystemModel classification = modelService.create(ClassificationSystemModel.class);
		classification.setId("jevTestClassification");
		system = modelService.create(ClassificationSystemVersionModel.class);
		system.setCatalog(classification);
		system.setVersion("1.0");
		modelService.saveAll(catalog, version, classification, system);

		final ClassificationClassModel tools = modelService.create(ClassificationClassModel.class);
		tools.setCode("tools");
		tools.setCatalogVersion(system);
		modelService.save(tools);
		power = assignment(tools, attribute("power", "Power source"), ClassificationAttributeTypeEnum.ENUM,
				value("ac", "AC-powered"), value("battery", "Battery-powered"), value("manual", "Manual"));
		color = assignment(tools, attribute("color", "Color"), ClassificationAttributeTypeEnum.ENUM,
				value("white", "White"), value("red", "Red"));
		assignment(tools, attribute("weight", "Weight"), ClassificationAttributeTypeEnum.NUMBER);

		drill = product("drill", "Cordless drill 18V, red", tools);
		socket = product("socket", "Double wall socket, white", tools);
		hammer = product("hammer", "Carpenter's hammer 450 g", tools);
		// the usual setup: the class sits above a category, and the product is only in the category
		final CategoryModel drivers = modelService.create(CategoryModel.class);
		drivers.setCode("drivers");
		drivers.setCatalogVersion(version);
		drivers.setSupercategories(List.of(tools));
		modelService.save(drivers);
		screwdriver = product("screwdriver", "Cordless screwdriver, red", drivers);
		setValue(drill, power, "battery");
		setValue(socket, color, "white");

		CONFIG.forEach(key -> original.put(key, configurationService.getConfiguration().getString(key, null)));
		configurationService.getConfiguration().setProperty("jev.attribute.catalog", "jevAttributeTest");
		configurationService.getConfiguration().setProperty("jev.attribute.system", "jevTestClassification");
		configurationService.getConfiguration().setProperty("jev.attribute.systemVersion", "1.0");

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
	public void dryRunComparesWithTheValuesProductsAlreadyHave() throws Exception
	{
		assertEquals(CronJobResult.SUCCESS, job(true).perform(cronJob()).getResult());

		assertEquals("every classified product, once", 4, judgments().size());
		final JevJudgmentModel drillJudgment = judgmentOf(drill);
		assertEquals("1 suggested, 0 unsure, 0 not stated", drillJudgment.getDecision());
		assertEquals("1 existing values", drillJudgment.getHumanDecision());
		final JsonNode attributes = MAPPER.readTree(drillJudgment.getAnswers()).path("attributes");
		assertEquals("only the attribute with a value; the number attribute is skipped", 1, attributes.size());
		assertEquals("power", attributes.get(0).path("attribute").asText());
		assertEquals("battery", attributes.get(0).path("decision").asText());
		assertEquals("battery", attributes.get(0).path("existing").get(0).asText());
		assertEquals("no value to compare with", JevAttributeSuggestionJob.NOTHING_TO_JUDGE, judgmentOf(hammer).getDecision());
	}

	@Test
	public void suggestFillsOnlyEmptyAttributesAndChangesNothing() throws Exception
	{
		assertEquals(CronJobResult.SUCCESS, job(false).perform(cronJob()).getResult());

		final JsonNode drillAttributes = MAPPER.readTree(judgmentOf(drill).getAnswers()).path("attributes");
		assertEquals("power already has a value", 1, drillAttributes.size());
		assertEquals("color", drillAttributes.get(0).path("attribute").asText());
		assertEquals("red", drillAttributes.get(0).path("decision").asText());
		assertEquals("2 empty attributes: power is manual, color is not in the text", "1 suggested, 0 unsure, 1 not stated",
				judgmentOf(hammer).getDecision());
		assertEquals("1 suggested, 0 unsure, 0 not stated", judgmentOf(drill).getDecision());
		assertEquals("classified through its category", "2 suggested, 0 unsure, 0 not stated", judgmentOf(screwdriver).getDecision());

		final FeatureList features = classificationService.getFeatures(drill);
		assertTrue("suggestions never change a product", features.getFeatureByAssignment(color).getValues().isEmpty());
		assertEquals(1, features.getFeatureByAssignment(power).getValues().size());

		job(false).perform(cronJob());
		assertEquals("a product is judged once per mode and language", 4, judgments().size());
	}

	@Test
	public void aPersonAppliesSuggestedValuesOnlyToAttributesThatAreStillEmpty()
	{
		job(false).perform(cronJob());
		final JevSuggestionService suggestions = getApplicationContext().getBean("jevSuggestionService", JevSuggestionService.class);
		assertFalse("the socket's empty attribute is not stated in its text", suggestions.canApply(judgmentOf(socket)));
		assertTrue("so it can only be dismissed", suggestions.canDismiss(judgmentOf(socket)));

		setValue(screwdriver, color, "white"); // a person filled it after the run
		assertEquals("power only: color has a value by now", 1, suggestions.apply(judgmentOf(screwdriver)));
		assertEquals("battery", valueOf(screwdriver, power));
		assertEquals("white", valueOf(screwdriver, color));

		assertEquals("power; color was not stated", 1, suggestions.apply(judgmentOf(hammer)));
		assertEquals("manual", valueOf(hammer, power));
		assertNull(valueOf(hammer, color));
		assertEquals(JevSuggestionService.APPLIED, judgmentOf(hammer).getResolution());
	}

	@Test
	public void missingOrWrongConfigurationIsAnError()
	{
		configurationService.getConfiguration().setProperty("jev.attribute.system", "");
		assertEquals(CronJobResult.ERROR, job(false).perform(cronJob()).getResult());
		configurationService.getConfiguration().setProperty("jev.attribute.system", "noSuchSystem");
		assertEquals(CronJobResult.ERROR, job(false).perform(cronJob()).getResult());
		assertFalse(judgments().iterator().hasNext());
	}

	/** Fake Jev: for every question, puts 0.9 on the value named by a keyword of the product name, else on "not stated". */
	private void answer(final HttpExchange exchange) throws IOException
	{
		final JsonNode request = MAPPER.readTree(exchange.getRequestBody());
		final String name = request.path("state").path("product").path("name").asText().toLowerCase(Locale.ROOT);
		final Map<String, Object> answers = new LinkedHashMap<>();
		request.path("questions").properties().forEach(question -> {
			final JsonNode criteria = question.getValue().path("criteria");
			final List<String> keys = new ArrayList<>();
			criteria.fieldNames().forEachRemaining(keys::add);
			final String pick = keys.stream().filter(key -> KEYWORDS.entrySet().stream()
					.anyMatch(k -> name.contains(k.getKey()) && k.getValue().equals(criteria.path(key).asText())))
					.findFirst().orElse(AttributeQuestions.NOT_STATED);
			final Map<String, Double> probabilities = new LinkedHashMap<>();
			keys.forEach(key -> probabilities.put(key, key.equals(pick) ? 0.9 : 0.1 / (keys.size() - 1)));
			final double confidence = (keys.size() * 0.9 - 1) / (keys.size() - 1);
			answers.put(question.getKey(), Map.of("type", "choice", "choice", pick, "probabilities", probabilities, "confidence", confidence));
		});
		final byte[] bytes = MAPPER.writeValueAsBytes(Map.of("model", "jev-1.13.0", "answers", answers,
				"usage", Map.of("input_tokens", 300, "output_tokens", 30)));
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private JevAttributeSuggestionJob job(final boolean dryRun)
	{
		final JevAttributeSuggestionJob job = new JevAttributeSuggestionJob();
		job.setModelService(modelService);
		job.setFlexibleSearchService(flexibleSearchService);
		job.setConfigurationService(configurationService);
		job.setCommonI18NService(commonI18NService);
		job.setCatalogVersionService(getApplicationContext().getBean("catalogVersionService", CatalogVersionService.class));
		job.setClassificationService(classificationService);
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
		job.setSpringId("jevAttributeDryRunJob");
		final CronJobModel cronJob = modelService.create(CronJobModel.class);
		cronJob.setCode("jevTestCronJob" + System.nanoTime());
		cronJob.setJob(job);
		modelService.saveAll(job, cronJob);
		return cronJob;
	}

	private ClassificationAttributeModel attribute(final String code, final String name)
	{
		final ClassificationAttributeModel attribute = modelService.create(ClassificationAttributeModel.class);
		attribute.setCode(code);
		attribute.setSystemVersion(system);
		attribute.setName(name, Locale.ENGLISH);
		modelService.save(attribute);
		return attribute;
	}

	private ClassificationAttributeValueModel value(final String code, final String name)
	{
		final ClassificationAttributeValueModel value = modelService.create(ClassificationAttributeValueModel.class);
		value.setCode(code);
		value.setSystemVersion(system);
		value.setName(name, Locale.ENGLISH);
		modelService.save(value);
		return value;
	}

	private ClassAttributeAssignmentModel assignment(final ClassificationClassModel classificationClass,
			final ClassificationAttributeModel attribute, final ClassificationAttributeTypeEnum type,
			final ClassificationAttributeValueModel... values)
	{
		final ClassAttributeAssignmentModel assignment = modelService.create(ClassAttributeAssignmentModel.class);
		assignment.setClassificationClass(classificationClass);
		assignment.setClassificationAttribute(attribute);
		assignment.setSystemVersion(system);
		assignment.setAttributeType(type);
		assignment.setAttributeValues(List.of(values));
		modelService.save(assignment);
		return assignment;
	}

	private ProductModel product(final String code, final String name, final CategoryModel classificationClass)
	{
		final ProductModel product = modelService.create(ProductModel.class);
		product.setCode(code);
		product.setCatalogVersion(version);
		product.setName(name, Locale.ENGLISH);
		product.setDescription("<p>" + name + "</p>", Locale.ENGLISH);
		product.setSupercategories(List.of(classificationClass));
		modelService.save(product);
		return product;
	}

	/** Test setup only: the job itself never writes features. */
	private void setValue(final ProductModel product, final ClassAttributeAssignmentModel assignment, final String code)
	{
		final FeatureList features = classificationService.getFeatures(product);
		final ClassificationAttributeValueModel value = assignment.getAttributeValues().stream()
				.filter(v -> v.getCode().equals(code)).findFirst().orElseThrow();
		features.getFeatureByAssignment(assignment).addValue(new FeatureValue(value));
		classificationService.replaceFeatures(product, features);
	}

	private String valueOf(final ProductModel product, final ClassAttributeAssignmentModel assignment)
	{
		final FeatureValue value = classificationService.getFeatures(product).getFeatureByAssignment(assignment).getValue();
		return value == null ? null : ((ClassificationAttributeValueModel) value.getValue()).getCode();
	}

	private List<JevJudgmentModel> judgments()
	{
		return flexibleSearchService.<JevJudgmentModel> search(
				"SELECT {pk} FROM {JevJudgment} WHERE {useCase} = 'attributeSuggestion'").getResult();
	}

	private JevJudgmentModel judgmentOf(final ProductModel product)
	{
		return flexibleSearchService.<JevJudgmentModel> searchUnique(
				new FlexibleSearchQuery("SELECT {pk} FROM {JevJudgment} WHERE {item} = ?item", Map.of("item", product)));
	}
}
