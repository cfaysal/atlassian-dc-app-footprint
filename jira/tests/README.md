# Offline tests for jiraDCappFootprint

The endpoint itself needs Jira classes and cannot run outside an instance. Most of what it
does, however, is product-independent: formatting, the module category heuristic, the
footprint aggregation, and the whole Confluence page export including the decision parser,
the space key validation and the page position logic. This suite tests exactly those, with
no Jira on the classpath and no running instance.

The classes free of every Jira type are `Fp`, `AppModuleInfo`, `ScreenPlacementInfo`,
`CustomFieldFootprint`, `WorkflowSnapshot`, `WorkflowReference`, `ImpactPolicy`,
`ProjectPartition`, `IssueTotals`, `ImpactDimension`, `ImpactAssessment`, `AppFootprint`,
`ImpactAnalyzer`, `DecisionRead`, `ExportOutcome` and `PageExport`.

## What is covered

### The workflow extension point walk

The report names every post function, condition, validator and pre function an app
contributes to a workflow, with the transition and the position inside its chain. The walk
is duck-typed against fake descriptors, so it runs without Jira on the classpath.

- Position and chain length are carried on every entry, and conditional branches stay
  separate chains: entries that can never run in sequence are never compared.
- Nested condition groups resolve recursively without double counting, and a common action
  referenced by several steps is reported once.
- The ordering marker fires only for a post function with an entry from another provider
  behind it in the same chain, and never for one that runs last.

### Attribution, and the two ways it goes wrong

- `full.module.key` concatenates the plugin key and the module key with **no separator**.
  A control implementation that splits on a colon is run against the value Jira itself
  ships, and fails exactly where the shipped rule succeeds.
- The longest matching plugin key wins, so a shorter key cannot claim the modules of a
  longer one it happens to prefix.
- Jira writes that argument for post functions only. An app condition or validator carries
  `class.name` alone, so the class index is the only path that finds it, and the index is
  built from the implementation class rather than from `getModuleClass()`, which for a
  workflow module often reports a Jira factory.
- Naming the module behind such an entry is guarded four ways, and the case that broke the
  first version is kept as a control: a validator configured on the `assignee` field, in an
  app owning a module called `assignee-sync-function`, matches exactly one candidate and
  would be confidently mislabelled. The shipped rule names nothing there.

### The decision parser

The report can export its summary as a Confluence page carrying a **Decision** column that
an administrator fills in. That column must survive every later run, so the parser gets the
most attention here.

- The three distinguishable outcomes `NONE`, `PARSED` and `FAILED`. `PARSED` with zero
  decisions is a legitimate result for a page nobody has annotated, not an error. `NONE`
  and `PARSED` allow a write, `FAILED` never does.
- Every failure trigger yields `FAILED`, a non-empty reason and an **empty** decision map.
  A partial parse must never leak into a write.
- Columns are located by their header name, never by position, so an inserted column does
  not orphan an administrator's notes.
- Cell content survives verbatim, including nested markup and HTML entities.
- The marker rule: a page with a colliding title that does not carry the export marker is
  `FAILED`, so the export can never overwrite a page it did not create.

### The page position logic

- `PageExport.moveDecision` is a pure decision about whether this run moves the page. A
  parent named in this run is an instruction and is carried out even on a page that already
  exists. No parent named leaves the position untouched. A page already sitting directly
  under that parent is not moved again. An unknown current position deliberately resolves
  to "move", never to "skip".
- `PageExport.parentOutcome` reports the measurement and nothing else: `true` only when the
  read-back names the requested parent, `false` when it answered and named something else
  or nothing, `unknown` when the read-back itself did not answer. A failed or empty read is
  never reported as a successful move, and never as a failed one either.
- `PageExport.innermostAncestor` separates a missing `ancestors` array, which is the absence
  of a measurement, from an empty one, which means the page sits at the top level.

### The space key

- `PageExport.spaceKeyProblem` accepts a leading tilde, so personal spaces work, and rejects
  anything that could break out of a CQL literal, naming the offending value.
- The suite also asserts that `cqlTerm` still strips the tilde. That is the defect the
  validation routes around: a space key is an identifier, not a search term, and must not
  pass through a search-term cleaner.
- `PageExport.parentProblem`: a parent id and a parent title are mutually exclusive, a
  parent title equal to the report title is refused, and the body of a created parent page
  deliberately carries no export marker.

### The measurement notes box

`Fp.diagClass` and `Fp.diagBoxShown` decide the colour and the visibility of the
"Measurement notes" box independently of each other. The box is coloured as a warning only
when it reports something that limits the measurement: an unresolved type field, a
budget-skipped issue count, a truncated screen reach, or a suppressed read error.
Observations are deliberate statements of the report and are not a parameter of the colour
at all, so no number of them can make the box look like a fault.

