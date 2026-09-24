package org.jevintegration.review;

import static org.junit.Assert.assertEquals;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.customerreview.enums.CustomerReviewApprovalType;

import java.util.Locale;

import org.jevintegration.JevClient.JevAnswers;
import org.jevintegration.review.ReviewModerationQuestions.Thresholds;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;


@UnitTest
public class ReviewModerationQuestionsTest
{
	private static final Thresholds DEFAULTS = new Thresholds(0.85, 0.15, 0.5, 0.5);

	@Test
	public void actsOnlyWhenSure() throws Exception
	{
		assertEquals(CustomerReviewApprovalType.REJECTED, decide(0.9, 0.0, 0.0, 1.0));
		assertEquals(CustomerReviewApprovalType.REJECTED, decide(0.0, 0.9, 0.0, 1.0));
		assertEquals(CustomerReviewApprovalType.APPROVED, decide(0.05, 0.05, 0.05, 0.9));
		assertEquals(CustomerReviewApprovalType.PENDING, decide(0.5, 0.0, 0.0, 0.9)); // unsure
		assertEquals(CustomerReviewApprovalType.PENDING, decide(0.0, 0.0, 0.9, 0.9)); // personal data: a person edits it
		assertEquals(CustomerReviewApprovalType.PENDING, decide(0.0, 0.0, 0.0, 0.1)); // off topic but harmless
	}

	private static CustomerReviewApprovalType decide(final double abusive, final double spam, final double personal,
			final double onTopic) throws Exception
	{
		final String json = String.format(Locale.ROOT,
				"{\"abusive\":{\"noul\":%s},\"spam\":{\"noul\":%s},\"personal_data\":{\"noul\":%s},\"about_product\":{\"noul\":%s}}",
				abusive, spam, personal, onTopic);
		return ReviewModerationQuestions.decide(new JevAnswers("jev-1.13.0", new ObjectMapper().readTree(json), 0), DEFAULTS);
	}
}
