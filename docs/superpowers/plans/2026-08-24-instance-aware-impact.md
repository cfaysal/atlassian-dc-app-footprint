# Instance-aware Impact Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the standalone Jira and Confluence Groovy reports the same instance-relative impact model and add the missing Jira-style instance/Base URL block to Confluence.

**Architecture:** Each standalone script contains the same small pure impact model because the files must remain independently pasteable into ScriptRunner. Product-specific code supplies measured numerators and instance denominators, while the pure model applies max-of-dimensions, shared percentage bands, and uncertainty rules. Existing raw metrics remain intact and are augmented with structured impact evidence in HTML, JSON, and CSV.

**Tech Stack:** Groovy 3, Jira Data Center Java API, Confluence Data Center Java API, ScriptRunner custom REST endpoints, offline Groovy harnesses.

---

## File map

- Modify `jira/jiraDCappFootprint.groovy`: pure impact model, instance denominators, assessment, sorting, counters, HTML badges, JSON, CSV.
- Modify `jira/tests/jiraDCappFootprint.tests.groovy`: impact boundary, uncertainty, aggregation, export, and source-wiring regressions.
- Modify `jira/tests/README.md`: record impact coverage and verified assertion count.
- Modify `confluence/confluenceDCappFootprint.groovy`: replace absolute policy, add instance content denominator, apply assessment, expose evidence, and render the instance/Base URL block.
- Modify `confluence/tests/confluenceDCappFootprint.tests.groovy`: impact boundary, partial-state, export, and Base URL regressions.
- Modify `confluence/tests/README.md`: record impact and instance-block coverage and verified assertion count.
- Modify `README.md`: document relative impact semantics shared by both products.

### Task 1: Pure percentage policy in both standalone scripts

**Files:**
- Modify: `jira/jiraDCappFootprint.groovy`
- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `confluence/confluenceDCappFootprint.groovy`
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Add failing boundary and max-of-dimensions tests**

Add table-driven cases for shares `0`, `0.01`, `4.99`, `5`, `19.99`, `20`, `49.99`, `50`, and values above `100`. Assert `NO_DETECTABLE_FOOTPRINT`, `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`, exact ranks, ratio capping, max-of-dimensions, numerator/denominator evidence, and lower-bound reasons.

```groovy
List<List<Object>> impactCases = [
    [1L, 10000L, "LOW"],
    [5L, 100L, "MEDIUM"],
    [20L, 100L, "HIGH"],
    [50L, 100L, "CRITICAL"],
    [250L, 100L, "CRITICAL"]
]
for (List<Object> row : impactCases) {
    ImpactDimension dimension = new ImpactDimension("reach", "Reach",
        (Long) row.get(0), (Long) row.get(1), false)
    ImpactAssessment result = ImpactPolicy.assess([dimension], false)
    check("impact " + row.get(0) + "/" + row.get(1), result.level, row.get(2))
}
```

- [ ] **Step 2: Run both offline suites and confirm the new symbols fail**

Run the extraction commands documented in `jira/tests/README.md` and `confluence/tests/README.md` with Groovy 3.0.21. Expected: both suites fail because `ImpactDimension` and the new `ImpactPolicy.assess` contract do not exist.

- [ ] **Step 3: Implement the same pure model in both scripts**

Add `ImpactDimension`, extend `ImpactAssessment`, and replace absolute constants with percent bands:

```groovy
class ImpactPolicy {
    static final BigDecimal CRITICAL_PERCENT = new BigDecimal("50")
    static final BigDecimal HIGH_PERCENT = new BigDecimal("20")
    static final BigDecimal MEDIUM_PERCENT = new BigDecimal("5")

    static ImpactAssessment assess(List<ImpactDimension> dimensions, boolean incomplete) {
        ImpactAssessment result = new ImpactAssessment()
        List<ImpactDimension> available = dimensions.findAll { ImpactDimension d -> d.available() }
        BigDecimal maximum = available.isEmpty() ? BigDecimal.ZERO :
            available.collect { ImpactDimension d -> d.percent() }.max()
        if (maximum.compareTo(CRITICAL_PERCENT) >= 0) result.setLevel("CRITICAL", "Critical", 7)
        else if (maximum.compareTo(HIGH_PERCENT) >= 0) result.setLevel("HIGH", "High", 6)
        else if (maximum.compareTo(MEDIUM_PERCENT) >= 0) result.setLevel("MEDIUM", "Medium", 5)
        else if (maximum.signum() > 0) result.setLevel("LOW", "Low", 4)
        else if (incomplete) result.setLevel("REVIEW_REQUIRED", "Review required", 2)
        else result.setLevel("NO_DETECTABLE_FOOTPRINT", "No detectable footprint", 0)
        result.dimensions.addAll(available)
        result.partial = incomplete || available.any { ImpactDimension d -> d.partial }
        result.addReasonsForSelectedLevel()
        return result
    }
}
```

