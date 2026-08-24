# OP-960 Confluence service-locator migration

## Intent

Remove every direct call to the deprecated `SpaceManager.getSpace(...)` and
`PageManager.getPage(...)` methods from the Confluence ScriptRunner endpoint. The
warnings must disappear because the deprecated calls are gone, not because a warning is
suppressed.

## Selected design

Resolve `com.atlassian.confluence.content.service.SpaceService` and
`com.atlassian.confluence.content.service.PageService` with `ComponentLocator` alongside
the existing components.

- Space lookup: `spaceService.getKeySpaceLocator(spaceKey).getSpace()`.
- Page lookup by id: `pageService.getIdPageLocator(pageId).getPage()`.
- Page lookup by exact title: `pageService.getTitleAndSpaceKeyPageLocator(spaceKey, title).getPage()`.

These locators return the same persistence `Space` and `Page` types the existing export
logic consumes. The report renderer, decision parser, parent selection, page creation,
page update and fail-closed responses therefore keep their current contracts. The
non-deprecated `PageManager` write and move operations remain unchanged.

The source stays a single deployable Groovy file. No adapter or compatibility fallback
will call the deprecated methods, and no `SuppressWarnings("deprecation")` annotation will
be introduced.

## Alternatives considered

1. `com.atlassian.confluence.api.service.content.ContentService` and its API-model
   `SpaceService`: this is the replacement named by the deprecation notice, but the earlier
   write implementation failed on a live ScriptRunner endpoint because the Spring AOP
   proxy was not visible from the chaining class loader. It also returns API models rather
   than the persistence objects used by the established write path.
2. ScriptRunner HAPI `Pages.search(...)`: HAPI is class-loader-safe, but title search is
   index-backed and would weaken the exact database lookup used to prevent duplicate pages
   during index lag. HAPI for Confluence Data Center also exposes no equivalent direct
   space lookup in its documented page API.
3. The selected Confluence service locators: they preserve exact lookup semantics and
   persistence return types, are not deprecated, and are the same service layer used by
   ScriptRunner HAPI internally.

## Error handling

Locator creation and `getPage()` / `getSpace()` stay inside the existing `try` blocks.
A thrown service error remains a failed read, while a locator returning `null` remains a
measured miss. Component resolution adds `PageService` and `SpaceService` to the existing
required-component gate so an unavailable service cannot be mistaken for absent content.

## Files and versioning

- Modify `confluence/confluenceDCappFootprint.groovy`.
- Add source-level regression assertions to
  `confluence/tests/confluenceDCappFootprint.tests.groovy`.
- Record the migration in `CHANGELOG.md` and advance the Confluence endpoint version in
  the existing lockstep locations if the repository's release checks require it.
- Copy the verified endpoint byte-for-byte to the active local ScriptRunner source at the
  end of the implementation.

## Verification

1. Red: a new source-level test must fail against the current file because deprecated
   lookup calls are present and the service-locator calls are absent.
2. Green: the same test must pass after all eight lookups are migrated.
3. Run the complete offline Confluence suite and the conversion-phase parse check.
4. Verify that neither direct deprecated call nor a deprecation suppression occurs in the
   endpoint source.
5. Verify byte identity between the repository endpoint and the active local ScriptRunner
   source.
6. Runtime verification on Confluence 10.2.10 / ScriptRunner 10 must exercise space
   selection, parent search, create, repeat update and decision preservation. Until that
   run is observed, service resolution on the target remains `UNKNOWN` rather than passed.

## Remaining-warning addendum

The first implementation removed the eight deprecated exact lookups but left two other
deprecated APIs in the same endpoint. OP-960 also covers them:

- Replace `SpaceManager.getAllSpaces()` in the staged space picker with the public plugin
  API `com.atlassian.confluence.api.service.content.SpaceService.find()`.
- Restrict its finder to API `SpaceStatus.CURRENT` and fetch every page with
  `SimplePageRequest(start, limit)`. Advance `start` by the returned page size while
  `PageResponse.hasMore()` is true; refuse the request if pagination claims more results
  without advancing.
- Replace the deprecated broad `SettingsManager` interface with its purpose-specific
  super-interface `GlobalSettingsManager` at both settings reads.

The API-space objects supply the same key and display name required by the picker. The
report-wide current/archived key inventory stays on the non-deprecated
`SpaceManager.getAllSpaceKeys(SpaceStatus)` calls, so its measurement contract does not
change. The existing fail-closed behavior for an unavailable or empty space inventory is
preserved, and no deprecated compatibility fallback or warning suppression is allowed.

The exact Confluence 10.2.10 runtime JARs confirm the selected signatures and that the
replacement types are not deprecated. Regression assertions must reject
`getAllSpaces()`, `SettingsManager`, and deprecation suppression before the production
source is changed.
