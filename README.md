# Atlassian Data Center App Footprint

Two read-only ScriptRunner REST endpoints that answer one question about a Data Center
instance: **how much of this instance would actually break if a given app were removed?**

The Marketplace tells you which apps are installed. The UPM tells you which are enabled.
Neither tells you that app X owns 41 custom fields on 312 000 issues and is wired into
17 workflows, while app Y has been installed for four years and is referenced nowhere.
These scripts measure that difference.

| Script | Platform | Version |
| --- | --- | --- |
| [`endpoints/jiraDCappFootprint.groovy`](endpoints/jiraDCappFootprint.groovy) | Jira Data Center | 3.1 |
| [`endpoints/confluenceDCappFootprint.groovy`](endpoints/confluenceDCappFootprint.groovy) | Confluence Data Center | 4.3 |

Typical uses: app consolidation before a licence renewal, scoping a Cloud migration,
building the removal-risk section of an audit report, or justifying to a budget owner
why a rarely-used app is or is not expensive to drop.

## Properties

Both endpoints share the same discipline, and it is the reason the output is worth
trusting:

- **Read-only.** No write path exists. No configuration is created, changed or deleted.
- **No outbound network call.** Nothing is sent anywhere. The report is a self-contained
  artifact produced inside your instance.
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

1. Go to **Administration > ScriptRunner > REST Endpoints**.
2. Choose **Custom endpoint** and paste the entire file contents.
3. Save. ScriptRunner registers the endpoint under the name `appFootprint`.

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
| `issueCounts` | `true`, `false` | `true` | Count issues per app-provided custom field. |
| `issueBudgetMs` | milliseconds | `120000` | Time budget for issue counting. `0` means unlimited. Fields beyond the budget are reported as NOT MEASURED, never as zero. |
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

## Counting semantics

Read this before quoting a number to a customer.

**Jira** measures configuration reach: extension modules per app, app-provided custom
fields and the number of issues carrying a value in them, screen and screen-scheme
placements, and workflow references including post-functions, conditions and validators.
Issue counting is the expensive part and is the reason `issueBudgetMs` exists.

**Confluence** measures content reach: extension modules per app, and actual macro usage
counted from the search index. Two rules govern how that number is reported:

- **Current and archived spaces are never mixed.** A macro used 4 000 times in archived
  spaces and never in a live space is a very different removal risk from the reverse, and
  a single blended total would hide exactly that.
- **Native User Macros are reported as Confluence configuration, not as Marketplace app
  content.** They are something your own administrators wrote. Attributing them to an app
  would inflate that app's footprint with work it never did.

Partial results are marked with an asterisk and are lower bounds, not estimates.

## Performance

Both endpoints run against production-sized instances, but they are not free. The Jira
issue count and the Confluence usage scan are the two costly operations, and both are
capped by a time budget that defaults to two minutes. Raise it with `issueBudgetMs` or
`scanBudgetMs` when you need completeness, set it to `0` only on an instance where a long
running read is acceptable, and prefer a maintenance window for the unlimited run.

Anything the budget cuts off is reported as not measured. The report never trades honesty
for a full-looking table.

## Tests

The Jira script ships a test suite that runs offline, without a Jira instance. See
[`tests/README.md`](tests/README.md) for what it covers and how to run it. The
Confluence suite does not exist yet.

Every push and pull request also runs a parse check over both endpoints and two hygiene
gates: one that scans for credentials and internal references, and one that asserts no
outbound network call was introduced. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Status

Both scripts are in active development and the interface may still change. The counting
discipline described above will not: read-only, no outbound call, and never a failed read
rendered as a measured zero.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Atlassian or Adaptavist. ScriptRunner is a product of
Adaptavist. Jira and Confluence are trademarks of Atlassian.
