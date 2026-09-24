# Where to call Jev in SAP Commerce

A Jev call takes 70 to 700 ms, can return 429 or 529, and sometimes fails. Choose the call site by
how long the caller can wait and what happens on "no decision".

| Call site | Sync? | In a DB transaction? | Latency budget | On "no decision" | Good for |
| --- | --- | --- | --- | --- | --- |
| Cronjob, `AbstractJobPerformable<CronJobModel>` | No | Only your own save | Seconds | Leave the item unmarked; the next run retries it | Review backlog, product copy classification, catalog quality, re-judging after a model upgrade |
| Business process action, `AbstractSimpleDecisionAction<T>` | No | Only the action's own work | Seconds | Return `Transition.NOK` or route to a manual-review action | Order and delivery notes, gift messages, B2B quote and approval comments, return reasons |
| Facade or OCC controller, before the save | Yes, the shopper waits | No (call before `modelService.save`) | About 1 s timeout, 1 attempt | Save the safe state (`pending`, "needs review") and answer the shopper normally | Instant review feedback, ticket routing at submit time, search intent |
| Backoffice action or editor button | Yes, the employee waits | No | A few seconds | Show "no suggestion", change nothing | "Suggest category", "check this copy", on demand |
| Event listener (`AbstractEventListener`) | Depends on whether the event is cluster-aware or async | May run inside the publisher's tx | Treat as sync unless the event is async | Log and drop, then let a cronjob sweep | Only if the event is already async; otherwise use a cronjob |
| `PrepareInterceptor`, `ValidateInterceptor`, `LoadInterceptor` | Yes | **Yes** | None | Every save of the type fails or slows | **Never.** These run on every save, including ImpEx, sync, and other nodes |

## Patterns per call site

### Cronjob (default)

- Record a `JevJudgment` for every item you judge, and select only items that have none for this use
  case (see the `jevintegration` extension). Don't select by "status still pending", or the same
  uncertain items fill every batch.
- Use `query.setCount(batchSize)`, `clearAbortRequestedIfNeeded(cronJob)` in the loop,
  `isAbortable() = true`, and catch per item so one bad item does not stop the batch.
- Return `CronJobResult.ERROR` when Jev was unavailable for every item, so monitoring notices.
- Sequential calls give about 3 per second, well under the rate limit. Parallelise only when the
  backlog needs it, and then cap it below 1,200 requests per minute across all nodes. On a cluster,
  pin the cronjob to one node group.
- The cronjob's `sessionLanguage` decides which localized values (`getName()`) go into `state`.

### Business process action

Illustrative sketch; these classes are not in the `jevintegration` extension.

```java
public class CheckDeliveryNoteAction extends AbstractSimpleDecisionAction<OrderProcessModel>
{
	@Override
	public Transition executeAction(final OrderProcessModel process)
	{
		final String note = deliveryNoteOf(process.getOrder()); // the shop's own free-text attribute
		if (StringUtils.isBlank(note))
		{
			return Transition.OK; // nothing to judge, so no call
		}
		return jevClient.ask(Map.of("note", note), DeliveryNoteQuestions.QUESTIONS)
				.map(DeliveryNoteQuestions::needsHuman) // pure policy, in the questions class
				.map(needsHuman -> needsHuman ? Transition.NOK : Transition.OK)
				.orElse(Transition.NOK); // no decision → manual review
	}
}
```

Wire `NOK` in the process XML to a manual-check or wait node, not to failure or cancellation.

### Facade, before the save

- Call Jev before `modelService.save`. Set the result on the model, then save once.
- Use a separate `JevClient` bean with `jev.timeout.ms≈1000` and `maxAttempts=1`. The shopper does
  not wait for backoff.
- The answer the shopper sees must not depend on Jev being up. For reviews, "Thanks, your review is
  being checked" works whether the review was approved or left pending.

## Question shapes that work in commerce

| Need | Primitive | Example |
| --- | --- | --- |
| Several independent risks | One Noul each | abusive, spam, personal_data, off_topic |
| One of a fixed set | Choice + "none" option | ticket team: `orders`, `returns`, `payment`, `product`, `none` |
| Quality on a scale | Score, 3 to 5 levels, each a concrete situation | product copy: "missing key facts" … "complete and specific" |
| Category over a large tree | Choice per level (beam search) | top level, then children of the top-2, until confident |
| Extract a value | Find candidates with regex or code, then a Choice picks one | which of these EANs or sizes the text refers to |

Put the text to judge under named fields (`review.comment`, `product.name`) and refer to them in the
instructions with backticks. Leave out ratings, prices, dates and ids unless the question is about
them. They are distractors, and Jev reads numbers poorly.
