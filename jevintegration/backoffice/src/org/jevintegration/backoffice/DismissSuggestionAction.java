package org.jevintegration.backoffice;

import org.jevintegration.JevSuggestionService;
import org.jevintegration.model.JevJudgmentModel;

import com.hybris.cockpitng.dataaccess.facades.permissions.PermissionFacade;


/** Marks the selected suggestions as dismissed; the products stay as they are. */
public class DismissSuggestionAction extends SuggestionAction
{
	@Override
	boolean allowed(final JevSuggestionService suggestions, final PermissionFacade permissions, final JevJudgmentModel judgment)
	{
		return suggestions.canDismiss(judgment) && permissions.canChangeInstance(judgment);
	}

	@Override
	void handle(final JevSuggestionService suggestions, final JevJudgmentModel judgment)
	{
		suggestions.dismiss(judgment);
	}
}
