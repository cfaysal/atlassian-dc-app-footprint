# Changelog

Notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The two scripts carry independent
version numbers in their file headers, because they ship independently, and those headers
are the authority. This file records what changed between them.

Both scripts are under active development and predate this repository. Their history up to
the first public commit is not reconstructed here; entries start from the first release
published in this repository.

## Unreleased

Nothing since the first release.

## 2026-08-21

First public release.

### jiraDCappFootprint 3.1

Measures configuration reach in Jira Data Center.

- Extension module inventory per installed app.
- App-provided custom fields with issue counts, bounded by `issueBudgetMs`. Fields beyond
  the budget are reported as NOT MEASURED, never as zero.
- Screen and screen-scheme placements per field.
- Workflow references including post-functions, conditions and validators, with draft
  workflows available on request.
- HTML, JSON and CSV output.
- javax and jakarta neutral: no JAX-RS import, `Response` resolved at runtime, so one file
  runs unedited on ScriptRunner 8.x through 10.x and later.
- Offline test suite covering the product-independent helper classes.

### confluenceDCappFootprint 4.3

Measures content reach in Confluence Data Center.

- Extension module inventory per installed app.
- Macro usage measured from the search index, with current and archived spaces reported as
  separate dimensions and never blended.
- Native User Macros reported as Confluence configuration rather than as Marketplace app
  content.
- Alias resolution, single-app filtering by plugin key, and CSV at app, macro or module
  granularity.
- Usage scan bounded by `scanBudgetMs`. Skipped or budgeted measurements are marked `n/m`,
  and partial totals are marked as lower bounds.
- Targets Confluence 10 with ScriptRunner 10 or newer.

### Repository

- Apache-2.0 licence, documentation of the counting semantics, contribution rules and
  security policy.
- CI running a parse check over both endpoints, the offline Jira test suite, and two
  hygiene gates: one scanning for credentials and internal references, one asserting that
  no outbound network call has been introduced.
