package org.jevintegration.attribute;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.util.List;
import java.util.Map;

import org.jevintegration.attribute.AttributeQuestions.Question;
import org.jevintegration.attribute.AttributeQuestions.Target;
import org.jevintegration.attribute.AttributeQuestions.Value;
import org.junit.Test;


@UnitTest
public class AttributeQuestionsTest
{
	private static final Target POWER = new Target("power", "Power source",
			List.of(new Value("ac", "AC-powered"), new Value("battery", "Battery-powered"), new Value("manual", "Manual")));

	@Test
	public void questionListsTheAllowedValuesAndANotStatedOption()
	{
		final Question question = AttributeQuestions.question(POWER);
		assertEquals(Map.of("v0", "ac", "v1", "battery", "v2", "manual", AttributeQuestions.NOT_STATED, AttributeQuestions.NOT_STATED),
				question.codeByKey());
		@SuppressWarnings("unchecked")
		final Map<String, Object> criteria = (Map<String, Object>) question.choice().get("criteria");
		assertEquals("AC-powered", criteria.get("v0"));
		assertEquals("The product text does not say", criteria.get(AttributeQuestions.NOT_STATED));
		assertTrue(((String) question.choice().get("instructions")).startsWith("Which Power source does `product` have?"));
	}

	@Test
	public void suggestsOnlyWhenSureAndNeverInventsAValue()
	{
		assertEquals("ac", AttributeQuestions.decide("ac", 0.9, 0.7));
		assertEquals(AttributeQuestions.PENDING, AttributeQuestions.decide("ac", 0.5, 0.7));
		assertEquals(AttributeQuestions.NOT_STATED, AttributeQuestions.decide(AttributeQuestions.NOT_STATED, 0.99, 0.7));
	}
}
