package org.jevintegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.catalog.model.CatalogModel;
import de.hybris.platform.catalog.model.CatalogVersionModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.cronjob.CronJobService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.jevintegration.model.JevJudgmentModel;
import org.junit.Before;
import org.junit.Test;


/** The optional retention ImpEx, as shipped: deletes judgments older than two years and keeps the rest. */
@IntegrationTest
public class JevJudgmentRetentionIntegrationTest extends ServicelayerTransactionalTest
{
	// looked up rather than @Resource-injected: that annotation is javax on 2211 and jakarta on 2211-jdk21
	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private CronJobService cronJobService;

	@Before
	public void setUp() throws Exception
	{
		createCoreData();
		modelService = getApplicationContext().getBean("modelService", ModelService.class);
		flexibleSearchService = getApplicationContext().getBean("flexibleSearchService", FlexibleSearchService.class);
		cronJobService = getApplicationContext().getBean("cronJobService", CronJobService.class);
		importCsv("/impex/optional/jevjudgment-retention.impex", "UTF-8");
	}

	@Test
	public void deletesJudgmentsOlderThanTwoYearsAndKeepsTheRest() throws Exception
	{
		final CatalogModel catalog = modelService.create(CatalogModel.class);
		catalog.setId("jevRetentionTest");
		final CatalogVersionModel version = modelService.create(CatalogVersionModel.class);
		version.setCatalog(catalog);
		version.setVersion("Staged");
		final ProductModel product = modelService.create(ProductModel.class);
		product.setCode("judged");
		product.setCatalogVersion(version);
		modelService.saveAll(catalog, version, product);
		judgment(product, "old", Instant.now().minus(3 * 365, ChronoUnit.DAYS));
		judgment(product, "recent", Instant.now().minus(365, ChronoUnit.DAYS));

		final CronJobModel cronJob = cronJobService.getCronJob("jevJudgmentRetentionCronJob");
		cronJobService.performCronJob(cronJob, true);

		assertTrue(cronJobService.isSuccessful(cronJob));
		assertEquals(List.of("recent"), flexibleSearchService.<JevJudgmentModel> search("SELECT {pk} FROM {JevJudgment}")
				.getResult().stream().map(JevJudgmentModel::getDecision).toList());
	}

	private void judgment(final ProductModel product, final String decision, final Instant created)
	{
		final JevJudgmentModel judgment = modelService.create(JevJudgmentModel.class);
		judgment.setItem(product);
		judgment.setUseCase("test");
		judgment.setDecision(decision);
		judgment.setCreationtime(Date.from(created));
		modelService.save(judgment);
	}
}
