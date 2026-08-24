# OP-961 Instance-aware impact design

## Purpose

The Jira and Confluence footprint reports classify decommissioning and migration
impact against the size of the instance being scanned. Absolute workload thresholds
are removed. Both reports use the same percentage bands and state semantics while
retaining product-specific dimensions.

The Confluence HTML report also gains the visible instance identity block already
present in Jira, including site title, Base URL, product version, build, and scan
options.

## Classification model

Each available dimension is converted to a percentage of its instance-wide
denominator. The overall impact is the highest dimension level, not an average or
weighted sum. Ratios used for classification are capped at 100 percent because
association counts may legitimately exceed their object denominator.

| Share | Level | Rank |
| ---: | --- | ---: |
| at least 50 percent | Critical | 7 |
| at least 20 percent | High | 6 |
| at least 5 percent | Medium | 5 |
| greater than zero | Low | 4 |

The reason list names every dimension that reaches the selected level and includes
its numerator, denominator, and percentage. Raw counts remain visible as evidence
but do not introduce absolute classification thresholds.

## Jira dimensions

- Issue-field association density: measured issue-field associations divided by all
  work items in the instance.
- Reached work items: work items in the union of reached spaces divided by all work
  items.
- Space reach: reached active spaces divided by all active spaces.
- Custom-field share: app-owned custom fields divided by all custom fields.
- Active-workflow share: active workflows referencing the app divided by all active
  workflows included by the scan.

Screen placements and individual workflow-reference counts remain explanatory raw
metrics. Their migration reach is represented by the reached-space and reached-work-
item dimensions, avoiding invented denominators.

## Confluence dimensions

- Current content reach: unique current macro-bearing content divided by the current
  pages and blog posts in the instance.
- Current macro-association density: current macro-content associations divided by
  the current pages and blog posts in the instance.
- Current space reach: current spaces containing app macros divided by all current
  spaces.

Archived content does not raise the current impact level. An app with archived but
no current macro footprint remains `LEGACY_ONLY`.

## Measurement state

- A complete positive dimension is classified normally.
- A partial positive value is a lower bound. It may raise the reported level but
  never justify lowering it; its reason is marked as a lower bound.
- An incomplete scan without positive measured evidence is `REVIEW_REQUIRED`, never
  `NO_DETECTABLE_FOOTPRINT`.
- `NO_DETECTABLE_FOOTPRINT` requires a complete measured zero across relevant usage
  dimensions.
- A disabled usage scan remains `NOT_SCANNED`.
- A zero or unreadable denominator makes only that dimension unavailable. It does
  not silently become a zero-percent measurement.

## Output parity

Both HTML reports expose the same impact badges, summary counters, rank-first sort,
dimension percentages, and reasons. JSON and CSV exports carry the level, label,
rank, partial marker, reasons, and dimension evidence.

The Confluence HTML report renders a Jira-style instance block immediately below
the page header with Instance, Base URL, Confluence version/build, and active scan
options. Existing page-export instance data is reused.

## Verification

Automated tests cover exact boundaries, tiny and large instances, max-of-dimensions,
ratio capping, missing denominators, partial lower bounds, zero-footprint states,
legacy-only behavior, export fields, sorting, and the Confluence Base URL block.
Both Groovy suites must pass locally. The final scripts are then checked in the
existing plugin-dev ScriptRunner environment without building a JAR.