`ImpactDimension.percent()` divides with a fixed scale, caps at `100`, returns zero for a zero numerator, and is unavailable when the denominator is null or non-positive. `ImpactAssessment.asMap()` emits level, label, rank, partial, reasons, and every dimension map.

- [ ] **Step 4: Run both suites and parse checks**

Expected: all boundary and max tests pass; both parse checks print `PARSE OK`.

- [ ] **Step 5: Commit the shared policy**

```bash
git add jira/jiraDCappFootprint.groovy jira/tests/jiraDCappFootprint.tests.groovy confluence/confluenceDCappFootprint.groovy confluence/tests/confluenceDCappFootprint.tests.groovy
git commit -m "OP-961 feat: add relative impact policy"
```

### Task 2: Confluence instance denominators and assessment

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy`
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Add failing product-assessment tests**

Create apps whose current content, associations, and space counts reach different bands against explicit instance totals. Assert that the highest dimension wins, archived-only remains `LEGACY_ONLY`, incomplete zero becomes `REVIEW_REQUIRED`, and complete zero becomes `NO_DETECTABLE_FOOTPRINT`.

```groovy
ImpactContext tiny = new ImpactContext(10L, 10L)
AppFootprint broad = measuredAppWithCurrentUsage(1, 1L, 8)
check("eight of ten spaces is critical",
    ImpactAnalyzer.assessConfluence(broad, true, tiny).level, "CRITICAL")
```

- [ ] **Step 2: Run the Confluence suite and verify failure**

Expected: failure because `ImpactContext` and the new `assessConfluence` signature are missing.

- [ ] **Step 3: Measure current instance totals through existing APIs**

Resolve `PageManager` once and read `countCurrentPages()` plus `countCurrentBlogs()` inside a guarded block. Use `currentSpaceKeys.size()` only when the space inventory succeeded. Carry each denominator as nullable so failed inventory cannot be reported as zero.

- [ ] **Step 4: Build Confluence dimensions and preserve special states**

Replace `Analyzer.assessImpact(app, scanUsage)` with a pure assessment using:

```groovy
new ImpactDimension("currentContent", "Current content reach",
    app.currentUniqueContentCount, currentContentTotal, app.currentUsagePartial)
new ImpactDimension("currentAssociations", "Current macro association density",
    app.currentAssociations, currentContentTotal, app.currentUsagePartial)
new ImpactDimension("currentSpaces", "Current space reach",
    app.currentSpaceCount, currentSpaceTotal, app.currentUsagePartial)
```

Apply `NOT_SCANNED` before percentage assessment and `LEGACY_ONLY` after a complete current zero but before inventory-only persistence review. A partial current scan without positive evidence returns `REVIEW_REQUIRED`.

- [ ] **Step 5: Run Confluence tests and parse check**

Expected: all assertions pass and parse check prints `PARSE OK`.

- [ ] **Step 6: Commit Confluence assessment**

```bash
git add confluence/confluenceDCappFootprint.groovy confluence/tests/confluenceDCappFootprint.tests.groovy
git commit -m "OP-961 feat: score Confluence against instance size"
```

### Task 3: Confluence instance and Base URL HTML parity

**Files:**
- Modify: `confluence/confluenceDCappFootprint.groovy`
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Add a failing source-wiring regression**

Assert that the main Confluence HTML contains a visible `instance` block after the page header and renders `instanceSiteTitle`, `instanceBaseUrl`, version/build, and all active options. Assert that the Base URL is escaped and uses the monospace class.

- [ ] **Step 2: Run the Confluence suite and confirm failure**

Expected: the instance-block assertion fails because only the page export currently renders instance identity.

- [ ] **Step 3: Add the Jira-style Confluence block**

Render:

```html
<div class="instance">
  <div><strong>Instance:</strong> ${esc(instanceSiteTitle ?: Cfp.NA)}</div>
  <div><strong>Base URL:</strong> <span class="mono">${esc(instanceBaseUrl ?: Cfp.NA)}</span></div>
  <div><strong>Confluence:</strong> ${esc(instanceVersion ?: Cfp.NA)} (build ${esc(instanceBuild ?: Cfp.NA)})</div>
  <div><strong>Options:</strong> <span class="mono">includeSystem=${includeSystem} includeDisabled=${includeDisabled} includeArchived=${includeArchived} includeModules=${includeModules} scanUsage=${scanUsage} scanAliases=${scanAliases} scanBudgetMs=${scanBudgetMs}</span></div>
