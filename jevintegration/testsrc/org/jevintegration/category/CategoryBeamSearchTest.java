package org.jevintegration.category;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jevintegration.category.CategoryBeamSearch.Result;
import org.jevintegration.category.CategorySuggestionQuestions.Question;
import org.jevintegration.category.CategoryTree.Node;
import org.junit.Test;


@UnitTest
public class CategoryBeamSearchTest
{
	private static final Node CORDLESS = leaf("cordless", "Cordless drills");
	private static final Node CORDED = leaf("corded", "Corded drills");
	private static final Node DRILLS = new Node("drills", "Drills", List.of(CORDLESS, CORDED));
	private static final Node SAWS = leaf("saws", "Saws");
	private static final Node TOOLS = new Node("tools", "Tools", List.of(DRILLS, SAWS));
	private static final Node FAUCETS = leaf("faucets", "Faucets");
	private static final Node PIPES = leaf("pipes", "Pipes");
	private static final Node PLUMBING = new Node("plumbing", "Plumbing", List.of(FAUCETS, PIPES));
	private static final Node NONE = leaf(CategoryTree.NONE, "None of these categories fits the product");
	private static final Node ROOT = new Node("", "", List.of(TOOLS, PLUMBING, NONE));

	/** A product that looks a little more like a tool at first, but is clearly a faucet one level down. */
	private static final Map<String, Map<String, Double>> FAUCET_ANSWERS = Map.of(
			"", Map.of("tools", 0.55, "plumbing", 0.45, CategoryTree.NONE, 0.0),
			"tools", Map.of("drills", 0.55, "saws", 0.45),
			"drills", Map.of("cordless", 0.6, "corded", 0.4),
			"plumbing", Map.of("faucets", 0.99, "pipes", 0.01));

	@Test
	public void beamRecoversFromAnUnclearFirstStep()
	{
		assertEquals("faucets", best(3, FAUCET_ANSWERS));
		assertEquals("greedy commits to the first step and cannot come back", "cordless", best(1, FAUCET_ANSWERS));
	}

	@Test
	public void scoreIsTheGeometricMeanOfTheDecisions()
	{
		final Result result = CategoryBeamSearch.search(ROOT, 3, asker(FAUCET_ANSWERS)).orElseThrow();
		assertEquals(Math.sqrt(0.45 * 0.99), result.best().score(), 1e-9);
	}

	@Test
	public void singleChildNeedsNoQuestion()
	{
		final Node onlyChild = new Node("fittings", "Fittings", List.of(leaf("elbows", "Elbows")));
		final Node root = new Node("", "", List.of(onlyChild, NONE));
		final Result result = CategoryBeamSearch.search(root, 3,
				asker(Map.of("", Map.of("fittings", 0.9, CategoryTree.NONE, 0.1)))).orElseThrow();
		assertEquals("elbows", result.best().end(root).code());
		assertEquals("only the root was a decision", 1, result.best().decisions());
		assertEquals(1, result.rounds().size());
	}

	@Test
	public void policyLeavesUnclearAndUnrelatedProductsForAPerson()
	{
		final Result sure = CategoryBeamSearch.search(ROOT, 3, asker(FAUCET_ANSWERS)).orElseThrow();
		assertEquals("faucets", CategorySuggestionQuestions.decide(sure, 0.6));
		assertEquals(CategorySuggestionQuestions.PENDING, CategorySuggestionQuestions.decide(sure, 0.7));

		final Map<String, Map<String, Double>> unrelatedAnswers = new HashMap<>(FAUCET_ANSWERS);
		unrelatedAnswers.put("", Map.of("tools", 0.05, "plumbing", 0.05, CategoryTree.NONE, 0.9));
		final Result unrelated = CategoryBeamSearch.search(ROOT, 3, asker(unrelatedAnswers)).orElseThrow();
		assertEquals(CategoryTree.NONE, CategorySuggestionQuestions.decide(unrelated, 0.6));
	}

	@Test
	public void jevDownMeansNoResult()
	{
		assertTrue(CategoryBeamSearch.search(ROOT, 3, paths -> Optional.empty()).isEmpty());
	}

	@Test
	public void questionListsChildrenUnderNeutralKeysAndNamesThePath()
	{
		final Question root = CategorySuggestionQuestions.question(ROOT, List.of());
		assertEquals(Map.of("c0", "tools", "c1", "plumbing", CategoryTree.NONE, CategoryTree.NONE), root.codeByKey());
		final Question drills = CategorySuggestionQuestions.question(DRILLS, List.of(TOOLS, DRILLS));
		assertTrue(((String) drills.choice().get("instructions")).contains("\"Tools > Drills\""));
		assertEquals(Map.of("c0", "Cordless drills", "c1", "Corded drills"), drills.choice().get("criteria"));
	}

	@Test
	public void htmlIsRemovedFromDescriptions()
	{
		assertEquals("18V drill & charger", CategorySuggestionQuestions.stripHtml("<p>18V <b>drill</b>&nbsp;&amp; charger</p>"));
	}

	private static String best(final int width, final Map<String, Map<String, Double>> answers)
	{
		return CategoryBeamSearch.search(ROOT, width, asker(answers)).orElseThrow().best().end(ROOT).code();
	}

	/** Answers every question from a table keyed by the category asked about. */
	private static CategoryBeamSearch.Asker asker(final Map<String, Map<String, Double>> answers)
	{
		return paths -> Optional.of(paths.stream()
				.map(path -> answers.get(path.isEmpty() ? "" : path.get(path.size() - 1).code()))
				.toList());
	}

	private static Node leaf(final String code, final String name)
	{
		return new Node(code, name, List.of());
	}
}
