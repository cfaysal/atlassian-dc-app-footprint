# OP-962 Decommission and archive parity design

## Purpose

The Jira and Confluence reports expose the same guarded decommission-candidate
concept. A candidate is an app included by the current report options, not
system-provided, and classified from complete evidence as
`NO_DETECTABLE_FOOTPRINT`. The list is a review starting point, never an uninstall
recommendation.

Jira additionally separates active and archived Space and Work Item evidence so
archived-only dependencies are visible as `LEGACY_ONLY` instead of being mixed into
current impact or hidden behind a candidate label.

## Candidate contract

- `includeDisabled` controls whether disabled apps enter the report population. If
  included, they remain eligible and carry the existing `DISABLED` badge.
- System-provided apps are never candidates.
- `LEGACY_ONLY`, `REVIEW_REQUIRED`, and `NOT_SCANNED` are never candidates.
- `NO_DETECTABLE_FOOTPRINT` requires complete measurement. A budget stop, failed
  inventory, unresolved archive split, or disabled required scan yields
  `REVIEW_REQUIRED`.
- Jira copy says "Included in this report" rather than incorrectly claiming every
  candidate is enabled.

Confluence builds its candidate list from the already existing impact assessment
and renders the same guarded notice used by Jira. JSON and page-export summaries
carry the candidate count in both products.

## Jira archive model

Jira gains `includeArchived`, defaulting to `true` like Confluence. The HTML control,
JSON options, CSV evidence, and page export all expose the selected value.

The project inventory is split with the public Jira APIs:

- `ProjectManager.getProjectObjects()` supplies active Spaces.
- `ProjectManager.getArchivedProjects()` supplies archived Spaces.

Workflow and screen reach project keys are partitioned against those two complete
inventories. Unknown project keys make that reach partial; they are not silently
assigned to either side.

Work Item totals are split by counting each archived Space with
`IssueManager.getIssueCountForProject(projectId)` and subtracting the complete
archived total from `IssueManager.getIssueCount()`. A negative or incomplete split
is invalid and makes the Work Item denominator unavailable.

For app-owned custom-field values, total associations continue to come from
`CustomField.getIssuesWithValue()`. When archived evidence is requested, archived
issue IDs are streamed per archived Space with
`IssueManager.getIssueIdsForProject(projectId)`, loaded in bounded batches, and
tested with `CustomField.getValue(issue)`. The existing `issueBudgetMs` bounds this
work. A stopped or failed scan marks the active/archive association split
incomplete and prevents a zero-impact conclusion.

## Classification

Current impact uses only active evidence:

- active Work Item associations divided by active Work Items;
- active reached Work Items divided by active Work Items;
- reached active Spaces divided by all active Spaces;
- app custom-field share divided by all custom fields;
- referenced workflows reaching active Spaces divided by all workflows reaching
  active Spaces.

After current impact is measured:

1. A positive current dimension keeps its relative Critical/High/Medium/Low level.
2. A complete current zero with positive archived reach or archived Work Item
   evidence becomes `LEGACY_ONLY`.
3. An incomplete active/archive split becomes `REVIEW_REQUIRED`.
4. Only a complete zero across both current and archived evidence becomes
   `NO_DETECTABLE_FOOTPRINT`.

Archived evidence is reported as counts and reasons but never raises the current
percentage level.

## Alternatives rejected

- **UI-only parity:** adding only the Confluence box leaves Jira's mixed
  active/archive measurements unexplained.
- **Reach-only archive detection:** splitting workflow and screen assignments but
  not archived custom-field values can incorrectly classify archive-only data as
  current or absent.
- **Unbounded archived issue scan:** accurate but unsafe on large instances. The
  existing time budget and explicit incomplete state are required.

## Verification

Tests first establish RED for candidate eligibility, disabled-app inclusion,
Confluence rendering, active/archive partitioning, archive-only `LEGACY_ONLY`, and
fail-closed budget/error behavior. Both full offline suites and whole-file parse
checks must pass. The exact resulting scripts require successful Jira and
Confluence runtime smoke tests before merge or push.
