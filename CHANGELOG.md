# Changelog

Notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The two scripts carry independent
version numbers in their file headers, because they ship independently, and those headers
are the authority. This file records what changed between them.

Both scripts are under active development and predate this repository. Their history up to
the first public commit is not reconstructed here; entries start from the first release
published in this repository.

## Unreleased

### Withdrawn

- **Confluence: `4.9` is withdrawn and its code taken back to `4.8` (`4.10`).** 4.9 rebuilt
  the whole database read path on dynamic typing, +524/-234 lines, against the hypothesis
  that `java.sql.*` was not visible to the ScriptRunner classloader. **That hypothesis is
  disproven.** The `500` came from a stale ScriptRunner endpoint registration: after deleting
  and re-creating the endpoint, the unchanged `4.8` loads and exports on the customer
  instance. 4.9 itself never ran on any instance, so what was on `main` was a large rewrite
  aimed at a cause that does not exist, sitting in front of the one build that is measured
  working. The endpoint source of `4.10` is the `4.8` source: the same `SELECT`, the same
  bound status, the same catalogue check, the same typed `Connection` signatures and the same
  hand-built JDK proxy for the SAL callback.
  4.9 stays in the history and in this file. A version that was committed does not disappear
  because it turned out to be unnecessary, and the next reader has to be able to find out why
  it existed and why it was taken back. Nothing here is a claim about `4.10` on an instance:
  what is measured is that its endpoint source matches the build that ran there, plus the
  additions listed below.

### Added

- **A probe that locates where ScriptRunner keeps its macro registry**
  (`confluence/tools/scriptRunnerRegistryProbe.groovy`, `0.1`). The macro name sources the
  report uses carry no macro body: verified against the Confluence javadoc, `MacroMetadata`
  exposes name, plugin key, aliases, categories, title, description, icon and hidden, and
  nothing returning a template or source. The body of a runtime-defined macro therefore sits
  in the owning app's own store, and where that store is on a running instance is not
  something an export format answers.
  Read on a customer instance from ScriptRunner's own registry export, structure only and no
  value opened: 17 macros, one JSON entry each, and a two-slot field
  `FIELD_SCRIPT_FILE_OR_SCRIPT`. Slot 0 held multi-line code for 11 of them, 1543 to 7187
  characters, never ending in `.groovy`; slot 1 held a path for the other 6, always ending in
  `.groovy` and never multi-line.
  The probe is admin-gated and strictly read-only, and it never prints a cell value: table
  names, column names, counts, and whether an administrator-supplied marker occurs in a given
  column. A probe that solved a locating problem by dumping the store would have created a
  worse one, because macro bodies and plugin configuration routinely carry credentials,
  internal addresses and SQL. Two controls come first, the executor and a non-empty
  catalogue, so an empty candidate list is never mistaken for evidence of absence.

- **Confluence: a macro an app builds at runtime is no longer invisible** (`4.11`). The
  content index is queried once per macro name that is known before the scan, and the
  names came from one place only: a module descriptor implementing `MacroMetadataSource`.
  An app that registers a single generic macro host and instantiates its macros at runtime
  out of its own storage therefore contributed no name, so no `MacroUsageQuery` was ever
  built for it. The figures that came out were not zero, they were unasked, and nothing in
  the report said so.
  Measured on a customer instance against ScriptRunner for Confluence 10.8.0: 99 enabled
  modules, one of them classified as a macro module, zero macros enumerated, every usage
  figure at zero, while the app's own registry export held 17 script macros, 2 CQL
  functions, 3 REST endpoints and 3 jobs.
  The macro name list now has a second source, the instance-wide macro catalogue
  (`MacroMetadataManager.getAllMacroMetadata()`). A name is attributed to an app only when
  the catalogue itself names that plugin key as the owner, so nothing is guessed, and each
  macro in the report carries where its name came from. The catalogue is resolved by name
  and every read is guarded separately, for the same reason the space picker is: a Spring
  component reaching a ScriptRunner endpoint as an AOP proxy is what broke that path twice.
  A catalogue that cannot be reached is reported as unreachable and never as an empty one.
