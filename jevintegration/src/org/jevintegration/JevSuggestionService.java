package org.jevintegration;

import de.hybris.platform.catalog.model.classification.ClassAttributeAssignmentModel;
import de.hybris.platform.catalog.model.classification.ClassificationAttributeValueModel;
import de.hybris.platform.category.CategoryService;
import de.hybris.platform.category.model.CategoryModel;
import de.hybris.platform.classification.ClassificationService;
import de.hybris.platform.classification.features.Feature;
import de.hybris.platform.classification.features.FeatureValue;
import de.hybris.platform.classification.features.LocalizedFeature;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jevintegration.attribute.AttributeQuestions;
import org.jevintegration.attribute.JevAttributeSuggestionJob;
import org.jevintegration.category.CategorySuggestionQuestions;
import org.jevintegration.category.CategoryTree;
import org.jevintegration.category.JevCategorySuggestionJob;
import org.jevintegration.model.JevJudgmentModel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * What a person does with a category or attribute suggestion: apply it to the product, or dismiss it. The jobs never
 * change a product; this is the one place that does, and only when a person asks for it (the Backoffice actions).
 */
public class JevSuggestionService
{
	public static final String APPLIED = "applied";
	public static final String DISMISSED = "dismissed";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ModelService modelService;
	private UserService userService;
	private CategoryService categoryService;
	private ClassificationService classificationService;

	/** An open suggestion: a category or attribute suggestion, not from a dry run, that nobody has resolved yet. */
	public boolean canDismiss(final JevJudgmentModel judgment)
	{
		return !judgment.isDryRun() && judgment.getResolution() == null && (isCategory(judgment)
				|| JevAttributeSuggestionJob.USE_CASE.equals(judgment.getUseCase()));
	}

	/** An open suggestion that suggests something: a category, or a value for at least one attribute. */
	public boolean canApply(final JevJudgmentModel judgment)
	{
		if (!canDismiss(judgment) || !(judgment.getItem() instanceof ProductModel))
		{
			return false;
		}
		return isCategory(judgment)
				? !CategorySuggestionQuestions.PENDING.equals(judgment.getDecision()) && !CategoryTree.NONE.equals(judgment.getDecision())
				: !suggestedValues(judgment).isEmpty();
	}

	/**
	 * Adds the suggested category to the product, or sets each suggested value whose attribute is still empty, and records
	 * who applied it. Returns the number of changes, 0 when the product already had them.
	 */
	public int apply(final JevJudgmentModel judgment)
	{
		if (!canApply(judgment))
		{
			throw new IllegalStateException("Not an open suggestion with something to apply: " + judgment.getPk());
		}
		final ProductModel product = (ProductModel) judgment.getItem();
		final int changes = isCategory(judgment) ? addCategory(product, judgment.getDecision())
				: setValues(product, suggestedValues(judgment));
		resolve(judgment, APPLIED);
		return changes;
	}

	public void dismiss(final JevJudgmentModel judgment)
	{
		if (!canDismiss(judgment))
		{
			throw new IllegalStateException("Not an open suggestion: " + judgment.getPk());
		}
		resolve(judgment, DISMISSED);
	}

	private static boolean isCategory(final JevJudgmentModel judgment)
	{
		return JevCategorySuggestionJob.USE_CASE.equals(judgment.getUseCase());
	}

	/** Adds, never replaces: the suggestion job only picks products with no category in the tree. */
	private int addCategory(final ProductModel product, final String code)
	{
		// the job reads products and the tree from the same catalog version
		final CategoryModel category = categoryService.getCategoryForCode(product.getCatalogVersion(), code);
		if (product.getSupercategories().contains(category))
		{
			return 0;
		}
		final List<CategoryModel> categories = new ArrayList<>(product.getSupercategories());
		categories.add(category);
		product.setSupercategories(categories);
		modelService.save(product);
		return 1;
	}

	private int setValues(final ProductModel product, final Map<String, String> valueByAttribute)
	{
		int changes = 0;
		for (final Feature feature : classificationService.getFeatures(product))
		{
			final ClassAttributeAssignmentModel assignment = feature.getClassAttributeAssignment();
			final String code = assignment == null ? null : valueByAttribute.get(assignment.getClassificationAttribute().getCode());
			// ponytail: a localized enum feature holds values per language; unusual, so it is left to a person
			if (code == null || !feature.getValues().isEmpty() || feature instanceof LocalizedFeature)
			{
				continue; // not suggested, filled since the run, or localized
			}
			final Optional<ClassificationAttributeValueModel> value = assignment.getAttributeValues().stream()
					.filter(allowed -> code.equals(allowed.getCode())).findFirst();
			if (value.isPresent())
			{
				feature.addValue(new FeatureValue(value.get()));
				classificationService.setFeature(product, feature);
				changes++;
			}
		}
		return changes;
	}

	/** Attribute code to the suggested value code, from the answers the job recorded. */
	private static Map<String, String> suggestedValues(final JevJudgmentModel judgment)
	{
		final Map<String, String> values = new LinkedHashMap<>();
		try
		{
			for (final JsonNode attribute : MAPPER.readTree(Objects.toString(judgment.getAnswers(), "{}")).path("attributes"))
			{
				final String decision = attribute.path("decision").asText();
				if (!AttributeQuestions.NOT_STATED.equals(decision) && !AttributeQuestions.PENDING.equals(decision))
				{
					values.put(attribute.path("attribute").asText(), decision);
				}
			}
		}
		catch (final JsonProcessingException e)
		{
			throw new IllegalStateException("Unreadable answers in judgment " + judgment.getPk(), e);
		}
		return values;
	}

	private void resolve(final JevJudgmentModel judgment, final String resolution)
	{
		judgment.setResolution(resolution);
		judgment.setResolvedBy(userService.getCurrentUser());
		judgment.setResolvedAt(new Date());
		modelService.save(judgment);
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setCategoryService(final CategoryService categoryService)
	{
		this.categoryService = categoryService;
	}

	public void setClassificationService(final ClassificationService classificationService)
	{
		this.classificationService = classificationService;
	}
}
