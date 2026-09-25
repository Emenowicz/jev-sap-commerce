package org.jevintegration.backoffice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.jevintegration.JevSuggestionService;
import org.jevintegration.model.JevJudgmentModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hybris.cockpitng.actions.ActionContext;
import com.hybris.cockpitng.actions.ActionResult;
import com.hybris.cockpitng.actions.CockpitAction;
import com.hybris.cockpitng.dataaccess.context.impl.DefaultContext;
import com.hybris.cockpitng.dataaccess.facades.object.ObjectCRUDHandler;
import com.hybris.cockpitng.dataaccess.facades.permissions.PermissionFacade;
import com.hybris.cockpitng.dataaccess.util.CockpitGlobalEventPublisher;
import com.hybris.cockpitng.util.BackofficeSpringUtil;
import com.hybris.cockpitng.util.notifications.NotificationService;
import com.hybris.cockpitng.util.notifications.event.NotificationEvent;
import com.hybris.cockpitng.util.notifications.event.NotificationEventTypes;
import com.hybris.cockpitng.widgets.collectionbrowser.CollectionBrowserController;


/**
 * Applies or dismisses the selected suggestions: one judgment in the editor, or several in the list. The rules are in
 * {@link JevSuggestionService}; this checks the user's Backoffice permissions, which the service layer does not, and
 * reports what happened.
 */
abstract class SuggestionAction implements CockpitAction<Object, Object>
{
	private static final Logger LOG = LoggerFactory.getLogger(SuggestionAction.class);

	/** Whether the service allows this action on the judgment, and the user may make the change. */
	abstract boolean allowed(JevSuggestionService suggestions, PermissionFacade permissions, JevJudgmentModel judgment);

	abstract void handle(JevSuggestionService suggestions, JevJudgmentModel judgment);

	@Override
	public boolean canPerform(final ActionContext<Object> ctx)
	{
		final JevSuggestionService suggestions = suggestions();
		final PermissionFacade permissions = permissions();
		return judgments(ctx).stream().anyMatch(judgment -> allowed(suggestions, permissions, judgment));
	}

	@Override
	public ActionResult<Object> perform(final ActionContext<Object> ctx)
	{
		final JevSuggestionService suggestions = suggestions();
		final PermissionFacade permissions = permissions();
		final List<JevJudgmentModel> judgments = judgments(ctx);
		final List<JevJudgmentModel> done = new ArrayList<>();
		final List<String> failures = new ArrayList<>();
		for (final JevJudgmentModel judgment : judgments)
		{
			if (!allowed(suggestions, permissions, judgment))
			{
				continue; // not an open suggestion, or not the user's to change; counted as not done
			}
			try
			{
				handle(suggestions, judgment);
				done.add(judgment);
			}
			catch (final RuntimeException e)
			{
				LOG.warn("Jev suggestion {} failed [judgment={}]", getClass().getSimpleName(), judgment.getPk(), e);
				failures.add(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
			}
		}

		// BackofficeSpringUtil instead of @Resource: that annotation is javax on 2211 and jakarta on 2211-jdk21
		final NotificationService notifications = BackofficeSpringUtil.getBean("notificationService", NotificationService.class);
		final String source = notifications.getWidgetNotificationSource(ctx);
		notifications.notifyUser(source, NotificationEventTypes.EVENT_TYPE_GENERAL, NotificationEvent.Level.SUCCESS,
				ctx.getLabel("done", new Object[] { done.size(), judgments.size() }));
		if (!failures.isEmpty())
		{
			notifications.notifyUser(source, NotificationEventTypes.EVENT_TYPE_GENERAL, NotificationEvent.Level.FAILURE,
					ctx.getLabel("failed", new Object[] { failures.size(), failures.get(0) }));
		}
		final DefaultContext reload = new DefaultContext();
		reload.addAttribute(CollectionBrowserController.SHOULD_RELOAD_AFTER_UPDATE, Boolean.TRUE);
		BackofficeSpringUtil.<CockpitGlobalEventPublisher> getBean("eventPublisher", CockpitGlobalEventPublisher.class)
				.publish(ObjectCRUDHandler.OBJECTS_UPDATED_EVENT, done, reload);
		return new ActionResult<>(failures.isEmpty() ? ActionResult.SUCCESS : ActionResult.ERROR);
	}

	/** The editor passes one judgment, the list view a collection. */
	static List<JevJudgmentModel> judgments(final ActionContext<Object> ctx)
	{
		final Object data = ctx.getData();
		final Collection<?> items = data instanceof Collection<?> collection ? collection : data == null ? List.of() : List.of(data);
		return items.stream().filter(JevJudgmentModel.class::isInstance).map(JevJudgmentModel.class::cast).toList();
	}

	private static JevSuggestionService suggestions()
	{
		return BackofficeSpringUtil.getBean("jevSuggestionService", JevSuggestionService.class);
	}

	private static PermissionFacade permissions()
	{
		return BackofficeSpringUtil.getBean("permissionFacade", PermissionFacade.class);
	}
}