- **Confluence: an app whose macro names could not be established reads as not measured**
  (`4.11`). Where neither source produced a name, the four macro usage figures render as
  not measured rather than as zero, and the app is held at Review required. Before this,
  such an app reached the closing verdict and read as having no detectable footprint. That
  it did not happen on the instance where this was found was luck: the incomplete archived
  scan caught it one branch earlier. `hasInventoryOnlyPersistenceSignals` covers blueprints,
  templates and custom content, and a macro host was not among them.

- **Confluence: the read path behind the export answers for itself, in the report**
  (`4.9`). The instance this was built for does not give its administrators access to
  `atlassian-confluence.log`, so a failure there is a referral number and nothing else. The
  report now states whether the SAL executor factory is found, whether the callback interface
  loads and can be implemented, whether a read-only executor can be created and whether the
  space table columns can be read from the catalogue - per building block, with the exception
  type and message on a refusal. No stack trace and no server path is printed.
  It is not free and therefore not automatic: `diag=true` runs all four, a standard report
  attempts only the two that cost a class load and prints a single line, and only when one of
  them refused. A step that was not attempted says so and is never counted as a pass. A
  self-check that cannot run at all is reported as a refusal rather than taken out on the
  report, which is the failure mode this release exists for.
  **Re-established on the `4.8` code in `4.10`, and it is the only part of 4.9 that was kept.**
  It was worth keeping on its own merits and not as a by-product of the rewrite: the reason a
  request failed has to reach the browser, because the administrators of that instance cannot
  open `atlassian-confluence.log` and the referral number from a `500` matched no line in
  their Splunk. `SelfCheck` holds every decision and every sentence and touches nothing that
  needs an instance, so the offline suite exercises all of it; `Db.probe` holds the four
  attempts, guards each one separately and is held to that by assertions read off the source.
  What did NOT come along is the dynamic typing that 4.9 wrapped it in.
- **Jira: an installed but idle workflow capability is reported as such** (`3.10`). This
  change was written against `3.8` and first carried the number `3.9`, which by then had
  already been published from `main` for the character escaping below. Two different
  contents under one number is a delivery fault rather than a cosmetic one, so it was
  moved up before it ever reached the remote. The report
  used to print the same empty sentence in every app card, which made an app registering a
  dozen workflow modules and using none look exactly like an app that cannot contribute one at
  all. Three states are now separated: no workflow module renders no section, modules with
  nothing configured are reported as a dormant capability with the modules listed, and
  configured extension points render the table as before. The instance summary counts the
  dormant apps, and the state reaches JSON and CSV.
  Measured on a live instance: sections rendered fell from 72 to 4, and three apps surfaced as
  dormant that were previously indistinguishable from the 68 without the capability.

- **Jira: the module behind a condition or validator is named where the descriptor allows it**
  (`3.6`). The implementation class identifies the app but not the module: measured on one
  instance, 34 app-and-class pairs covered 72 modules. The entry's own descriptor arguments are
  now matched against the module keys of the owning app, and a module is reported only when
  exactly one matches. A ScriptRunner condition that read `GroovyCondition` now reads
  `scriptrunner-workflow-function-…AllSubtasksResolvedCondition`. Ambiguous cases stay empty
  rather than carrying a guess, and every row records how its module was named.
- The naming rule is narrow on purpose (`3.7`), after an adversarial review found the first
  version could be confidently wrong. Candidates are the app's workflow modules only; argument values
  must be namespaced identifiers of real length, so that a system field id or group name cannot
  name a module; the value must sit at the end of the key; and exactly one candidate must match.
  Measured on a live instance after the narrowing: the four ScriptRunner extension points still
  name their exact canned module, and exactly three entries instance-wide carry a derived name.