### Everything else

- HTML escaping, number formatting in both `de` and `en` styles, CSV quoting.
- Query parameter evaluation, including the default taken when the input is garbage.
- Link construction for the toggle buttons.
- The module category heuristic as a table, including the regression test for
  `WebResourceModuleDescriptor`, which must never be classified as "REST / API" again.
- Equivalence of the tokenised counter to a naive substring scan. This is a property test
  over randomly generated descriptor XML, more than 8000 cases, including self-overlapping
  needles and needles containing non-token characters.
- The fast-reject helpers `mayOccur` and `mergeTokens`.
- Aggregation in `AppFootprint.finish()`, including the partial-measurement flag, the union
  of projects across both paths, an issue count taken exactly once per project, the lower
  bound when a count is missing, and the never-evaluated case that must stay `n/e` rather
  than becoming zero.
- Active/archive partitioning for Project reach, Issue reach and custom-field values,
  including unknown keys, invalid total splits, bounded archive-source contracts, and the
  rule that known active evidence survives an omitted archive scan.
- The shared instance-relative impact bands, max-of-dimensions behavior, small-instance and
  large-instance cases, missing denominators, partial lower bounds, complete zero, JSON
  evidence, archive-only `LEGACY_ONLY`, fail-closed decommission eligibility, HTML
  counters/filtering, CSV evidence and Confluence page-export parity.
- A head-to-head benchmark of the old algorithm against the three-stage scan, asserting
  that both produce identical results.

## Red before green

A test that has never failed proves nothing. The suite therefore carries control
implementations of the discarded behaviour and measures the difference on every run.

For the page position that control is the create-only variant this endpoint used to have.
It refuses six of the eight position cases and claims four parents the measurement does not
confirm. That is precisely the defect the position logic replaced.

## What is not covered

Nothing that needs a running instance. That means the analysis against Jira itself, the
application link call that writes the page, the search that finds spaces and parent pages,
the move itself, the permission gate and both HTTP entry points. Test against a real
instance before trusting a change to any of those.

For the workflow walk specifically, the offline suite drives the descriptor fallback, not
`JiraWorkflow.getAllActions()`, so the scope labels `Initial`, `Global` and `Common` come
from a real instance or from nowhere. Two areas are outside the walk altogether and are not
tested because they are not built: workflow registers and trigger functions, whose
descriptor form has not been verified.

## Requirements

A JDK and a Groovy 3 jar. Nothing else, no Maven build, no Jira, no ScriptRunner.

Download the jar from Maven Central if you do not already have one, for example
`groovy-3.0.21.jar` from `org/codehaus/groovy/groovy/3.0.21/`.

## Running the suite

The helper classes are cut out of the endpoint file, so the tests always run against the
shipped source rather than a copy that can drift. The boundaries are derived with `grep`
rather than hard-coded, because the file keeps growing.

```bash
cd jira/tests

F=../jiraDCappFootprint.groovy
START=$(grep -n '^class Fp {' "$F" | cut -d: -f1)
END=$(( $(grep -n '^ \* REST Endpoint$' "$F" | cut -d: -f1) - 2 ))
sed -n "${START},${END}p" "$F" > /tmp/classes.groovy
cat /tmp/classes.groovy jiraDCappFootprint.tests.groovy > /tmp/testsuite.groovy

GROOVY=/path/to/groovy-3.0.21.jar
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/testsuite.groovy
```

A green run reports the red-before-green measurement and ends with `ALL TESTS PASSED`.

## Parse check

The parse check compiles the whole endpoint file to the CONVERSION phase. That validates
syntax without resolving any Jira symbol, so it runs anywhere:

```bash
java -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain \
     ../../tools/parsecheck.groovy ../jiraDCappFootprint.groovy
```

A green run prints `PARSE OK` followed by the parsed class names.

Note what this does and does not prove. The parse check finds syntax errors. It does not
resolve symbols, so a misspelled Jira method name passes it and still fails inside the
instance.

## Last recorded run

2026-08-27: 526 assertions green, parse check green. Red-before-green measurements of the
same run: eight position cases, where the create-only control refuses six moves and claims
four parents the measurement does not confirm; a split-on-colon control that finds no owner
in the value Jira ships; and a match-anywhere control that names a post function module for
a validator, where the shipped rule names nothing.

Benchmark over 100 workflows, 19.3 million characters of XML and 100 apps: old scan
6369 ms, new scan 1252 ms, identical results at 847 references. The absolute numbers say
more about the machine than about the code; the ratio is the point.
