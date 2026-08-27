# Confluence Service-Locator Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all eight deprecated Confluence space/page lookup calls without suppressing warnings or changing the established page-export behavior.

**Architecture:** Resolve Confluence's persistence-model `PageService` and `SpaceService` through `ComponentLocator`, then use their exact id, title-and-space-key, and space-key locators for reads. Keep `PageManager` only for the existing save and move operations, and preserve every fail-closed response boundary.

**Tech Stack:** Groovy 3, ScriptRunner REST endpoint, Confluence Data Center 10 service locators, JDK 17 offline harness

---

### Task 1: Add a source-contract regression test

**Files:**
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`
- Test: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Add assertions that locate the shipped endpoint source**

Add a small source-contract section before the result block. It must work both from the repository root, as CI does, and from `confluence/tests`, as the README documents:

```groovy
File endpointSource = new File("confluence/confluenceDCappFootprint.groovy")
if (!endpointSource.isFile()) {
    endpointSource = new File("../confluenceDCappFootprint.groovy")
}
ok("service-locator contract can read the endpoint source", endpointSource.isFile())
String endpointText = endpointSource.isFile() ? endpointSource.getText("UTF-8") : ""
```

- [ ] **Step 2: State the complete migration contract**

Assert that the endpoint contains no `pageManager.getPage(` or `spaceManager.getSpace(` call and no `SuppressWarnings("deprecation")`. Assert exact counts for the replacement calls: five title-and-space-key page locators, two id page locators, and one space-key locator. Also assert the two service imports so a same-named API service cannot be substituted silently.

```groovy
ok("no deprecated PageManager lookup remains", !endpointText.contains("pageManager.getPage("))
ok("no deprecated SpaceManager lookup remains", !endpointText.contains("spaceManager.getSpace("))
ok("deprecation warnings are not suppressed", !endpointText.contains('SuppressWarnings("deprecation")'))
check("all title lookups use PageService", endpointText.count("pageService.getTitleAndSpaceKeyPageLocator("), 5)
check("all id lookups use PageService", endpointText.count("pageService.getIdPageLocator("), 2)
check("the space lookup uses SpaceService", endpointText.count("spaceService.getKeySpaceLocator("), 1)
ok("persistence PageService is imported",
    endpointText.contains("import com.atlassian.confluence.content.service.PageService"))
ok("persistence SpaceService is imported",
    endpointText.contains("import com.atlassian.confluence.content.service.SpaceService"))
```

- [ ] **Step 3: Run the full Confluence suite and observe RED**

Assemble the suite exactly as `.github/workflows/ci.yml` does, then run it with JDK 17 and Groovy 3.0.21:

```bash
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY_CP" groovy.ui.GroovyMain /tmp/conf_testsuite.groovy
```

Expected: the existing 461 assertions stay green, while the new source-contract assertions fail because all eight manager lookups still exist and the locator calls/imports do not.

### Task 2: Migrate parent-search readbacks to `PageService`

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy:65`
- Modify: `confluence/confluenceDCappFootprint.groovy:1040`
- Modify: `confluence/confluenceDCappFootprint.groovy:3769`

- [ ] **Step 1: Import the persistence service interfaces**

Add:

```groovy
import com.atlassian.confluence.content.service.PageService
import com.atlassian.confluence.content.service.SpaceService
```

Keep `PageManager`, `SpaceManager`, `Page`, and `Space`: the managers still supply inventory/write/move operations, while the services supply exact reads.

- [ ] **Step 2: Change `Analyzer.searchPagesByTitle` to accept `PageService`**

Replace its `PageManager pageManager` parameter with `PageService pageService`. Replace the exact lookup and indexed-hit readback with:

```groovy
Page exact = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, query).getPage()
```

```groovy
page = pageService.getIdPageLocator(Long.parseLong(contentId)).getPage()
```

Keep both surrounding `try` blocks, null checks, cross-space guard, ordering, and error text unchanged.

- [ ] **Step 3: Resolve `PageService` in the `pages` action**

Resolve `PageService.class`, require it together with `SearchManager`, report `PageService` when it is missing, and pass it to `Analyzer.searchPagesByTitle`. Do not add a fallback to `PageManager`.

### Task 3: Migrate the page-export read path

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy:3828`
- Modify: `confluence/confluenceDCappFootprint.groovy:4117`

- [ ] **Step 1: Resolve all required read/write components once**

At the write action boundary, keep `PageManager` and add:

```groovy
PageService pageService = ComponentLocator.getComponent(PageService.class)
SpaceService spaceService = ComponentLocator.getComponent(SpaceService.class)
```

Require all three components in the existing 500 response gate.

- [ ] **Step 2: Replace the space and id lookups**

Use:

```groovy
space = spaceService.getKeySpaceLocator(spaceKey).getSpace()
parentPage = pageService.getIdPageLocator(parentId).getPage()
```

Keep the current exception handling, null distinction, and parent-space validation.

- [ ] **Step 3: Replace all four export title lookups**

Use `pageService.getTitleAndSpaceKeyPageLocator(...).getPage()` for the existing report page, typed parent pre-create check, parent post-save readback, and report post-save readback. Keep every lookup in its current `try`/failure boundary so a thrown read never becomes a measured miss.

- [ ] **Step 4: Correct the surrounding comments**

Describe the exact service locators and persistence return types. Retain the measured warning about the earlier API-model `ContentService` AOP proxy only where it explains why writes still use `PageManager`; remove text that justifies deprecated reads.

### Task 4: Advance the endpoint version and record the migration

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy:128`
- Modify: `README.md:14`
- Modify: `CHANGELOG.md:10`

- [ ] **Step 1: Bump the Confluence endpoint from 4.3 to 4.4**

Change `Cfp.VERSION` to `4.4` and the Confluence row in `README.md` to `4.4`. Do not change the Jira endpoint version.

- [ ] **Step 2: Add an Unreleased Changed entry**

Record that deprecated `PageManager.getPage(...)` and `SpaceManager.getSpace(...)` reads were replaced with the persistence `PageService` / `SpaceService` locators, with `PageManager` retained only for non-deprecated writes and moves.

### Task 5: Prove GREEN and inspect scope

**Files:**
- Test: `confluence/tests/confluenceDCappFootprint.tests.groovy`
- Test: `tools/parsecheck.groovy`

- [ ] **Step 1: Run the complete Confluence offline suite**

```bash
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY_CP" groovy.ui.GroovyMain /tmp/conf_testsuite.groovy
```

Expected: `FAILED: 0` and `ALL TESTS PASSED`, with the assertion count increased by the new source contract.

- [ ] **Step 2: Run the conversion-phase parse check**

```bash
java -Dfile.encoding=UTF-8 -cp "$GROOVY_CP" groovy.ui.GroovyMain tools/parsecheck.groovy confluence/confluenceDCappFootprint.groovy
```

Expected: `PARSE OK` and the parsed class list.

- [ ] **Step 3: Run focused source checks**

```bash
rg -n 'pageManager\.getPage\(|spaceManager\.getSpace\(|SuppressWarnings\("deprecation"\)' confluence/confluenceDCappFootprint.groovy
rg -n 'pageService\.get(TitleAndSpaceKey|Id)PageLocator|spaceService\.getKeySpaceLocator' confluence/confluenceDCappFootprint.groovy
```

Expected: the first command has no matches; the second names exactly eight lookup calls.

- [ ] **Step 4: Inspect the complete diff**

```bash
git diff --check
git diff -- confluence/confluenceDCappFootprint.groovy confluence/tests/confluenceDCappFootprint.tests.groovy README.md CHANGELOG.md
```

Confirm no unrelated behavior, warning suppression, API-model service import, credential, internal reference, or generated artifact entered the diff.

### Task 6: Synchronize the active ScriptRunner source and commit

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy`

- [ ] **Step 1: Apply the verified endpoint diff to the active copy**

Apply the same endpoint patch to the active local source only after Task 5 is green. Do not alter the active Jira endpoint or test files.

- [ ] **Step 2: Verify byte identity**

```powershell
Get-FileHash -Algorithm SHA256 'confluence\confluenceDCappFootprint.groovy'
Get-FileHash -Algorithm SHA256 'confluence/confluenceDCappFootprint.groovy'
```

Expected: the two SHA-256 values are identical.

- [ ] **Step 3: Commit the repository changes**

```bash
git add confluence/confluenceDCappFootprint.groovy confluence/tests/confluenceDCappFootprint.tests.groovy README.md CHANGELOG.md
git commit -m "OP-960 refactor: replace deprecated Confluence lookups"
```

- [ ] **Step 4: Record acceptance evidence in OP-960**

Comment with the red/green assertion results, parsecheck, focused lookup counts, hash equality, and final commit. Keep OP-960 In Progress because the required Confluence 10.2.10 / ScriptRunner 10 runtime smoke test is not performed by this local refactor; service resolution at the target remains `UNKNOWN` until measured.

### Task 7: Add RED coverage for the remaining deprecated APIs

**Files:**
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy:1368-1377`
- Test: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Extend the source contract**

Add assertions which reject `.getAllSpaces(` and the exact `SettingsManager` token, require
the API `SpaceService` alias and paginated current-space finder, and require
`GlobalSettingsManager`:

```groovy
ok("no deprecated getAllSpaces call remains", !endpointText.contains(".getAllSpaces("))
ok("no deprecated SettingsManager type remains",
    !(endpointText =~ /\bSettingsManager\b/).find())
ok("API SpaceService is imported with a distinct name",
    endpointText.contains("import com.atlassian.confluence.api.service.content.SpaceService as ApiSpaceService"))
check("the current-space finder is paginated", endpointText.count(".withStatus(ApiSpaceStatus.CURRENT)"), 1)
check("space pagination checks for more results", endpointText.count("spacePage.hasMore()"), 1)
ok("GlobalSettingsManager is imported",
    endpointText.contains("import com.atlassian.confluence.setup.settings.GlobalSettingsManager"))
check("both settings reads resolve GlobalSettingsManager",
    endpointText.count("ComponentLocator.getComponent(GlobalSettingsManager.class)"), 2)
```

- [ ] **Step 2: Run the complete suite and prove RED**

Run the offline test suite as described in `jira/tests/README.md`. Expected: the existing assertions remain
green and the new contract fails on `getAllSpaces()`, `SettingsManager`, missing API
finder pagination, and missing `GlobalSettingsManager`.

- [ ] **Step 3: Commit the RED test**

```bash
git add confluence/tests/confluenceDCappFootprint.tests.groovy
git commit -m "OP-960 test: cover remaining Confluence deprecations"
```

### Task 8: Replace the remaining deprecated APIs and prove GREEN

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy:62-90,3713-3742,3978-3998,4154-4164`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `confluence/confluenceDCappFootprint.groovy`

- [ ] **Step 1: Import the current public APIs**

```groovy
import com.atlassian.confluence.api.model.content.Space as ApiSpace
import com.atlassian.confluence.api.model.content.SpaceStatus as ApiSpaceStatus
import com.atlassian.confluence.api.model.pagination.PageResponse
import com.atlassian.confluence.api.model.pagination.SimplePageRequest
import com.atlassian.confluence.api.service.content.SpaceService as ApiSpaceService
import com.atlassian.confluence.setup.settings.GlobalSettingsManager
```

Remove the deprecated `SettingsManager` import. Keep the distinct persistence-model
`SpaceService`, `Space`, `SpaceManager`, and `SpaceStatus` imports for their existing
non-deprecated call sites.

- [ ] **Step 2: Replace the staged space inventory with complete API pagination**

Resolve `ApiSpaceService`, query `ApiSpaceStatus.CURRENT`, add every returned API space's
key and name to `spaceRows`, and advance by `spacePage.size()` while
`spacePage.hasMore()` is true. Throw if a page claims more results but returns zero so a
partial inventory can never be reported as complete.

- [ ] **Step 3: Narrow both settings reads**

Replace both `SettingsManager` declarations and class literals with
`GlobalSettingsManager`, and update the surrounding comments without changing the base
URL or site-title behavior.

- [ ] **Step 4: Advance the Confluence source version**

Change only the Confluence endpoint from `4.4` to `4.5` in `Cfp.VERSION` and the README,
then extend the existing OP-960 changelog entry to name the API space finder and
`GlobalSettingsManager` replacement.

- [ ] **Step 5: Run GREEN verification**

Run the full suite and parse check offline. Then require no matches from:

```bash
rg -n '\.getAllSpaces\(|\bSettingsManager\b|SuppressWarnings\("deprecation"\)' confluence/confluenceDCappFootprint.groovy
```

Inspect `git diff --check` and the complete authorized diff.

- [ ] **Step 6: Synchronize, verify, commit, and update OP-960**

Copy the verified endpoint byte-for-byte to the active ScriptRunner source, compare both
SHA-256 values, commit repository changes with an `OP-960 ` subject, and record RED/GREEN,
parse, signature, hash, and commit evidence in OP-960. Keep the item In Progress until the
Director confirms the Confluence runtime smoke test.
