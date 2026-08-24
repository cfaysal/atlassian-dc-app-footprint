# Atlassian Data Center App Footprint

Two ScriptRunner REST endpoints that answer one question about a Data Center
instance: **how much of this instance would actually break if a given app were removed?**

The Marketplace tells you which apps are installed. The UPM tells you which are enabled.
Neither tells you that app X owns 41 custom fields on 312 000 issues and is wired into
17 workflows, while app Y has been installed for four years and is referenced nowhere.
These scripts measure that difference.

| Script | Platform | Version |
| --- | --- | --- |
| [`jira/jiraDCappFootprint.groovy`](jira/jiraDCappFootprint.groovy) | Jira Data Center | 3.3 |
| [`confluence/confluenceDCappFootprint.groovy`](confluence/confluenceDCappFootprint.groovy) | Confluence Data Center | 4.7 |

Typical uses: app consolidation before a licence renewal, scoping a Cloud migration,
building the removal-risk section of an audit report, or justifying to a budget owner
why a rarely-used app is or is not expensive to drop.

How this sits next to the Jira and Confluence Cloud Migration Assistants, and next to
App Usage for Jira, is described under
[Relation to the Atlassian migration tools](#relation-to-the-atlassian-migration-tools).

## Properties

Both endpoints share the same discipline, and it is the reason the output is worth
trusting:

- **Read-only by default.** The analysis never writes. No configuration is created,
  changed or deleted. The single exception is the Confluence page export, offered by
  both endpoints and run only when an administrator explicitly requests it: one page,
  in a space they choose, protected by a marker so this export can never overwrite a
  page it did not create.
- **No outbound network call during analysis.** Producing a report contacts nothing
  outside your instance, in either endpoint and in every format. The report is a
  self-contained artifact.
  The one exception is the Jira endpoint's page export. Confluence is a separate
  instance from Jira, so writing a page there necessarily leaves Jira. That call goes
  only to the Confluence instance you select from your configured application links,
  only after you open the export and choose a target, and only to look up spaces and
  pages and write the one page you asked for. Opening the report makes no such call.
  The Confluence endpoint writes through the local Confluence API and makes no network
  call at all.
- **Admin-gated.** Restricted to `jira-administrators` and `confluence-administrators`
  respectively, enforced by ScriptRunner, not by the script.
- **A failed read is never rendered as a measured zero.** If a count could not be taken,
  the report says so at that exact cell. Suppressed errors are recorded per item and
  surfaced. An empty result and a broken result never look alike.
- **A skipped or budgeted scan is marked `n/m` (not measured).** Time budgets keep the
  endpoint from hanging a production instance, and anything the budget cut off is
  labelled, never silently reported as nothing.

## Requirements

- Adaptavist ScriptRunner, licensed and installed.
- An administrator account in the respective group.
- Jira Data Center, or Confluence Data Center 10 for the Confluence script.

The Jira script is **javax / jakarta neutral**: the JAX-RS `Response` class is resolved at
runtime and the closure parameter is untyped, so the file carries no `javax.*` or
`jakarta.*` import and runs unedited on either line. This matters because the namespace a
ScriptRunner script needs follows the **ScriptRunner** version, not the Jira version:
ScriptRunner 10.x and above use `jakarta.ws.rs.*`, versions 8.x to 9.x use `javax.ws.rs.*`.

The Confluence script targets Confluence 10 with ScriptRunner 10 or newer
(`jakarta.ws.rs`).

## Installation

Install the script as a **file in your script root**, not as an inline script.

1. Put the `.groovy` file into your ScriptRunner script root.
2. Go to **Administration > ScriptRunner > REST Endpoints**.
3. Choose **Custom endpoint**, switch it from inline to **File**, and point it at the
   file you just placed.
4. Save. ScriptRunner registers the endpoint under the name `appFootprint`.

Pasting the code inline works for small scripts and fails for these. ScriptRunner stores
an inline script as a serialised configuration property, and that property is capped:
saving a large one is refused with

```
Serialized value cannot be longer than 99,000 characters
```

The refusal happens while saving the endpoint configuration, before Groovy is compiled or
run, so it is not a Groovy or a Confluence limit. The cap counts the serialised value
rather than the characters you see in the editor, and escaping adds to it, so a script
somewhat below the number can already be rejected. Both endpoints in this repository are
well past it.

A file in the script root has no such cap: the endpoint stores only the reference. It is
also the better home for a script this size, because it can be versioned and diffed
instead of living in a text box.

Call it as an administrator:

```
https://<your-instance>/rest/scriptrunner/latest/custom/appFootprint
```

The HTML report is self-contained and can be saved to disk or printed to PDF as-is.

## Parameters

All parameters are optional and are appended as query parameters.

### Jira

| Parameter | Values | Default | Effect |
| --- | --- | --- | --- |
| `format` | `html`, `json`, `csv` | `html` | Output format. |
| `includeSystem` | `true`, `false` | `false` | Include system-provided apps. |
| `includeDisabled` | `true`, `false` | `true` | Include installed but disabled apps. |
| `includeDrafts` | `true`, `false` | `false` | Include draft workflows in the scan. |
| `includeModules` | `true`, `false` | `false` | Emit the full module list per app. |
| `includeArchived` | `true`, `false` | `true` | Measure archived Spaces and Work Items separately from current impact. |
| `includeReach` | `true`, `false` | `true` | Measure the Spaces and Work Items reached through workflows and screens. |
| `issueCounts` | `true`, `false` | `true` | Count Work Items per app-provided custom field. |
| `issueBudgetMs` | milliseconds | `120000` | Time budget for Work Item counting, including the archived value split. `0` means unlimited. Fields beyond the budget are reported as NOT MEASURED, never as zero. |
| `numbers` | `de`, `en` | `de` | Thousands separator style. |

### Confluence

| Parameter | Values | Default | Effect |
| --- | --- | --- | --- |
| `format` | `html`, `json`, `csv` | `html` | Output format. |
| `level` | `app`, `macro`, `module` | `app` | CSV granularity. CSV only. |
| `includeSystem` | `true`, `false` | `false` | Include system-provided apps. |
| `includeDisabled` | `true`, `false` | `true` | Include installed but disabled apps. |
| `includeArchived` | `true`, `false` | `true` | Include archived spaces as a separate dimension. |
| `includeModules` | `true`, `false` | `false` | Emit module detail in HTML and JSON. |
| `scanUsage` | `true`, `false` | `true` | Measure actual macro usage, not just declared macros. |
| `scanAliases` | `true`, `false` | `false` | Also resolve macro aliases. |
| `scanBudgetMs` | milliseconds | `120000` | Time budget for the usage scan. `0` means unlimited. |
| `appKey` | plugin key | none | Restrict the report to a single app. |
| `numbers` | `de`, `en` | `de` | Thousands separator style. |

## Export to Confluence

Both reports can write their executive summary into Confluence as a page. This is the only
write either script performs, and it happens only when an administrator asks for it in the
report.

Nothing is looked up until the export is opened. Rendering a report reads no application
links, no spaces and no pages. Open the export, and the Jira endpoint lists the Confluence
application links you have configured; the Confluence endpoint writes into its own
instance and needs no link at all. Then pick a space, optionally name a parent page, give
the page a title, and submit. A repeat run updates the same page instead of creating a
second one, and the answer carries a link to it.

The parent page field searches while you type and the result list stays until you pick an
entry or clear the field. If no page matches, the field says so and the parent is created
during generation. There is no separate button for that: a title with no page picked is
the instruction. Immediately before creating, the endpoint checks the exact title in that
space once more and adopts an existing page rather than creating a second one with the
same title. If the parent cannot be created, the run stops and says so; the report is
never filed at the top level as a consolation prize.

A parent named in a run is an instruction, so it is applied to a page that already exists
as well. Name no parent and the page keeps the position an administrator gave it. After
writing, the endpoint reads the page back and compares its innermost ancestor with the
parent you asked for. It reports the measurement rather than the call: the page was moved,
it was not moved, or the position could not be read back at all. A failed read is never
rendered as a successful move.

The generated page carries a **Decision** column. Write `KEEP`, `REMOVE` or whatever note
fits, and it survives every later run. Three rules protect it:

- The decision cell is carried over verbatim, including your own wording and markup, not
  just the keyword.
- Columns are located by their header name, never by position, so an added column does not
  orphan your notes.
- If the existing page cannot be read or parsed, nothing is written at all. A failed read
  never becomes an empty Decision column, and the export reports the failure instead of
  quietly succeeding.

A generated page carries a marker. A page with a colliding title that does not carry it is
treated as a failed read, so this export cannot overwrite a page it did not create.

If an app disappears between two runs, its note has nowhere to go. Rather than dropping it
silently, the page lists it under the decisions without a matching app and says how many
were carried over.

## Counting semantics

Read this before quoting a number to a customer.

**Jira** measures configuration reach: extension modules per app, app-provided custom
fields and the number of Work Items carrying a value in them, screen and screen-scheme
placements, and workflow references including post-functions, conditions and validators.
Active and archived Spaces and Work Items are measured separately. Archived-only evidence
is `LEGACY_ONLY`, while an omitted or incomplete archive split is `REVIEW_REQUIRED` for an
otherwise empty current footprint. Work Item counting is the expensive part and is the
reason `issueBudgetMs` exists.

**Confluence** measures content reach: extension modules per app, and actual macro usage
counted from the search index. Two rules govern how that number is reported:

- **Current and archived spaces are never mixed.** A macro used 4 000 times in archived
  spaces and never in a live space is a very different removal risk from the reverse, and
  a single blended total would hide exactly that.
- **Native User Macros are reported as Confluence configuration, not as Marketplace app
  content.** They are something your own administrators wrote. Attributing them to an app
  would inflate that app's footprint with work it never did.

Partial results are marked with an asterisk and are lower bounds, not estimates.

## Instance-relative impact

Both reports classify impact against the size of the instance they are scanning. Raw
counts remain visible, but no absolute count makes an app Critical, High or Medium. Each
available product-specific dimension is divided by its instance-wide denominator, and the
highest resulting share determines the app's level:

| Highest measured share | Impact |
| ---: | --- |
| at least 50% | Critical |
| at least 20% | High |
| at least 5% | Medium |
| greater than 0% | Low |

Jira evaluates active Work Item-field associations and reached Work Items against all
active Work Items, reached active Spaces against all active Spaces, app-owned custom fields
against all custom fields, and referenced workflows reaching active Spaces against the
instance-wide active workflow reach. Confluence evaluates
current unique content and current macro associations against all current pages and blog
posts, plus current Space reach against all current Spaces. Archived evidence in both
products is kept separate as `LEGACY_ONLY` and never raises the current impact level.

The level is the maximum of the dimensions, not an average. Association ratios are capped
at 100% for display because several associations can belong to one object. A partial
positive measurement may raise the level and is labelled as a lower bound; it can never
lower a known level. A partial zero becomes `REVIEW_REQUIRED`, while
`NO_DETECTABLE_FOOTPRINT` is reserved for a complete measured zero. HTML, JSON, CSV and the
Confluence page export carry the resulting level and evidence. The Confluence HTML report
also names the instance, Base URL, product version/build and active scan options in the
same way as the Jira report.

### Decommission candidates

Both reports use the same fail-closed candidate rule. An app is listed only when it is in
the selected report population, is not system-provided, and complete current and archived
evidence classifies it as `NO_DETECTABLE_FOOTPRINT`. Disabled apps are eligible only when
`includeDisabled=true` includes them in the report. `LEGACY_ONLY`, `REVIEW_REQUIRED`, and
unmeasured results are never candidates. The list is a starting point for review, not an
automatic uninstall recommendation.

## Performance

Both endpoints run against production-sized instances, but they are not free. The Jira
issue count and the Confluence usage scan are the two costly operations, and both are
capped by a time budget that defaults to two minutes. Raise it with `issueBudgetMs` or
`scanBudgetMs` when you need completeness, set it to `0` only on an instance where a long
running read is acceptable, and prefer a maintenance window for the unlimited run.

Anything the budget cuts off is reported as not measured. The report never trades honesty
for a full-looking table.

## Tests

Both scripts ship a test suite that runs offline, without a running instance. See
[`jira/tests/README.md`](jira/tests/README.md) and
[`confluence/tests/README.md`](confluence/tests/README.md) for what each covers and how to
run it.

The Confluence suite carries a control implementation of the discarded decision parser and
asserts on every run that the real parser refuses malformed input the control accepts. A
suite that has never been red proves nothing, so the discriminating power is measured
rather than assumed.

Every push and pull request runs both suites, a parse check over both endpoints, and two
hygiene gates: one that scans for credentials and internal references, and one that asserts
no outbound network call was introduced. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Relation to the Atlassian migration tools

Atlassian ships its own assessment tooling. The honest answer to "why not just use that" is
that those tools answer a neighbouring question, with one real overlap.

The **Jira Cloud Migration Assistant** lists installed apps with cloud availability, a
migration path and a decision. It does not measure configuration. Its only usage column is a
set of links into a separate plugin, and the documentation says so plainly: "App usage:
Displays links to app's usage information in the App usage for Jira plug-in. This column only
appears if App Usage for Jira is installed and enabled."

The **Confluence Cloud Migration Assistant** does measure macro usage, inside a fixed window:
"Appears on: This column shows you how many pages the macros of a given app appears on in the
last 30 days", with a second column counting the views over the same window. A macro sitting
on a page nobody has opened in thirty-one days does not appear there.

**App Usage** is part of Data Center administration itself, and it is the real overlap. In
Jira it presents tabs for common usage data, user interactions, custom fields, workflows and
dashboards, including the number of issues that have a value for an app-provided field and the
conditions, validators and post functions an app contributes per workflow transition. In
Confluence the equivalent view is documented as "Available in: Confluence Data Center 10.2.11
and 9.2.20, and later patch releases" and reports a macro Page count, "The number of unique
pages in the site that contain this macro", alongside Active Objects tables and REST activity.

Read its own caveats before treating it as a verdict. Jira's App Usage opens with the banner
"App usage data is indicative, so make sure you investigate it further before making any
decision about this app." On custom fields it states that "an app can use many custom fields
within Jira, but this is not tracked: this table shows only the custom field types that will
most likely stop working if you remove or disable an app." On REST activity: "tracking only
begins after you've installed and enabled App Usage. It therefore can't display data about any
API calls from the days, weeks, and months before App Usage was enabled." The table view is
limited to tables an app declared through Active Objects.

Said plainly: on a current Data Center, App Usage already answers a good part of what these
endpoints report, and it answers questions about runtime activity that they deliberately do
not ask.

What these endpoints add on top:

- **Nothing depends on when tracking was switched on.** The measurement reads the current
  configuration and the content index at the moment of the run. There is no collection period
  to have missed, and no thirty day window that decides whether a macro counts.
- **Screen and screen scheme placements per app-provided field**, which the App Usage tabs do
  not cover.
- **Archived spaces are their own dimension** in Confluence, never blended into the current
  totals and never silently dropped. The documented App Usage column is a single site-wide
  page count.
- **A read that failed or ran out of budget is labelled NOT MEASURED** at the exact cell. An
  empty result and a broken result never look alike.
- **One self-contained artifact for both products**, in HTML, JSON and CSV, readable by
  someone who has no access to the instance.
- **The Decision column lives in a Confluence page**, which outlives the Data Center instance
  the decision is about.

What they do not do: they do not measure clicks, views or runtime user activity. An app with
no detectable configuration footprint can still be in daily use through a UI, a REST client or
a scheduled job. The reports say so at the top of every run, and this is the same statement.

## Status

Both scripts are in active development and the interface may still change. The counting
discipline described above will not: no outbound call, no write the administrator did not
ask for, and never a failed read rendered as a measured zero.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Atlassian or Adaptavist. ScriptRunner is a product of
Adaptavist. Jira and Confluence are trademarks of Atlassian.