</div>
```

Reuse the existing resolved values. Do not perform another settings lookup.

- [ ] **Step 4: Run Confluence tests and parse check**

Expected: all assertions pass and parse check prints `PARSE OK`.

- [ ] **Step 5: Commit Base URL parity**

```bash
git add confluence/confluenceDCappFootprint.groovy confluence/tests/confluenceDCappFootprint.tests.groovy
git commit -m "OP-961 feat: show Confluence instance identity"
```

### Task 4: Jira instance denominators and impact presentation

**Files:**
- Modify: `jira/jiraDCappFootprint.groovy`
- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`

- [ ] **Step 1: Add failing Jira assessment tests**

Cover a small instance where 8 of 10 reached spaces is Critical, a large instance where 8 of 1000 is Low, a custom-field dominated instance, association density capped at 100 percent, incomplete counts, zero denominators, and max-of-dimensions.

- [ ] **Step 2: Run Jira suite and confirm failure**

Expected: failures for the missing Jira assessment context and impact export fields.

- [ ] **Step 3: Measure Jira denominators once**

Use `issueManager.getIssueCount()` for all work items, keep the existing active project inventory outside the `includeReach` guard, count active scannable workflows from snapshots, and reuse `allCustomFields.size()`. Each read has a separate success flag and diagnostic.

- [ ] **Step 4: Assess every Jira app after `finish()`**

Create dimensions for issue-field associations, reached work items, reached spaces, custom fields, and active workflows. Use the same `ImpactPolicy` and uncertainty semantics as Confluence. Sort by impact rank, then maximum share, then the existing footprint signal/name ordering.

- [ ] **Step 5: Add Jira badges, counters, reasons, JSON, and CSV**

Mirror the Confluence legend names and colors. Add `impactLevel`, `impactLabel`, `impactPartial`, `impactPercent`, and serialized dimension evidence without removing existing columns or fields.

- [ ] **Step 6: Run Jira tests and parse check**

Expected: all assertions pass and parse check prints `PARSE OK`.

- [ ] **Step 7: Commit Jira impact parity**

```bash
git add jira/jiraDCappFootprint.groovy jira/tests/jiraDCappFootprint.tests.groovy
git commit -m "OP-961 feat: score Jira against instance size"
```

### Task 5: Documentation and cross-script parity checks

**Files:**
- Modify: `README.md`
- Modify: `jira/tests/README.md`
- Modify: `confluence/tests/README.md`
- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`

- [ ] **Step 1: Add source-level parity assertions**

Assert identical percentage literals, level names, and ranks in both endpoint sources. Assert no absolute impact constants such as `CRITICAL_CONTENT`, `HIGH_SPACES`, or `MEDIUM_ASSOCIATIONS` remain.

- [ ] **Step 2: Document the semantics**

Explain max-of-dimensions, the `50/20/5/>0` bands, product-specific denominators, lower-bound behavior, and why raw counts without natural denominators do not affect severity.

- [ ] **Step 3: Run both complete offline suites, parse checks, and `git diff --check`**

Expected: both suites end with `ALL TESTS PASSED`; both parsers print `PARSE OK`; diff check produces no output.

- [ ] **Step 4: Record measured assertion counts and commit**

```bash
git add README.md jira/tests/README.md confluence/tests/README.md jira/tests/jiraDCappFootprint.tests.groovy confluence/tests/confluenceDCappFootprint.tests.groovy
git commit -m "OP-961 test: verify impact parity"
```

### Task 6: ScriptRunner-compatible acceptance

**Files:**
- Verify: `jira/jiraDCappFootprint.groovy`
- Verify: `confluence/confluenceDCappFootprint.groovy`

- [ ] **Step 1: Inspect final scope and history**

Run `git diff pre-op961-instance-aware-impact..HEAD --stat`, `git diff --check`, and `git status --short --branch`. Expected: only the two scripts, their tests/readmes, repository README, design, and plan are changed; worktree clean after commits.

- [ ] **Step 2: Run the existing plugin-dev ScriptRunner compile/type check**

Use the established plugin-dev environment to validate both `.groovy` files against the Jira and Confluence Data Center APIs. Do not run Maven and do not build a JAR. Expected: both scripts receive the green ScriptRunner static/type result with no new deprecation warnings.

- [ ] **Step 3: Run behavior-preserving simplification review**

Review only lines changed by OP-961. Accept only simplifications that preserve the approved scoring, output, and uncertainty behavior. Re-run both suites after any accepted edit.

- [ ] **Step 4: Add the acceptance receipt to OP-961 and transition Done**

Comment with C1 behavior, C2 test and ScriptRunner evidence, C3 no new permissions/network/security impact, and C4 final diff integrity. Transition only after read-back confirms the acceptance evidence and status.