- **Jira: every workflow extension point names its owning app in the JSON** (`3.8`). The owner
  was only implied by which app's list an entry sat in, which a consumer reading the flat
  extension list could not see.
- **Jira: an app whose only workflow footprint is a condition or validator is no longer missed
  by the text scan.** Implementation classes joined the scan's needles. The scan reaches for
  class needles only when the plugin key produced no hit at all, so no existing count can grow
  because of this; what changes is the case that previously produced nothing. Measured against a
  live instance with a workflow carrying a single ScriptRunner condition and no post function:
  the plugin key appears nowhere in that descriptor, `getModuleClass()` reports a Jira factory
  that appears nowhere either, and the workflow went entirely unlisted. It is now listed, with
  the detection path named.

- **Jira: app conditions and validators are attributed through the implementation class**
  (`3.5`). Jira writes the `full.module.key` argument for post functions only, verified in the
  bytecode of `AddWorkflowTransitionConditionParams` and `AddWorkflowTransitionValidatorParams`
  on Jira 11.3.8: neither writes it. An app condition or validator therefore reaches the
  descriptor carrying `class.name` alone. The class index is now built from
  `AbstractWorkflowModuleDescriptor.getImplementationClass()`, the same value Jira writes into
  that argument, so those extension points are found at all. Measured against a live instance:
  a ScriptRunner condition and validator went from undetected to detected.
- The class index deliberately prefers the implementation class over `getModuleClass()`, which
  for a workflow module frequently reports a **Jira** factory rather than a class of the app
  that registered it. Trusting the factory first would hand a Jira class to whichever app
  registered it first.

- **Jira: workflow extension points, per transition and per position** (`3.4`). The report
  now names every post function, condition, validator and pre function an app contributes
  to a workflow, together with the transition it sits in and its index inside that chain.
  Previously the workflow view could only say that a workflow referenced an app somewhere,
  which is not enough to plan a migration around.
- **Jira: ordering dependencies.** A post function of an app is flagged when a post function
  from another provider runs after it in the same chain. Conditional branches are kept
  apart, so entries that can never run in sequence are never compared. Reported per app, per
  transition, and as an instance total.
- Jira attributes an extension point through the `full.module.key` argument. That value is
  the plugin key and the module key concatenated **without a separator**, so it is resolved
  by longest matching plugin key rather than split. The key universe deliberately covers
  every installed plugin, including those the current filters exclude, so a shorter key
  cannot claim the modules of a longer one it happens to prefix. Where the argument is
  absent the implementation class is used, and the report says which of the two applied.
- Jira walks every workflow structurally, gated by nothing. It reads the descriptor graph
  the script already holds, so a workflow whose XML serialisation failed still yields its
  extension points, and the walk performs no additional retrieval.

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
  malformed input the control accepts. CI runs both suites.
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

- **Every glyph above ASCII in both endpoints is written as a `\uXXXX` escape, and a CI gate
  keeps it that way** (`4.10` / Jira `3.9`). Twelve raw characters sat in Confluence output
  strings and fourteen in the Jira ones, eleven of them `\u00B7` separators and the rest the
  `\u2014` placeholder in `NA`. ScriptRunner compiles a script with the DEFAULT charset of
  the server JVM, which is a property of the customer's server and not of this repository.
  Measured on Groovy 3.0.21 with the identical source file: the raw literal reads as the
  intended character under `-Dfile.encoding=UTF-8`, as `U+FFFD` under `US-ASCII` and as
  mojibake under `ISO-8859-1`, while `\uXXXX` reads as the intended character under all
  three. That the report looked correct after a fresh registration proves nothing; it was
  read on one charset. The escape removes the dependency instead of observing it.
  The gate is written in Python rather than as a `grep -P` pattern. The neighbouring
  control-byte gate in the sibling repository is a `grep -P` with a `|| true`: BSD grep has
  no `-P`, the error is swallowed, and the step reports green on a maintainer's Mac without
  having read a byte - observed while writing this. A gate that passes without checking is
  worse than none.
