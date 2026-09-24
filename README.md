# jev-sap-commerce

`jevintegration` is an SAP Commerce extension that lets [Jev](https://docs.typesafe.ai/introduction),
TypeSafe's typed-decision model, make narrow decisions about shop text. You measure it on your own
data before it changes anything. Version 1 moderates product reviews.

Jev does not write text. For each review it answers four yes/no questions, each with a
probability: is it abusive, is it spam, does it contain personal data, is it about the product.
The extension turns those probabilities into approve, reject or leave pending. Anything Jev isn't
sure about stays pending for a person.

This is an independent project, not made by or affiliated with TypeSafe or SAP.

## What's in v1

- **Dry run:** judges reviews your moderators already approved or rejected, and records Jev's
  decision next to theirs without changing anything. Each run writes one summary line per language
  to the cronjob's log file and to the server log.
- **Live:** judges pending reviews. It approves or rejects a review only when the answers clear
  your thresholds; everything else stays pending.
- **An audit record per decision** (`JevJudgment`): the judged item, the language, the Jev model
  version, Jev's raw answers, the decision and, for a dry run, the moderator's decision. Records
  are read-only once written. You can find them in Backoffice under **Jev**.
- **Safe defaults:** without an API key every call is skipped. The two cronjobs have no triggers,
  so nothing runs until you start it. When Jev is unavailable, nothing is recorded and the next run
  retries.

It needs the `customerreview` extension and adds no library dependency.

**Tested:** the test suite passes on SAP Commerce **2211.46 (JDK 17)** and **2211-jdk21.17 (JDK 21)**.
On 2211-jdk21.17, a full run was also checked in a running server: system update, both cronjobs,
the Backoffice screens, and a dry run against the real Jev API (see [First results](#first-results-with-the-real-jev-api)).

## First results with the real Jev API

On 2026-09-24, the dry run judged the 80 synthetic reviews in [`eval/`](eval) with `jev-1.13.0` and
the default thresholds:

| Language | Reviews | Decided by Jev | Matching the label | Left for a person |
| --- | --- | --- | --- | --- |
| de | 20 | 19 | 19 | 1 |
| en | 20 | 19 | 19 | 1 |
| fr | 20 | 18 | 18 | 2 |
| it | 20 | 18 | 18 | 2 |

- No wrong approvals and no wrong rejections.
- Left for a person:
  - the 4 reviews containing a third person's contact details, which the policy sends to a person;
  - the French and Italian examples of harsh but legitimate criticism, with abuse probabilities of
    0.21 and 0.29, which fall between the thresholds.
- Every violation (insults, a threat, harassment, spam, gibberish, off-topic, prompt injection)
  scored 0.94 or higher. Every legitimate review scored 0.29 or lower. The prompt-injection reviews
  ("ignore all previous instructions…") were rejected as spam.
- Cost and speed: 55,850 input tokens in total (about 700 per review), roughly $0.002. About 0.3 s
  per review, run one after another, including SAP's own processing.

These reviews were written for the test and are clear-cut. Real reviews are messier: sarcasm,
mixed languages, Swiss German dialect, borderline insults. So this shows the setup works in four
languages; it doesn't tell you your accuracy. Run the dry run on your own moderated reviews before
going live. Per-review scores are in [`eval/results-jev-1.13.0.tsv`](eval/results-jev-1.13.0.tsv).

## Install

1. Copy `jevintegration/` from this repo into `hybris/bin/custom/`, and add
   `<extension name="jevintegration"/>` to `localextensions.xml`. On CCv2, add it to `manifest.json`
   as well.
2. Build, then update the system with the extension's essential data (`ant updatesystem`, or deploy
   on CCv2 with data migration). This creates the `JevJudgment` table and two cronjobs,
   `jevReviewDryRunCronJob` and `jevReviewModerationCronJob`.
3. Set the key: `jev.api.key` in `local.properties` locally, or as a service property in Cloud
   Portal on CCv2. Never commit it. Get a key from the [TypeSafe console](https://console.typesafe.ai/keys).
4. On CCv2, make sure the environment can reach `api.typesafe.ai` over outbound HTTPS.

## Measure first: the dry run

1. In Backoffice, run `jevReviewDryRunCronJob`. It takes up to `jev.review.batch.size` reviews
   (default 200), half approved and half rejected, starting with the newest. Reviews already judged
   in a dry run are skipped, so each run adds new ones.
2. The cronjob's log file (`CronJob.logFiles`, also in the server log) has one line per language,
   for example:

   ```
   Jev review moderation, dry run, language de: 20 judged, 19 decided by Jev, 1 left for a person;
   of Jev's decisions 19 match your moderators, 0 approved what they rejected, 0 rejected what they approved
   ```

   That line comes from the synthetic run above.
3. For the full comparison table per language, run this in HAC (FlexibleSearch):

   ```sql
   SELECT {language}, {humanDecision}, {decision}, COUNT({pk}) FROM {JevJudgment}
   WHERE {useCase} = 'reviewModeration' AND {dryRun} = 1
   GROUP BY {language}, {humanDecision}, {decision} ORDER BY {language}, {humanDecision}, {decision}
   ```

4. Adjust the thresholds (below) until Jev's own decisions nearly always match your moderators'.
   What's left pending is what people still review. Watch the "approved what they rejected" count
   most closely.

Jev's strongest language is English. For German, French or Italian storefronts, read the
per-language lines before you go live. In the synthetic run, the French and Italian examples of
harsh criticism were the only legitimate reviews left for a person.

## Go live

Add a trigger to `jevReviewModerationCronJob`, for example every 5 minutes, in Backoffice or ImpEx.
It picks up new reviews that are still pending and haven't been judged yet.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `jev.api.key` | empty | TypeSafe API key. Empty = no calls. |
| `jev.model` | `jev-1.13.0` | Pin the version your thresholds were tuned on. `jev-latest` moves when TypeSafe ships. |
| `jev.review.reject.at` | `0.85` | Abusive or spam at or above this: rejected. |
| `jev.review.approve.below` | `0.15` | Every risk below this, and on topic: approved. |
| `jev.review.personaldata.at` | `0.5` | Personal data at or above this: pending, so a person can edit it. |
| `jev.review.ontopic.at` | `0.5` | Needed to approve. |
| `jev.review.batch.size` | `200` | Reviews per run. Calls are sequential, about 3 per second. |
| `jev.timeout.ms`, `jev.max.attempts` | `2000`, `3` | Per call. 408, 429 and 5xx are retried with backoff. |

The thresholds are starting guesses. Use the dry run to set them for your shop.

## What leaves your platform

For each review, Jev receives the product name, the review headline and the comment. It does not
receive the author, email, customer id or rating. The questions tell Jev to treat review text as
data, even when it looks like an instruction.

TypeSafe is a US API. Before going live, settle the data protection position (GDPR, Swiss DSG, a
DPA with TypeSafe) with whoever owns your customer data. According to TypeSafe, it doesn't train
on requests, and zero data retention is available on enterprise plans.

## Add your own use case

Follow the `org.jevintegration.review` package:

1. A questions class: builds `state` (only the fields the questions need), holds every question,
   and has a pure `decide()`.
2. A job (`AbstractJobPerformable`): selects items that have no `JevJudgment` for its use case, makes
   one `JevClient.ask` call per item, saves a `JevJudgment`, and applies a result only when Jev is
   sure.
3. A dry-run mode that compares against decisions people already made, and thresholds in
   `project.properties`.

Keep calls out of interceptors. They run inside every save, including ImpEx and catalog sync.

## Development

```bash
# from hybris/bin/platform, after adding the extension to localextensions.xml
ant all && ant yunitinit
ant alltests -Dtestclasses.extensions=jevintegration
```

The tests use a local fake Jev endpoint, so they need no network or API key. They cover:
- the client: retries, giving up, a missing answer, no key;
- the moderation policy;
- the dry run and live job against the junit tenant, and a Jev outage;
- the essential data ImpEx, with both cronjobs run through SAP's cronjob service;
- read-only judgments;
- the FlexibleSearch query above.

## Also in this repo

`skills/jev-sap-commerce/` is a Claude Code skill for building on this extension: where in SAP Commerce to call Jev,
and which decisions to keep in code. Install it with `npx skills add Emenowicz/jev-sap-commerce`, or
copy `skills/jev-sap-commerce/` to `~/.claude/skills/`. It pairs with TypeSafe's own skill
(`claude plugin marketplace add typesafe-ai/skills`).

## Licence

[Apache-2.0](LICENSE).

## Not yet in v1

- Category suggestions from product text. Next, if the review dry runs look good.
- A Backoffice button to judge a single item on demand.
- Re-scoring stored answers with new thresholds without calling Jev again. The raw answers are
  already saved in `JevJudgment.answers`.
