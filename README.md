# jev-sap-commerce

`jevintegration` is an SAP Commerce extension that lets [Jev](https://docs.typesafe.ai/introduction),
TypeSafe's typed-decision model, make narrow decisions about shop text. It **moderates product
reviews**, **suggests product categories** and **suggests classification attribute values**, and
you measure it on your own data before it changes anything.

Jev does not write text. It answers typed questions (yes/no, pick one, score) with probabilities,
and the extension's Java code decides what to do with them. Anything Jev isn't sure about is left
for a person.

This is an independent project, not made by or affiliated with TypeSafe or SAP.

## What it does

**Review moderation.** For each review, Jev answers four yes/no questions: is it abusive, is it
spam, does it contain personal data, is it about the product. A review is approved or rejected
only when the answers clear your thresholds; everything else stays pending for a moderator.

**Category suggestions.** Jev walks your category tree one level at a time and suggests the
category that fits a product best. It uses TypeSafe's
[hierarchical classification](https://docs.typesafe.ai/cookbooks/hierarchical_classification):
at every level it keeps the 3 most likely paths, so a later level can correct an unclear earlier
one. Suggestions are recorded for a merchandiser to confirm; **the extension never changes a
product's categories.**

**Attribute suggestions.** For each enum attribute of a product's classification class (material,
colour, power source, finish and so on), Jev picks the allowed value the product text states, or
says the text doesn't say. A product's attributes are asked together, up to 25 per request. Suggestions are
recorded for a merchandiser; **the extension never changes a product's feature values.** Numeric,
date and free-text attributes are left out on purpose: that's a job for code.

For all three:
- **Dry runs** judge items people already decided (moderated reviews, categorised products,
  attribute values already set) and
  compare, changing nothing. Each run writes one summary line per language to the cronjob's log
  file and the server log.
- **An audit record per decision** (`JevJudgment`): the item, the language, the Jev model version,
  Jev's raw answers, the decision and, in a dry run, what people had decided. Records are
  read-only once written. You can find them in Backoffice under **Jev**.
- **Safe defaults:** without an API key every call is skipped. The cronjobs have no triggers, so
  nothing runs until you start it. When Jev is unavailable, nothing is recorded and the next run
  retries.

It needs the `customerreview` and `catalog` extensions and adds no library dependency.

**Tested:** the 27 tests pass on SAP Commerce **2211.46 (JDK 17)** and **2211-jdk21.17 (JDK 21)**.
On 2211-jdk21.17, all three use cases also ran in a real server against the real Jev API (below),
and the Backoffice screens were checked.

## Results with the real Jev API

All runs used `jev-1.13.0`, default thresholds and synthetic data written for the test (all in
[`eval/`](eval)). They show that the setup works in English, German, French and Italian. They don't
tell you your accuracy: run the dry runs on your own data before going live.

### Review moderation: 80 reviews

| Language | Reviews | Decided by Jev | Matching the label | Left for a person |
| --- | --- | --- | --- | --- |
| de | 20 | 19 | 19 | 1 |
| en | 20 | 19 | 19 | 1 |
| fr | 20 | 18 | 18 | 2 |
| it | 20 | 18 | 18 | 2 |

- No wrong approvals and no wrong rejections.
- Left for a person:
  - the 4 reviews containing a third person's contact details, which the policy sends to a person;
  - the French and Italian examples of harsh but legitimate criticism (abuse probability 0.21 and
    0.29, between the thresholds).
- Every violation (insults, a threat, harassment, spam, gibberish, off-topic, prompt injection)
  scored 0.94 or higher; every legitimate review 0.29 or lower. The prompt-injection reviews
  ("ignore all previous instructions…") were rejected as spam.
- About 700 input tokens and 0.3 s per review; the whole run cost roughly $0.002.

Real reviews are messier than these: sarcasm, mixed languages, Swiss German dialect, borderline
insults. Per-review scores: [`eval/review-results-jev-1.13.0.tsv`](eval/review-results-jev-1.13.0.tsv).

### Category suggestions: 40 products × 4 languages

The tree was the Hardware branch of [Shopify's public product taxonomy](https://github.com/Shopify/product-taxonomy):
1,122 categories over 6 levels, with 843 leaf categories to choose from, in each language. The 40
products were written as shop copy (invented brands, sizes, uses), each already in one leaf.

| Language | Suggested | Exact leaf | Neighbouring leaf | Same top-level branch | Different branch | Left for a person |
| --- | --- | --- | --- | --- | --- | --- |
| de | 39 | 30 | 2 | 5 | 2 | 1 |
| en | 39 | 33 | 1 | 4 | 1 | 1 |
| fr | 40 | 29 | 1 | 9 | 1 | 0 |
| it | 35 | 26 | 3 | 5 | 1 | 5 |

- 77% of the suggestions named the exact leaf (EN 85%, DE 77%, IT 74%, FR 73%), and 97% were in the
  right top-level branch.
- Many misses are an overlap in the tree rather than a wrong answer. Shopify has both "Stud
  Finders" and "Stud Sensors", and circular saw blades also fit "Circular Saw Accessories". A clear
  miss: a door handle set was wrong in all four languages, twice under "Locks & Latches", once as a
  bracket and once as a buckle.
- The path score does not separate right from wrong well. Raising `jev.category.min.score` from 0.7
  to 0.9 raises exact matches only from 77% to 83%, and halves how many products get a suggestion.
  That is why suggestions go to a merchandiser rather than onto the product.
- About 4,000 input tokens (one request per level of the tree) and 1.2 s per product; all 160
  classifications cost about $0.03.

The columns come from the per-product results, using the taxonomy's ids to see how close a miss
was. The cronjob log uses coarser groups: neighbouring, parent or child, further off. "Left for a
person" in Italian includes one product Jev placed outside the tree (a pond pump; the tree does
have pond pumps). Per-product results: [`eval/category-results-jev-1.13.0.tsv`](eval/category-results-jev-1.13.0.tsv).

### Attribute suggestions: 21 known values × 4 languages

The same 40 products, in a classification class with three of Shopify's enum attributes and all
their values: Power source (28 values), Hardware material (21) and Color (19). A value was set
only where the product text clearly states it; the dry run asked Jev to find those 21 values again.

| Language | Values judged | Suggested by Jev | Matching | Left for a person (not stated / unsure) |
| --- | --- | --- | --- | --- |
| de | 21 | 15 | 15 | 6 (2 / 4) |
| en | 21 | 15 | 15 | 6 (4 / 2) |
| fr | 21 | 16 | 15 | 5 (3 / 2) |
| it | 21 | 9 | 9 | 12 (8 / 4) |

- 54 of the 55 suggestions matched. The other is a near-duplicate in Shopify's list: "Batteries"
  instead of "Battery-powered".
- By attribute: Hardware material 23 of 24 values found, Color 16 of 16, Power source only 15 of
  44 (plus the near-duplicate).
- Jev reads literally. For corded tools the text says "750 W" but not "mains", so Jev mostly
  answered that the text doesn't say, or was unsure; for hand tools it chose "Manual" about half
  the time. If you want such values filled, state them in the product copy, or derive them with a
  rule in code.
- The suggestion run (English) also asked Jev about the 99 attributes left empty. It said "not
  stated" for 73, was unsure about 8 and filled 18. Nearly all 18 follow from the text: steel for
  a forged steel head, clear for glue that dries transparent, ceramic for ceramic tiles. Silver
  was inferred from chrome or stainless steel. One is wrong: aluminium oxide sandpaper became
  "Aluminum".
- About 950 input tokens per product (the English dry run took 7.5 s for 40 products); all runs
  together cost about $0.005.

Per-attribute results: [`eval/attribute-results-jev-1.13.0.tsv`](eval/attribute-results-jev-1.13.0.tsv).

## Install

1. Copy `jevintegration/` from this repo into `hybris/bin/custom/`, and add
   `<extension name="jevintegration"/>` to `localextensions.xml`. On CCv2, add it to `manifest.json`
   as well.
2. Build, then update the system with the extension's essential data (`ant updatesystem`, or deploy
   on CCv2 with data migration). This creates the `JevJudgment` table and six cronjobs: a dry run
   and a live or suggestion cronjob each for reviews (`jevReview…`), categories (`jevCategory…`)
   and attributes (`jevAttribute…`).
