package org.jevintegration.backoffice;

import org.jevintegration.JevSuggestionService;
import org.jevintegration.model.JevJudgmentModel;

import com.hybris.cockpitng.actions.ActionContext;
import com.hybris.cockpitng.dataaccess.facades.permissions.PermissionFacade;


/** Applies the selected suggestions to their products, after a confirmation: it changes products. */
public class ApplySuggestionAction extends SuggestionAction
{
	@Override
	boolean allowed(final JevSuggestionService suggestions, final PermissionFacade permissions, final JevJudgmentModel judgment)
	{
		return suggestions.canApply(judgment) && permissions.canChangeInstance(judgment)
				&& permissions.canChangeInstance(judgment.getItem());
	}

	@Override
	void handle(final JevSuggestionService suggestions, final JevJudgmentModel judgment)
	{
		suggestions.apply(judgment);
	}

	@Override
	public boolean needsConfirmation(final ActionContext<Object> ctx)
	{
		return true;
	}

	@Override
	public String getConfirmationMessage(final ActionContext<Object> ctx)
	{
		return ctx.getLabel("confirmation");
	}
}
