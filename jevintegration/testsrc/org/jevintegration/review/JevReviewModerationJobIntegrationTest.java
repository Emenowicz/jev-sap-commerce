package org.jevintegration.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.cronjob.CronJobService;
import de.hybris.platform.customerreview.CustomerReviewService;
import de.hybris.platform.customerreview.enums.CustomerReviewApprovalType;
import de.hybris.platform.customerreview.model.CustomerReviewModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.internal.model.ServicelayerJobModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.user.UserService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jevintegration.JevClient;
import org.jevintegration.model.JevJudgmentModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/** Runs the real job against the junit tenant, with a local fake of the Jev endpoint. */
@IntegrationTest
public class JevReviewModerationJobIntegrationTest extends ServicelayerTransactionalTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The per-language comparison the README tells people to run in HAC. Keep the two in sync. */
	private static final String README_REPORT = "SELECT {language}, {humanDecision}, {decision}, COUNT({pk}) FROM {JevJudgment}"
			+ " WHERE {useCase} = 'reviewModeration' AND {dryRun} = 1"
			+ " GROUP BY {language}, {humanDecision}, {decision} ORDER BY {language}, {humanDecision}, {decision}";

	// looked up rather than @Resource-injected: that annotation is javax on 2211 and jakarta on 2211-jdk21
	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;
	private CustomerReviewService customerReviewService;
	private UserService userService;
	private CronJobService cronJobService;

	private HttpServer jev;
	private boolean jevDown;
	private ProductModel product;

	@Before
	public void setUp() throws Exception
	{
		createCoreData();
		modelService = getApplicationContext().getBean("modelService", ModelService.class);
		flexibleSearchService = getApplicationContext().getBean("flexibleSearchService", FlexibleSearchService.class);
		configurationService = getApplicationContext().getBean("configurationService", ConfigurationService.class);
		commonI18NService = getApplicationContext().getBean("commonI18NService", CommonI18NService.class);
		customerReviewService = getApplicationContext().getBean("customerReviewService", CustomerReviewService.class);
		userService = getApplicationContext().getBean("userService", UserService.class);
		cronJobService = getApplicationContext().getBean("cronJobService", CronJobService.class);
		final CatalogModel catalog = modelService.create(CatalogModel.class);
		catalog.setId("jevTestCatalog");
		final CatalogVersionModel version = modelService.create(CatalogVersionModel.class);
		version.setCatalog(catalog);
		version.setVersion("Staged");
		product = modelService.create(ProductModel.class);
		product.setCode("jevTestProduct");
		product.setCatalogVersion(version);
		product.setName("Hammer", Locale.ENGLISH);
		product.setName("Hammer", Locale.GERMAN);
		modelService.saveAll(catalog, version, product);

		jev = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		jev.createContext("/v1/systemone", this::answer);
		jev.start();
	}

	@After
	public void tearDown()
	{
		jev.stop(0);
	}

	@Test
	public void dryRunRecordsBothDecisionsAndChangesNothing()
	{
		final CustomerReviewModel good = review("Solid hammer, good grip", CustomerReviewApprovalType.APPROVED, "en");
		final CustomerReviewModel spam = review("buy now at cheap-tools.example", CustomerReviewApprovalType.REJECTED, "de");
		final CustomerReviewModel waiting = review("Arrived quickly", CustomerReviewApprovalType.PENDING, "en");

		assertEquals(CronJobResult.SUCCESS, job(true).perform(cronJob()).getResult());

		assertEquals("pending reviews are not part of a dry run", 2, judgments().size());
		final JevJudgmentModel spamJudgment = judgmentOf(spam);
		assertEquals("rejected", spamJudgment.getDecision());
		assertEquals("rejected", spamJudgment.getHumanDecision());
		assertEquals("de", spamJudgment.getLanguage());
		assertTrue(spamJudgment.isDryRun());
		assertEquals("jev-1.13.0", spamJudgment.getModel());
		assertEquals(Integer.valueOf(400), spamJudgment.getInputTokens());
		assertTrue(spamJudgment.getAnswers().contains("\"spam\""));
		assertEquals("approved", judgmentOf(good).getDecision());
		assertEquals(CustomerReviewApprovalType.REJECTED, reloaded(spam).getApprovalStatus());
		assertEquals(CustomerReviewApprovalType.PENDING, reloaded(waiting).getApprovalStatus());

		job(true).perform(cronJob());
		assertEquals("a review is judged once per mode", 2, judgments().size());

		final FlexibleSearchQuery report = new FlexibleSearchQuery(README_REPORT);
		report.setResultClassList(List.of(String.class, String.class, String.class, Integer.class));
		assertEquals(List.of(List.of("de", "rejected", "rejected", 1), List.of("en", "approved", "approved", 1)),
				flexibleSearchService.<List<Object>> search(report).getResult());
	}

	@Test
	public void liveApprovesOrRejectsPendingReviews()
	{
		final CustomerReviewModel good = review("Solid hammer, good grip", CustomerReviewApprovalType.PENDING, "en");
		final CustomerReviewModel abuse = review("the seller is an idiot", CustomerReviewApprovalType.PENDING, "en");

		assertEquals(CronJobResult.SUCCESS, job(false).perform(cronJob()).getResult());

		assertEquals(CustomerReviewApprovalType.APPROVED, reloaded(good).getApprovalStatus());
		assertEquals(CustomerReviewApprovalType.REJECTED, reloaded(abuse).getApprovalStatus());
		assertFalse(judgmentOf(good).isDryRun());
		assertNull(judgmentOf(good).getHumanDecision());
	}

	@Test
	public void jevDownChangesNothingAndReportsError()
	{
		jevDown = true;
		final CustomerReviewModel waiting = review("Solid hammer, good grip", CustomerReviewApprovalType.PENDING, "en");

		assertEquals(CronJobResult.ERROR, job(false).perform(cronJob()).getResult());

		assertTrue(judgments().isEmpty());
		assertEquals(CustomerReviewApprovalType.PENDING, reloaded(waiting).getApprovalStatus());
	}

	@Test
	public void essentialDataCronJobsRunThroughTheCronJobService() throws Exception
	{
		importCsv("/impex/essentialdata-jevintegration.impex", "UTF-8");

		final List<CronJobModel> cronJobs = flexibleSearchService.<CronJobModel> search(
				"SELECT {pk} FROM {CronJob} WHERE {code} IN ('jevReviewDryRunCronJob', 'jevReviewModerationCronJob')").getResult();
		assertEquals(2, cronJobs.size());
		for (final CronJobModel cronJob : cronJobs)
		{
			assertTrue("nothing runs until someone starts it", cronJob.getTriggers().isEmpty());
			// the platform starts its own session for a cronjob; that fails without a session language
			cronJobService.performCronJob(cronJob, true);
			modelService.refresh(cronJob);
			assertEquals(cronJob.getCode(), CronJobResult.SUCCESS, cronJob.getResult());
		}
	}

	@Test
	public void savedJudgmentsAreReadOnly()
	{
		final CustomerReviewModel spam = review("buy now at cheap-tools.example", CustomerReviewApprovalType.REJECTED, "en");
		job(true).perform(cronJob());
		final JevJudgmentModel judgment = judgmentOf(spam);

		try
		{
			judgment.setDecision("approved");
			modelService.save(judgment);
			fail("an audit record must not change after it is written");
		}
		catch (final RuntimeException expected)
		{
			modelService.refresh(judgment);
		}
		assertEquals("rejected", judgmentOf(spam).getDecision());
	}

	/** Fake Jev: "buy now" reads as spam, "idiot" as abuse, anything else as a clean review of the product. */
	private void answer(final HttpExchange exchange) throws IOException
	{
		final String comment = MAPPER.readTree(exchange.getRequestBody()).path("state").path("review").path("comment").asText();
		final String body = String.format(Locale.ROOT, "{\"model\":\"jev-1.13.0\",\"answers\":{"
				+ "\"abusive\":{\"type\":\"noul\",\"noul\":%s},\"spam\":{\"type\":\"noul\",\"noul\":%s},"
				+ "\"personal_data\":{\"type\":\"noul\",\"noul\":0.01},\"about_product\":{\"type\":\"noul\",\"noul\":0.9}},"
				+ "\"usage\":{\"input_tokens\":400,\"output_tokens\":40}}",
				comment.contains("idiot") ? 0.95 : 0.02, comment.contains("buy now") ? 0.95 : 0.02);
		final byte[] bytes = (jevDown ? "{}" : body).getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(jevDown ? 503 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private JevReviewModerationJob job(final boolean dryRun)
	{
		final JevReviewModerationJob job = new JevReviewModerationJob();
		job.setModelService(modelService);
		job.setFlexibleSearchService(flexibleSearchService);
		job.setConfigurationService(configurationService);
		job.setCommonI18NService(commonI18NService);
		job.setJevClient(new JevClient("http://127.0.0.1:" + jev.getAddress().getPort() + "/v1/systemone", "key",
				"jev-1.13.0", 2000, 1));
		job.setDryRun(dryRun);
		return job;
	}

	private CronJobModel cronJob()
	{
		final ServicelayerJobModel job = modelService.create(ServicelayerJobModel.class);
		job.setCode("jevTestJob" + System.nanoTime());
		job.setSpringId("jevReviewDryRunJob");
		final CronJobModel cronJob = modelService.create(CronJobModel.class);
		cronJob.setCode("jevTestCronJob" + System.nanoTime());
		cronJob.setJob(job);
		modelService.saveAll(job, cronJob);
		return cronJob;
	}

	private CustomerReviewModel review(final String comment, final CustomerReviewApprovalType status, final String isocode)
	{
		final CustomerReviewModel review = customerReviewService.createCustomerReview(Double.valueOf(4), "Review", comment,
				userService.getAdminUser(), product);
		review.setApprovalStatus(status);
		review.setLanguage(commonI18NService.getLanguage(isocode));
		modelService.save(review);
		return review;
	}

	private CustomerReviewModel reloaded(final CustomerReviewModel review)
	{
		modelService.refresh(review);
		return review;
	}

	private List<JevJudgmentModel> judgments()
	{
		return flexibleSearchService.<JevJudgmentModel> search("SELECT {pk} FROM {JevJudgment}").getResult();
	}

	private JevJudgmentModel judgmentOf(final CustomerReviewModel review)
	{
		return flexibleSearchService.<JevJudgmentModel> searchUnique(
				new FlexibleSearchQuery("SELECT {pk} FROM {JevJudgment} WHERE {item} = ?item", Map.of("item", review)));
	}
}