3. Set the key: `jev.api.key` in `local.properties` locally, or as a service property in Cloud
   Portal on CCv2. Never commit it. Get a key from the [TypeSafe console](https://console.typesafe.ai/keys).
4. On CCv2, make sure the environment can reach `api.typesafe.ai` over outbound HTTPS.

## Review moderation: measure, then go live

1. In Backoffice, run `jevReviewDryRunCronJob`. It takes up to `jev.review.batch.size` reviews
   (default 200), half approved and half rejected, starting with the newest. Reviews already judged
   in a dry run are skipped, so each run adds new ones.
2. The cronjob's log file (`CronJob.logFiles`, also in the server log) has one line per language,
   for example, from the German run above:

   ```
   Jev review moderation, dry run, language de: 20 judged, 19 decided by Jev, 1 left for a person;
   of Jev's decisions 19 match your moderators, 0 approved what they rejected, 0 rejected what they approved
   ```

3. For the full comparison table per language, run this in HAC (FlexibleSearch):

   ```sql
   SELECT {language}, {humanDecision}, {decision}, COUNT({pk}) FROM {JevJudgment}
   WHERE {useCase} = 'reviewModeration' AND {dryRun} = 1
   GROUP BY {language}, {humanDecision}, {decision} ORDER BY {language}, {humanDecision}, {decision}
   ```

4. Adjust the `jev.review.*` thresholds until Jev's own decisions nearly always match your
   moderators'. Watch the "approved what they rejected" count most closely.
5. To go live, add a trigger to `jevReviewModerationCronJob`, for example every 5 minutes. It picks up
   new reviews that are still pending and haven't been judged yet.

## Category suggestions: configure and measure

1. Point it at your tree: `jev.category.catalog` (for example `electronicsProductCatalog`),
   `jev.category.catalogVersion` (default `Staged`) and `jev.category.roots` (one or more root
   category codes). Classification classes and variant categories are left out, and variant
   products are skipped, since they inherit their base product's categories. One Jev question can
   list at most 255 options, so a category with more than 254 direct subcategories stops the job
   with an error: split that level. Suggestions always end on a leaf category (or `none`). If your
   products usually sit in intermediate categories, the dry run counts those as "a parent or
   child of it".
2. Run `jevCategoryDryRunCronJob`. It suggests a category for products that already have one in
   the tree and compares. Texts and category names are read in the cronjob's **session
   language**, so set that to each storefront language in turn. The log line, here with the English
   numbers above:

   ```
   Jev category suggestions, dry run, language en: 40 judged, 39 suggested by Jev, 1 left for a merchandiser;
   of Jev's suggestions 33 match the product's category, 1 are a neighbouring category (same parent),
   0 are a parent or child of it, 5 are further off
   ```

3. Run `jevCategorySuggestionCronJob` for products that have no category in the tree yet, in one
   session language (each language is judged separately, so a second language would suggest
   again for every product). Each
   suggestion is a `JevJudgment` whose `decision` is the suggested category code, or `pending` when
   the score is below `jev.category.min.score`, or `none` when the product fits nowhere in the
   tree. Its `answers` hold the full path, the score, the other paths kept and Jev's top answers
   at every level. A merchandiser works through them in Backoffice (**Jev > Jev judgment**,
   filtered by use case) and assigns the categories they agree with.

## Attribute suggestions: configure and measure

1. Point it at your products and classification system: `jev.attribute.catalog` (with
   `jev.attribute.catalogVersion`, default `Staged`) and `jev.attribute.system` (with
   `jev.attribute.systemVersion`, default `1.0`), for example `ElectronicsClassification`.
2. Run `jevAttributeDryRunCronJob`. For every enum attribute that already has a value, Jev picks a
   value without seeing it, and the log compares:

   ```
   Jev attribute suggestions, dry run, language en: 40 products, 21 attributes judged, 15 suggested by Jev,
   6 left for a merchandiser (4 not stated in the text); of Jev's suggestions 15 match the product's value, 0 differ
   ```

   Texts and value names are read in the cronjob's session language; set it to each storefront
   language in turn.
3. Run `jevAttributeSuggestionCronJob`, in one session language, for the enum attributes without a
   value. Each product gets one `JevJudgment` whose `decision` summarises it (for example "2
   suggested, 1 unsure, 3 not stated") and whose `answers` list every attribute with Jev's value,
   confidence and top alternatives. A product with nothing to judge gets a `-` record, so it isn't
   fetched again.

Products are found through their classification classes, whether a class is assigned to the
product directly or, as usual, to a category above it.

Limits: only enum attributes with at most 254 allowed values (one Jev question lists at most 255
options); multi-valued attributes get a single suggestion; the log names every attribute type it
skipped. `jev.attribute.min.confidence` is Jev's confidence in its choice, a different number from
the category path score.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `jev.api.key` | empty | TypeSafe API key. Empty = no calls. |
| `jev.model` | `jev-1.13.0` | Pin the version your thresholds were tuned on. `jev-latest` moves when TypeSafe ships. |
| `jev.timeout.ms`, `jev.max.attempts` | `2000`, `3` | Per request. 408, 429 and 5xx are retried with backoff. |
| `jev.review.reject.at` | `0.85` | Abusive or spam at or above this: rejected. |
| `jev.review.approve.below` | `0.15` | Every risk below this, and on topic: approved. |
| `jev.review.personaldata.at` | `0.5` | Personal data at or above this: pending, so a person can edit it. |
| `jev.review.ontopic.at` | `0.5` | Needed to approve. |
| `jev.review.batch.size` | `200` | Reviews per run. Calls are sequential, about 3 per second. |
| `jev.category.catalog`, `jev.category.catalogVersion` | empty, `Staged` | The catalog version with the tree and the products. |
| `jev.category.roots` | empty | Root category codes, comma-separated. |
| `jev.category.min.score` | `0.7` | Path score (geometric mean of Jev's probabilities along the path) needed to suggest. |
| `jev.category.beam.width` | `3` | Paths kept per level. 1 = greedy search, fewer tokens, no second chances. |
| `jev.category.batch.size` | `100` | Products per run. Each takes one request per level of the tree. |
| `jev.attribute.catalog`, `jev.attribute.catalogVersion` | empty, `Staged` | The catalog version with the products. |
| `jev.attribute.system`, `jev.attribute.systemVersion` | empty, `1.0` | The classification system whose enum attributes are filled in. |
| `jev.attribute.min.confidence` | `0.7` | Jev's confidence in the chosen value needed to suggest it. |
| `jev.attribute.batch.size` | `100` | Products per run. Each takes one request per 25 attributes. |

The thresholds are starting guesses. Use the dry runs to set them for your shop.

## What leaves your platform

- **Reviews:** the product name, the review headline and the comment. Not the author, email,
  customer id or rating.
- **Categories and attributes:** the product name, its description with HTML removed (first
  2,000 characters), and the category, attribute and value names being chosen between.

The questions tell Jev to treat this text as data, even when it looks like an instruction.
TypeSafe is a US API. Before going live, settle the data protection position (GDPR, Swiss DSG, a
DPA with TypeSafe) with whoever owns your data. According to TypeSafe, it doesn't train on
requests, and zero data retention is available on enterprise plans.

## Recipes: other decisions to build on the extension

These depend on each shop's data model, so they are not built in. Each one reuses `JevClient` and
`JevJudgment` from your own extension (`<requires-extension name="jevintegration"/>`). A coding
agent with this repo's skill installed knows the pattern.

- **Customer ticket routing.** A cronjob over new tickets from SAP's ticket system without a
  `JevJudgment`. One Choice over your teams or ticket categories, plus "none". Assign only when
  confident; otherwise leave the ticket for triage. Send the subject and the first message, not
  the customer's details.
- **Order-note triage** (delivery instructions, gift messages, B2B order comments). A business
  process action (`AbstractSimpleDecisionAction`) with yes/no questions such as "asks to change the
  delivery" or "is a complaint". Take the manual-review transition on yes, and also when Jev gives
  no answer.
- **Search intent.** In the search facade, before the query: one Choice such as product search /
  order status / returns / contact. This one is synchronous, so use about a 1 s timeout and a
  single attempt, and fall back to the normal search. To limit calls, ask only when a search
  returns nothing.
- **Product content quality.** A cronjob over products in the Staged catalog. One Score with 3 to 5
  concrete levels, from "missing basic facts" to "complete and specific", recorded as a worklist
  for the content team. Don't block approval automatically.

The pattern for any new one is the review package: a questions class (state, questions, pure
`decide()`), a job that selects items with no `JevJudgment` for its use case and records one per
item, a dry run against decisions people already made, and thresholds in `project.properties`.
Keep Jev calls out of interceptors: they run inside every save, including ImpEx and catalog sync.

## Development

```bash
# from hybris/bin/platform, after adding the extension to localextensions.xml
ant all && ant yunitinit
ant alltests -Dtestclasses.extensions=jevintegration
```

The tests use a local fake Jev endpoint, so they need no network or API key. They cover:
- **the client:** retries, giving up, a missing answer, no key;
- **review moderation:** the policy, the dry run and live job against the junit tenant, a Jev
  outage, and read-only judgments;
- **category suggestions:**
  - the beam search, including a case where it finds the right leaf and greedy search does not;
  - the dry run and suggest job against the junit tenant, and variant products being skipped;
  - missing or wrong configuration ending as an error;
- **attribute suggestions:** the questions and policy, the dry run and suggest job against a real
  classification system in the junit tenant (empty attributes found, number attributes skipped,
  features never changed), and wrong configuration;
- **the essential data ImpEx**, with all six cronjobs started through SAP's cronjob service;
- **the review FlexibleSearch query** above.

## Evaluation data

[`eval/`](eval) has everything needed to repeat the measurements in a sandbox:
- `synthetic-reviews.impex` (80 reviews) and `review-results-jev-1.13.0.tsv`;
- `hardware-categories.impex` (1,122 categories in four languages) and `synthetic-products.impex`
  (40 products), plus `category-results-jev-1.13.0.tsv`;
- `hardware-attributes.impex` (a classification system with three attributes and 68 values in four
  languages), `hardware-attribute-values.impex` (the 21 labelled values), plus
  `attribute-results-jev-1.13.0.tsv`.

The category, attribute and value names come from Shopify's Product Taxonomy under the MIT
License; the notice is in `hardware-categories.impex`.

## Also in this repo

`skills/jev-sap-commerce/` is a Claude Code skill for building on this extension: where in SAP
Commerce to call Jev, and which decisions to keep in code. It checks whether a project already has
`jevintegration`, and if not, offers to install it from this repository. Install the skill with
`npx skills add Emenowicz/jev-sap-commerce`, or copy `skills/jev-sap-commerce/` to
`~/.claude/skills/`. It pairs with TypeSafe's own skill (`claude plugin marketplace add typesafe-ai/skills`).

## Licence

[Apache-2.0](LICENSE). The evaluation's category, attribute and value names are Shopify's, under the
MIT License.

## Not yet

- Applying category or attribute suggestions automatically, even when confident.
- Numeric attributes (size, voltage, weight). Jev reads numbers poorly; extract candidates in code
  and let Jev pick one, if needed.
- A Backoffice button to judge a single item on demand.
- Re-scoring stored answers with new thresholds without calling Jev again. The raw answers are
  already saved in `JevJudgment.answers`.
