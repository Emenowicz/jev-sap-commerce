package org.jevintegration.review;

import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.customerreview.enums.CustomerReviewApprovalType;
import de.hybris.platform.customerreview.model.CustomerReviewModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.jevintegration.JevClient;
import org.jevintegration.JevClient.JevAnswers;
import org.jevintegration.model.JevJudgmentModel;
import org.jevintegration.review.ReviewModerationQuestions.Thresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Review moderation with Jev.
 * <ul>
 * <li>Live: judges pending reviews, approves or rejects the ones Jev is sure about, and leaves the rest pending for a person.</li>
 * <li>Dry run: judges reviews your moderators already decided and records both decisions, changing nothing, so you can
 * measure Jev on your own data before going live.</li>
 * </ul>
 * Every judgment is saved as a {@link JevJudgmentModel}, which is also what stops a review from being judged twice.
 */
public class JevReviewModerationJob extends AbstractJobPerformable<CronJobModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(JevReviewModerationJob.class);

	public static final String USE_CASE = "reviewModeration";

	// ponytail: NOT IN over the judgments of this use case, backed by the item index; revisit past millions of judgments
	private static final String UNJUDGED_REVIEWS = "SELECT {r.pk} FROM {CustomerReview AS r} WHERE {r.approvalStatus} = ?status"
			+ " AND {r.pk} NOT IN ({{ SELECT {j.item} FROM {JevJudgment AS j} WHERE {j.useCase} = ?useCase AND {j.dryRun} = ?dryRun }})"
			+ " ORDER BY {r.creationtime} DESC";

	private JevClient jevClient;
	private ConfigurationService configurationService;
	private CommonI18NService commonI18NService;
	private boolean dryRun;

	@Override
	public PerformResult perform(final CronJobModel cronJob)
	{
		final var config = configurationService.getConfiguration();
		final Thresholds thresholds = new Thresholds(config.getDouble("jev.review.reject.at", 0.85),
				config.getDouble("jev.review.approve.below", 0.15), config.getDouble("jev.review.personaldata.at", 0.5),
				config.getDouble("jev.review.ontopic.at", 0.5));
		final int batchSize = config.getInt("jev.review.batch.size", 200);

		final List<CustomerReviewModel> reviews = new ArrayList<>();
		if (dryRun)
		{
			// half of each kind, so the rare rejections are not drowned out by approvals
			reviews.addAll(find(CustomerReviewApprovalType.APPROVED, batchSize / 2));
			reviews.addAll(find(CustomerReviewApprovalType.REJECTED, batchSize - batchSize / 2));
		}
		else
		{
			reviews.addAll(find(CustomerReviewApprovalType.PENDING, batchSize));
		}

		final Map<String, Tally> byLanguage = new TreeMap<>();
		int unavailable = 0;
		for (final CustomerReviewModel review : reviews)
		{
			if (clearAbortRequestedIfNeeded(cronJob))
			{
				return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
			}
			try
			{
				final LanguageModel language = review.getLanguage();
				final Locale locale = language == null ? null : commonI18NService.getLocaleForLanguage(language);
				final Optional<JevAnswers> answers = jevClient.ask(ReviewModerationQuestions.state(review, locale),
						ReviewModerationQuestions.QUESTIONS);
				if (answers.isEmpty())
				{
					unavailable++; // nothing recorded, so the next run retries it
					continue;
				}
				final CustomerReviewApprovalType decision = ReviewModerationQuestions.decide(answers.get(), thresholds);
				final String humanDecision = dryRun ? review.getApprovalStatus().getCode() : null;
				final JevJudgmentModel judgment = judgment(review, language, answers.get(), decision, humanDecision);
				if (dryRun)
				{
					modelService.save(judgment);
				}
				else
				{
					review.setApprovalStatus(decision);
					// ponytail: one saveAll, not an explicit transaction; if only the judgment lands, the review stays pending
					// for a person. Wrap both in Transaction.current().execute(...) if a status must never change without its record.
					modelService.saveAll(review, judgment);
				}
				byLanguage.computeIfAbsent(Objects.toString(judgment.getLanguage(), "unknown"), k -> new Tally())
						.add(decision.getCode(), humanDecision);
			}
			catch (final RuntimeException e)
			{
				// ponytail: nothing recorded, so it is retried next run; if batchSize reviews keep failing they fill every batch
				LOG.error("Jev review moderation failed [review={}]", review.getPk(), e);
			}
		}

		byLanguage.forEach((language, tally) -> LOG.info("Jev review moderation, {}, language {}: {}",
				dryRun ? "dry run" : "live", language, tally.describe(dryRun)));
		if (unavailable > 0)
		{
			LOG.warn("Jev was unavailable for {} of {} reviews; they are retried next run", unavailable, reviews.size());
		}
		final boolean jevDown = !reviews.isEmpty() && unavailable == reviews.size();
		return new PerformResult(jevDown ? CronJobResult.ERROR : CronJobResult.SUCCESS, CronJobStatus.FINISHED);
	}

	private List<CustomerReviewModel> find(final CustomerReviewApprovalType status, final int count)
	{
		if (count <= 0)
		{
			return List.of();
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery(UNJUDGED_REVIEWS,
				Map.of("status", status, "useCase", USE_CASE, "dryRun", dryRun));
		query.setCount(count);
		return flexibleSearchService.<CustomerReviewModel> search(query).getResult();
	}

	private JevJudgmentModel judgment(final CustomerReviewModel review, final LanguageModel language, final JevAnswers answers,
			final CustomerReviewApprovalType decision, final String humanDecision)
	{
		final JevJudgmentModel judgment = modelService.create(JevJudgmentModel.class);
		judgment.setItem(review);
		judgment.setUseCase(USE_CASE);
		judgment.setDryRun(dryRun);
		judgment.setLanguage(language == null ? null : language.getIsocode());
		judgment.setModel(answers.model());
		judgment.setAnswers(answers.answers().toString());
		judgment.setDecision(decision.getCode());
		judgment.setHumanDecision(humanDecision);
		judgment.setInputTokens(answers.inputTokens());
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

	public void setDryRun(final boolean dryRun)
	{
		this.dryRun = dryRun;
	}

	/** What happened to the reviews of one language in one run. */
	static final class Tally
	{
		private int judged;
		private int decided;
		private int agreed;
		private int approvedButRejected;
		private int rejectedButApproved;

		void add(final String decision, final String humanDecision)
		{
			judged++;
			if (CustomerReviewApprovalType.PENDING.getCode().equals(decision))
			{
				return;
			}
			decided++;
			if (humanDecision == null)
			{
				return;
			}
			if (decision.equals(humanDecision))
			{
				agreed++;
			}
			else if (CustomerReviewApprovalType.APPROVED.getCode().equals(decision))
			{
				approvedButRejected++;
			}
			else
			{
				rejectedButApproved++;
			}
		}

		String describe(final boolean dryRun)
		{
			final String base = judged + " judged, " + decided + " decided by Jev, " + (judged - decided) + " left for a person";
			return dryRun ? base + "; of Jev's decisions " + agreed + " match your moderators, " + approvedButRejected
					+ " approved what they rejected, " + rejectedButApproved + " rejected what they approved" : base;
		}
	}
}
