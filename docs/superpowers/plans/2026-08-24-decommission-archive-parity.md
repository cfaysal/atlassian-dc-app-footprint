# Decommission and Archive Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox acceptance so progress can be resumed without guessing.

**Goal:** Give Jira and Confluence the same guarded decommission-candidate contract, and make Jira impact classification distinguish active from archived Space and Work Item evidence.

**Architecture:** Keep both files deployable as standalone ScriptRunner Groovy endpoints. Product-independent policy and partitioning logic stays in the existing helper-class cut so the offline suites exercise the shipped source; Jira/Confluence APIs remain below the cut and feed explicit measured/partial states into that policy. Archive reads are bounded by the existing Jira issue budget and fail closed to `REVIEW_REQUIRED`.

**Tech Stack:** Groovy 3, ScriptRunner for Jira/Confluence Data Center, Jira public Java APIs, existing offline Groovy test harness

---

## Task 1: Lock the shared candidate contract with failing tests

**Files:**

- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`
- Modify: `jira/jiraDCappFootprint.groovy`
- Modify: `confluence/confluenceDCappFootprint.groovy`

- [ ] Add Jira and Confluence assertions for a pure `ImpactPolicy.isDecommissionCandidate(systemProvided, assessment)` predicate: non-system `NO_DETECTABLE_FOOTPRINT` is eligible; system apps, `LEGACY_ONLY`, `REVIEW_REQUIRED`, and `NOT_SCANNED` are not.
- [ ] Add source-contract assertions that Jira wording says `Included in this report`, never `Enabled, not system-provided`, and that Confluence renders a candidate notice.
- [ ] Run both focused suites and record RED because the helper and Confluence notice do not yet exist.
- [ ] Add the smallest identical predicate to both `ImpactPolicy` classes and route Jira's existing list through it.
- [ ] Re-run focused suites and record GREEN for the candidate truth table.

## Task 2: Add Confluence candidate parity and fail-closed archive semantics

**Files:**

- Modify: `confluence/tests/confluenceDCappFootprint.tests.groovy`
- Modify: `confluence/confluenceDCappFootprint.groovy`

- [ ] Add RED tests for disabled-app eligibility through report-population semantics, candidate count in JSON/page-export summary, notice rendering, and `includeArchived=false` producing `REVIEW_REQUIRED` instead of a false complete zero.
- [ ] Extend `ImpactAnalyzer.assessConfluence` with an explicit archive-measurement flag and require that evidence before returning `NO_DETECTABLE_FOOTPRINT`.
- [ ] Build `decommissionCandidates` only from already included apps and the pure predicate; preserve the existing `DISABLED` badge.
- [ ] Render the guarded notice and candidate rows in HTML, and add `decommissionCandidateCount` to the JSON and page-export summary.
- [ ] Re-run the Confluence suite and record GREEN.

## Task 3: Introduce pure Jira active/archive evidence models

**Files:**

- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `jira/jiraDCappFootprint.groovy`

- [ ] Add RED unit tests for partitioning known project keys into active and archived sets, flagging unknown keys as partial, and preserving distinct active/archive counts in maps.
- [ ] Extend `WorkflowReference` with active/archive project keys, Work Item counts, and reach states while retaining aggregate compatibility fields where the UI still needs them.
- [ ] Extend `CustomFieldFootprint` with active/archive association values and explicit split state.
- [ ] Extend `AppFootprint.finish()` to aggregate active and archived reach independently without double-counting a Space or Work Item.
- [ ] Add a small product-independent partition helper to the offline cut; unknown keys must set partial rather than disappear.
- [ ] Re-run the Jira suite and record GREEN for the pure model.

## Task 4: Measure Jira archive-aware denominators and evidence

**Files:**

- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `jira/jiraDCappFootprint.groovy`

- [ ] Add RED source-contract tests for `includeArchived` defaulting to true, active inventory via `getProjectObjects()`, archive inventory via `getArchivedProjects()`, per-archive Work Item counts, bounded issue-ID batches, and fail-closed state propagation.
- [ ] Read active and archived Space inventories separately and expose complete inventory-key sets.
- [ ] Compute archived Work Items from `getIssueCountForProject(projectId)` and active Work Items as global minus archived; reject failed, missing, or negative splits.
- [ ] Partition workflow and screen reach keys against the inventories; an unknown key makes that path partial.
- [ ] When `issueCounts && includeArchived`, scan archived issue IDs in bounded batches using `getIssueIdsForProject`, `getIssueObjects`, and `CustomField.getValue`; stop at `issueBudgetMs` and mark every unfinished split partial.
- [ ] When archive measurement is disabled or incomplete, retain observed positive evidence but prevent a complete-zero conclusion.
- [ ] Re-run the Jira suite and record GREEN for the source contracts and pure state logic.

## Task 5: Classify and render Jira active/archive impact

**Files:**

- Modify: `jira/tests/jiraDCappFootprint.tests.groovy`
- Modify: `jira/jiraDCappFootprint.groovy`

- [ ] Add RED tests for active-only percentage dimensions, archive-only `LEGACY_ONLY`, incomplete split `REVIEW_REQUIRED`, complete all-zero `NO_DETECTABLE_FOOTPRINT`, and positive current impact retaining its relative band.
- [ ] Change `ImpactAnalyzer.assessJira` so percentage dimensions use active evidence only and archive evidence is evaluated after the current policy result.
- [ ] Report archive counts and explanatory reasons without raising the current impact percentage.
- [ ] Add `includeArchived` to query options, JSON, CSV evidence, HTML toggle/options, and page export.
- [ ] Add `LEGACY_ONLY` to counters, badges, filtering, JSON/CSV/page output and keep candidate eligibility restricted to complete `NO_DETECTABLE_FOOTPRINT`.
- [ ] Re-run the Jira suite and record GREEN.

## Task 6: Documentation, full verification, and runtime handoff

**Files:**

- Modify: `jira/tests/README.md`
- Modify: `confluence/tests/README.md`
- Modify: `README.md` if the report contract is documented there

- [ ] Update test coverage notes and the recorded assertion totals only from fresh output.
- [ ] Run both complete offline suites from the shipped helper-class cuts.
- [ ] Run whole-file Groovy conversion-phase parse checks for both endpoint files.
- [ ] Inspect `git diff --check`, `git diff --stat`, the full diff, and `git status` for scope integrity.
- [ ] Run the mandatory code-simplifier review only against changed production lines, accepting behavior-preserving simplifications only.
- [ ] Commit implementation with an `OP-962 ` subject and no attribution.
- [ ] Give the Director the exact two Groovy file paths and request Jira-/Confluence-test runtime smoke results; do not merge, push, or transition OP-962 to Done before both exact files pass.

## Verification commands

The repository's documented Bash extraction commands remain authoritative. On Windows, equivalent PowerShell extraction may be used, but it must cut the same class boundaries from the endpoint files rather than testing copied helpers.

```bash
cd jira/tests
F=../jiraDCappFootprint.groovy
START=$(grep -n '^class Fp {' "$F" | cut -d: -f1)
END=$(( $(grep -n '^ \* REST Endpoint$' "$F" | cut -d: -f1) - 2 ))
sed -n "${START},${END}p" "$F" > /tmp/classes.groovy
cat /tmp/classes.groovy jiraDCappFootprint.tests.groovy > /tmp/testsuite.groovy
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/testsuite.groovy

