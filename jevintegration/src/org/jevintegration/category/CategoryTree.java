package org.jevintegration.category;

import de.hybris.platform.catalog.model.classification.ClassificationClassModel;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.core.model.product.ProductModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * The part of the category tree Jev chooses from, loaded once per run with names in one language.
 * SAP categories form a graph (a category can have several parents), so a category reachable by two
 * paths appears under both. Classification classes and variant categories are not navigation
 * categories and are left out.
 */
public final class CategoryTree
{
	public static final String NONE = "none";
	/** Jev's Choice takes up to 255 options; one is kept for "none". */
	static final int MAX_CHILDREN = 254;
	static final int MAX_DEPTH = 12;
	private static final Set<String> NOT_NAVIGATION = Set.of("VariantCategory", "VariantValueCategory");

	/** One category; the root and "none" are not real categories. */
	public record Node(String code, String name, List<Node> children)
	{
		public boolean isLeaf()
		{
			return children.isEmpty();
		}
	}

	private final Node root;
	private final Set<CategoryModel> categories;
	private final Map<String, Set<String>> ancestors;
	private final Map<String, Set<String>> parents;

	private CategoryTree(final Node root, final Set<CategoryModel> categories, final Map<String, Set<String>> ancestors,
			final Map<String, Set<String>> parents)
	{
		this.root = root;
		this.categories = categories;
		this.ancestors = ancestors;
		this.parents = parents;
	}

	/**
	 * @throws IllegalStateException when a category has more subcategories than one Jev question can list
	 */
	public static CategoryTree load(final Collection<CategoryModel> roots, final Locale locale)
	{
		final Set<CategoryModel> categories = new LinkedHashSet<>();
		final Map<String, Set<String>> ancestors = new HashMap<>();
		final Map<String, Set<String>> parents = new HashMap<>();
		final List<Node> top = new ArrayList<>();
		for (final CategoryModel category : roots)
		{
			top.add(node(category, locale, new ArrayDeque<>(), categories, ancestors, parents));
		}
		top.add(new Node(NONE, "None of these categories fits the product", List.of()));
		return new CategoryTree(new Node("", "", List.copyOf(top)), categories, ancestors, parents);
	}

	private static Node node(final CategoryModel category, final Locale locale, final Deque<String> path,
			final Set<CategoryModel> categories, final Map<String, Set<String>> ancestors, final Map<String, Set<String>> parents)
	{
		categories.add(category);
		ancestors.computeIfAbsent(category.getCode(), code -> new HashSet<>()).addAll(path);
		if (!path.isEmpty())
		{
			parents.computeIfAbsent(category.getCode(), code -> new HashSet<>()).add(path.peek());
		}
		final String name = Objects.toString(category.getName(locale), category.getCode());
		if (path.size() >= MAX_DEPTH || path.contains(category.getCode()))
		{
			return new Node(category.getCode(), name, List.of()); // too deep, or a cycle in the category graph
		}
		path.push(category.getCode());
		final List<Node> children = category.getCategories().stream()
				.filter(child -> !(child instanceof ClassificationClassModel) && !NOT_NAVIGATION.contains(child.getItemtype()))
				.map(child -> node(child, locale, path, categories, ancestors, parents))
				.toList();
		path.pop();
		if (children.size() > MAX_CHILDREN)
		{
			throw new IllegalStateException("Category " + category.getCode() + " has " + children.size()
					+ " subcategories, but one Jev question takes at most " + (MAX_CHILDREN + 1) + " options. Split that level.");
		}
		return new Node(category.getCode(), name, children);
	}

	public Node root()
	{
		return root;
	}

	/** Every category in the tree, for the product queries. */
	public Set<CategoryModel> categories()
	{
		return categories;
	}

	/** The codes of the product's categories that belong to this tree, comma-separated; the label a dry run compares with. */
	public String codesOf(final ProductModel product)
	{
		return product.getSupercategories().stream()
				.filter(categories::contains)
				.map(CategoryModel::getCode)
				.collect(Collectors.joining(","));
	}

	/** Two different categories under the same parent: a suggestion one step off. */
	public boolean siblings(final String a, final String b)
	{
		return !a.equals(b) && parents.getOrDefault(a, Set.of()).stream().anyMatch(parents.getOrDefault(b, Set.of())::contains);
	}

	/** Same category, or one is an ancestor of the other: a suggestion on the right branch. */
	public boolean sameBranch(final String a, final String b)
	{
		return a.equals(b) || ancestors.getOrDefault(a, Set.of()).contains(b) || ancestors.getOrDefault(b, Set.of()).contains(a);
	}
}