- **Confluence page and space reads use persistence service locators.** Deprecated
  `PageManager.getPage(...)` and `SpaceManager.getSpace(...)` lookups were replaced with
  `PageService` and `SpaceService` locators. `PageManager` remains only for non-deprecated
  page writes and moves. The staged space picker now paginates the public API
  `SpaceService.find(...)`, and settings reads use the purpose-specific
  `GlobalSettingsManager`, removing the remaining deprecated manager APIs.
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

- **WITHDRAWN in `4.10`, see the Withdrawn section above. The premise of this entry is
  disproven: the fault was a stale ScriptRunner endpoint registration, not a type that could
  not resolve, and the unchanged `4.8` loads and exports once the endpoint is re-created. The
  entry is kept verbatim because the reasoning it records is what produced 4.9.**
  Confluence: `4.8` did not load on the customer instance, and 4.9 removes the class of
  fault rather than diagnosing it. After 4.8 the endpoint answered `500` to the plain `GET`
  as well - a request that reaches no line of the new database code. A fault that hits a
  request unable to execute the new code is a fault at load time, and a script that does not
  load fails every call it will ever get. 4.8 named seven types the earlier versions did not:
  `java.sql.Connection`, `DatabaseMetaData`, `PreparedStatement` and `ResultSet`,
  `java.lang.reflect.InvocationHandler`, `Method` and `Proxy`, plus
  `org.codehaus.groovy.runtime.InvokerHelper`, and it built the SAL callback as an anonymous
  inner class inside a script file. None of them is named any more: every signature and local
  on that path is `Object`, every call goes through one dynamic dispatch point, and the
  callback is a Groovy closure coerced to an interface loaded by name, which leaves the JDK
  proxy inside the Groovy runtime. What a type is not named by cannot fail to resolve for it.
  **UNVERIFIED, and it stays that way: that those types were the cause.** The stack trace
  behind the referral number is not reachable, so nothing here is a diagnosis. What is
  measured is the absence of the dependency - the read path compiles on its own with one
  import and its class file names none of those types, where the same measurement on the 4.8
  file fails with ten unresolved classes. The behaviour of the picker is unchanged: the same
  `SELECT`, the same bound status, the same catalogue check before the statement, the same
  refusals.
- **Confluence: the space list of the export could not be read on any instance** (`4.8`,
  superseded by `4.9` above, which did not reach an instance in a loadable state).
  Opening "Export to Confluence" answered with `IllegalArgumentException:
  org.springframework.aop.SpringProxy referenced from a method is not visible from class
  loader ... ChainingClassLoader`. The stage resolved
  `com.atlassian.confluence.api.service.content.SpaceService`, and that concrete type is a
  Spring AOP proxy the ScriptRunner chaining classloader cannot see. The fail-loud path was
  working correctly: it reported a failed read rather than an instance without spaces. The
  read path was the broken part. The picker now reads the `SPACES` table through the SAL
  read-only executor, with `CURRENT` as a bound parameter and never as pasted text, and the
  columns `spacekey`, `spacename` and `spacestatus` verified through the database catalogue
  before the statement runs. A column that moved in an upgrade is named in the refusal; it
  can no longer produce a picker listing every space including the archived ones. The same
  fix was measured on two Confluence 10.2.14 instances in the sibling space-configuration
  script. The `api.service.content` imports are kept, unused, with the measurement written
  next to them: the finding is about that one proxied type, and `api.service.settings` is
  measured working on the same instance line.
- **Confluence: a cut space list is now announced.** The list travels with the cap it was
  read under and the ordering the cap cut by, and the browser says so when it was cut. A
  silently shortened list reads exactly like a complete one.

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