cd ../../confluence/tests
F=../confluenceDCappFootprint.groovy
A_START=$(grep -n '^class Cfp {' "$F" | cut -d: -f1)
A_END=$(( $(grep -n '^class Analyzer {' "$F" | cut -d: -f1) - 1 ))
B_START=$(grep -n '^class DecisionRead {' "$F" | cut -d: -f1)
B_END=$(( $(grep -n '^appFootprint(' "$F" | head -1 | cut -d: -f1) - 1 ))
{
  echo 'import java.util.regex.Matcher'
  echo 'import java.util.regex.Pattern'
  sed -n "${A_START},${A_END}p" "$F"
  sed -n "${B_START},${B_END}p" "$F"
  cat confluenceDCappFootprint.tests.groovy
} > /tmp/conf_testsuite.groovy
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/conf_testsuite.groovy

java -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain ../../tools/parsecheck.groovy ../confluenceDCappFootprint.groovy
```

## Runtime acceptance

- Jira: the exact branch file loads in ScriptRunner; archived toggle and output work; an archive-only fixture becomes `LEGACY_ONLY`; an incomplete/budgeted archive scan becomes `REVIEW_REQUIRED`; candidate notice includes eligible disabled apps only when `includeDisabled=true`.
- Confluence: the exact branch file loads in ScriptRunner; candidate notice/count render; disabled candidates follow `includeDisabled`; archive scan off cannot produce a candidate; page export still works.
- No merge or push before both runtime receipts are supplied.
