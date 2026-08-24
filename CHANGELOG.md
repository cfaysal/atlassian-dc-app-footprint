# Changelog

Notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The two scripts carry independent
version numbers in their file headers, because they ship independently, and those headers
are the authority. This file records what changed between them.

Both scripts are under active development and predate this repository. Their history up to
the first public commit is not reconstructed here; entries start from the first release
published in this repository.

## Unreleased

### Added

- **Confluence page export, in both endpoints.** The report writes its executive summary
  into a Confluence page. An administrator opens the export, picks a target, searches for a
  space, optionally names a parent page, and gives the page a title. A repeat run updates
  the same page rather than creating a second one, and the answer carries a link to it.
- The generated page carries a **Decision** column for `KEEP` / `REMOVE` notes, and keeping
  that column intact is the point of the feature. Cells are carried over verbatim including
  an administrator's own wording, columns are located by header name rather than by
  position, and nothing is written at all when the existing page cannot be read or parsed.
  A generated page carries a marker, and a title-colliding page without it is refused, so
  the export cannot overwrite a page it did not create. A decision whose app has since
  disappeared is listed rather than dropped silently.
- The export is staged and nothing is looked up until it is asked for. Rendering a report
  reads no application links and no spaces. The Jira endpoint lists the configured
  Confluence application links only when the export is opened, and loads spaces only after
  a target is chosen.
- **Jira instances with several Confluence application links** can pick the target. The
  primary is preselected; a single link is selected outright.
- The generated page names the instance the numbers came from, read from the instance
  itself rather than from the submitted payload.
- **Offline test suite for the Confluence endpoint.** It carries a control implementation
  of the discarded decision parser and asserts on every run that the real parser refuses
  malformed input the control accepts. CI runs both suites: 273 assertions for Jira, 461
  for Confluence.
- **Parent pages are searched while you type and created when they do not exist.** The
  result list stays until an entry is picked or the field is cleared, and a field with no
  match says the page will be created. There is no separate create button: a title with no
  page picked is the instruction, and the parent is created during generation. Immediately
  before creating, the exact title is checked in that space once more and an existing page
  is adopted rather than duplicated. A parent title equal to the report title is refused,
  because a duplicate title in the same space would leave an orphaned container behind.
- **The position of the report page is measured, not assumed.** A parent named in a run is
  applied to a page that already exists as well; naming no parent leaves the position an
  administrator gave the page untouched. After writing, the page is read back and its
  innermost ancestor compared with the requested parent. The answer distinguishes moved,
  not moved, and position not readable. A failed read is never rendered as a successful
  move.
- A cross-check between the module category heuristic and the macro enumeration. A
  divergence between the two is now reported instead of passing unnoticed.
- Detection of macro names occurring both as an app macro and as a native user macro. The
  possible double count is reported rather than resolved by guessing.

### Changed

- **Confluence page and space reads use persistence service locators.** Deprecated
  `PageManager.getPage(...)` and `SpaceManager.getSpace(...)` lookups were replaced with
  `PageService` and `SpaceService` locators. `PageManager` remains only for non-deprecated
  page writes and moves.
- **The README names the Atlassian tooling these endpoints sit next to.** A new section
  describes the Jira and Confluence Cloud Migration Assistants and the App Usage view that
  Data Center administration ships itself, quotes what their documentation actually promises,
  and states where these endpoints add something and where they do not. App Usage is named as
  the real overlap: on a current Data Center it already answers a good part of the same
  questions, and it answers runtime questions these endpoints deliberately do not ask. Its own
  caveats are quoted rather than paraphrased, including that its data is "indicative" and that
  REST tracking "only begins after you've installed and enabled App Usage".
- **Repository layout.** Each product now has its own directory, `jira/` and `confluence/`,
  each holding the endpoint and its `tests/`.
- The read-only wording in `README.md`, `SECURITY.md` and `CONTRIBUTING.md` now states the
  exceptions precisely. Producing a report still writes nothing and calls nothing outside
  the instance, in either endpoint and in every format. Writing a page from Jira does leave
  Jira, because Confluence is a separate instance there; that call goes over an application
  link the administrator configured and selected, and only after they open the export.
- The hygiene gate no longer claims more than it checks. It is named accordingly and now
  also looks for application link usage everywhere that usage is not declared. A raw HTTP
  grep alone would have reported green while an outbound call existed, because an
  application link call goes through `ApplicationLinkRequestFactory` and matches none of
  the HTTP patterns.
- Installation now recommends a script file in the script root rather than an inline
  script, with the reason and the exact error an inline paste produces.
- The default page title names its source: `JIRA App Footprint - Executive Summary` and
  `Confluence App Footprint - Executive Summary`.

### Fixed

- **Parent page search found nothing in personal spaces.** The Jira endpoint passed the
  space key through the same sanitiser as the free-text search term, which strips the
  tilde, so `~jsmith` became a key that does not exist. Confluence answered with zero
  results and no error, which is why the defect was invisible. A space key is an
  identifier, not a search term, and is now validated rather than cleaned.
- **The Confluence endpoint found parent pages only by their exact title.** Titles are now
  matched word by word through the content index, with a trailing wildcard on the last
  token only. Every hit is resolved through the page manager so the title and space shown
  come from the database rather than from the index, and hits in another space are dropped.
  The exact-title hit is kept and listed first.
- **A repeat run did not file the report under the parent page.** The parent was applied on
  the create path only, so a second run to the same title left the report where it was
  while still reporting the parent as present.
- **The measurement notes box was always coloured as a warning**, even when it reported
  that nothing had failed and nothing had been suppressed. In the Confluence endpoint the
  rule was defined twice and the second definition silently overrode the first. The colour
  now follows the content: a warning only for a budget-skipped scan, a suppressed read
  error, an unresolved type field or a truncated screen reach. Observations no longer
  colour the box.
- Read errors and deliberate observations are now counted and reported separately. A budget
  statement or a cross-check result is not a fault and is no longer presented as one.

- Macro enumeration tested `MacroModuleDescriptor`, which modern XHTML macro descriptors do
  not implement. An app could show eleven macro modules in its capability list and zero
  provided macros at the same time. The check now uses `MacroMetadataSource`, which the
  Confluence API documents as the shared interface for macro descriptors.
- The Confluence page write used `ContentService`, which is a Spring AOP proxy and fails
  inside a ScriptRunner REST endpoint with `SpringProxy ... is not visible from class
  loader`. It now writes through `PageManager`, the path Adaptavist's own documentation
  uses for this. Found on a live instance; the fail-closed gate reported it as a failure
  and wrote nothing, which is what it exists for.
- Listing Confluence application links by type returned an empty list rather than an error
  when the script and the applinks plugin resolved that type through different class
  loaders. An instance with a working link was told it had none. The typed lookup now falls
  back to an untyped scan whenever it finds nothing, and a refusal reports how many links
  were seen and of which types instead of ending the trail.
- Native user macros are read separately from the plugin loop and reported as instance
  configuration rather than as app content.
- The macro usage scan gained the time budget `scanBudgetMs`, matching `issueBudgetMs` on
  the Jira side. Macros beyond the budget are reported as NOT MEASURED, never as zero.

### Verification status

Both exports have been run against live Data Center instances: a page created, a repeat run
updating it to a new version, decisions surviving the update, and the Jira endpoint writing
across an application link into a separate Confluence instance.

Not exercised anywhere: whether a ScriptRunner custom REST endpoint sits behind the
Confluence XSRF filter. The report sends `X-Atlassian-Token: no-check` and the server checks
no header; the effective controls are the administrator group gate and the marker rule. This
is marked as unverified in the code.

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
