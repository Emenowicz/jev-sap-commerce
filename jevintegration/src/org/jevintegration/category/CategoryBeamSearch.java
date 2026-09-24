package org.jevintegration.category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jevintegration.category.CategoryTree.Node;


/**
 * TypeSafe's hierarchical classification: at every level, ask one Choice per kept path, extend each path
 * by every child, and keep the best {@code width} paths by the geometric mean of their step probabilities.
 * Keeping more than one path lets deeper evidence repair an unclear early step (width 1 is greedy search).
 * See https://docs.typesafe.ai/cookbooks/hierarchical_classification.
 */
final class CategoryBeamSearch
{
	private static final double EPSILON = 1e-9;

	/** A path from the root; its score compares shallow and deep leaves fairly. */
	record Candidate(List<Node> path, double probabilityProduct, int decisions)
	{
		double score()
		{
			return decisions == 0 ? 1.0 : Math.pow(probabilityProduct, 1.0 / decisions);
		}

		Node end(final Node root)
		{
			return path.isEmpty() ? root : path.get(path.size() - 1);
		}
	}

	/** One question: the category asked about and its five most likely children, for the audit record. */
	record Round(String parent, Map<String, Double> top)
	{
	}

	record Result(Candidate best, List<Candidate> beam, List<Round> rounds)
	{
	}

	/**
	 * Asks Jev, in one request, about the category at the end of each path (the root for an empty path): per path,
	 * the probability of each child code. Empty = Jev unavailable.
	 */
	interface Asker
	{
		Optional<List<Map<String, Double>>> ask(List<List<Node>> paths);
	}

	private CategoryBeamSearch()
	{
	}

	static Optional<Result> search(final Node root, final int width, final Asker asker)
	{
		List<Candidate> beam = List.of(new Candidate(List.of(), 1.0, 0));
		final List<Round> rounds = new ArrayList<>();
		for (int depth = 0; depth <= CategoryTree.MAX_DEPTH; depth++)
		{
			final List<Candidate> open = beam.stream().filter(c -> !c.end(root).isLeaf()).toList();
			if (open.isEmpty())
			{
				break;
			}
			final List<Candidate> asked = open.stream().filter(c -> c.end(root).children().size() > 1).toList();
			final List<Map<String, Double>> answers;
			if (asked.isEmpty())
			{
				answers = List.of();
			}
			else
			{
				final Optional<List<Map<String, Double>>> answered = asker.ask(asked.stream().map(Candidate::path).toList());
				if (answered.isEmpty())
				{
					return Optional.empty();
				}
				answers = answered.get();
			}

			final List<Candidate> next = new ArrayList<>(beam.stream().filter(c -> c.end(root).isLeaf()).toList());
			for (final Candidate candidate : open)
			{
				final Node node = candidate.end(root);
				final int index = asked.indexOf(candidate);
				if (index < 0)
				{
					next.add(extend(candidate, node.children().get(0), 1.0, false)); // one child: nothing to decide
					continue;
				}
				final Map<String, Double> probabilities = answers.get(index);
				rounds.add(new Round(node.code(), top(probabilities, 5)));
				for (final Node child : node.children())
				{
					next.add(extend(candidate, child, probabilities.getOrDefault(child.code(), 0.0), true));
				}
			}
			beam = next.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).limit(width).toList();
		}
		return Optional.of(new Result(beam.get(0), beam, rounds));
	}

	private static Candidate extend(final Candidate candidate, final Node child, final double probability, final boolean decision)
	{
		final List<Node> path = new ArrayList<>(candidate.path());
		path.add(child);
		return new Candidate(List.copyOf(path),
				candidate.probabilityProduct() * (decision ? Math.max(probability, EPSILON) : 1.0),
				candidate.decisions() + (decision ? 1 : 0));
	}

	private static Map<String, Double> top(final Map<String, Double> probabilities, final int n)
	{
		final Map<String, Double> top = new LinkedHashMap<>();
		probabilities.entrySet().stream()
				.sorted(Map.Entry.<String, Double> comparingByValue().reversed())
				.limit(n)
				.forEach(e -> top.put(e.getKey(), e.getValue()));
		return top;
	}
}
