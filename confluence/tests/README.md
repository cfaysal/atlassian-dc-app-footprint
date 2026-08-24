# Offline tests for confluenceDCappFootprint

The endpoint itself needs Confluence classes and cannot run outside an instance. Most of
what it does, however, is product-independent: formatting, the module category heuristic,
the footprint aggregation, and the whole Confluence page export including the decision
parser and the storage-format renderer. This suite tests exactly those, with no Confluence
on the classpath and no running instance.

The classes free of every Confluence type are `Cfp`, `ImpactPolicy`, `ImpactDimension`,
`ImpactAssessment`, `ExtensionModuleInfo`, `MacroFootprint`, `AppFootprint`,
`ImpactAnalyzer`, `DecisionRead`, `ExportOutcome` and `PageExport`. `Analyzer` sits between
them in the file, touches Confluence search types, and is therefore left out of the cut.

`Cfp` names five platform types in method signatures. The suite declares five empty
placeholder interfaces so the cut resolves. Only `MultivaluedMap` gets a real method, so
the parameter fake is a genuine stand-in rather than a cast. The two methods that actually
use the other four are outside the scope of this suite.

## What is covered

### The decision parser, which is why this suite exists

The Confluence report can export its summary as a page carrying a **Decision** column that
an administrator fills in. That column must survive every later run. The parser is the
thing standing between an administrator's notes and a silent overwrite, so it gets the
most attention here.

- The three distinguishable outcomes `NONE`, `PARSED` and `FAILED`. `PARSED` with zero
  decisions is a legitimate result for a page nobody has annotated, not an error. `NONE`
  and `PARSED` allow a write, `FAILED` never does.
- Every failure trigger yields `FAILED`, a non-empty reason, and an **empty** decision map:
  empty body, null body, missing export marker, a marker but no table carrying both column
  names, a data row shorter than the header, a duplicate app key, a truncated `<tbody>`.
  A partial parse must never leak into a write.
- Columns are located by their **header name**, never by position. The Decision column and
  the key column are moved, and a further column is inserted between them, and the same
  decisions are still found.
- Every `<tbody>` on the page is read, not only the first.
- Cell content survives verbatim, including nested markup and HTML entities. Surrounding
  whitespace is trimmed, the markup is not touched.
- The marker rule: a page with a colliding title that does not carry the export marker is
  `FAILED`, so the export can never overwrite a page it did not create.
- Round trip: a freshly generated page reads back as `PARSED` with zero decisions, not as
  `FAILED`. Two consecutive runs preserve the same notes.
- Loss reporting: when an app disappears between runs, the carried count is lower than the
  count read, the orphaned decision is named, and the warning appears in the storage.

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
- `PageExport.parentProblem`: a parent id and a parent title are mutually exclusive, a
  parent title equal to the report title is refused, and the body of a created parent page
  deliberately carries no export marker.

### The measurement notes box

`Cfp.diagClass` and `Cfp.diagBoxShown` decide the colour and the visibility of the
"Measurement notes" box independently of each other. The box is coloured as a warning only
when it reports something that limits the measurement: a macro scan skipped by the budget,
or a suppressed read error. Observations are deliberate statements of the report and are
not a parameter of the colour at all, so no number of them can make the box look like a
fault.

### Red before green

A test that has never failed proves nothing. The suite therefore carries a control
implementation of the discarded parser: first `<tbody>` only, fixed column indices, every
exception swallowed, a bare map with no way to signal failure.

Every run measures both against the same fixtures and asserts the difference: the real
parser refuses all nine malformed pages, the control refuses six. The control would have
written over a foreign page, written a partial parse, and silently dropped one of two
duplicate decisions. The discriminating power of these assertions is therefore checked on
every run rather than demonstrated once by hand.

The page position carries a second control: the create-only variant this endpoint used to
have, which set a parent when creating a page and ignored it on every later run. It refuses
six of the eight position cases and claims four parents the measurement does not confirm.
That is precisely the defect the position logic replaced.

### Everything else

- `Cfp`: HTML escaping, number formatting in both `de` and `en` styles, CSV quoting, query
  parameter evaluation including the default taken when the input is garbage, link
  construction, and the small helpers around content types and diagnostics.
- The module category heuristic as a table, with two regressions stated as such: a
  descriptor class name containing `xhtmlmacro` must be classified as `Macros`, and a
  `WebResourceModuleDescriptor` must never be classified as `REST / API`. The rule list is
  ordered and the first match wins, which is asserted as well.
- `MacroFootprint` across all five measurement states, including the assertion that a state
  other than measured yields `n/m` rather than a number, even when content ids are present.
- `AppFootprint` aggregation, the enabled-only counts, module type ordering and the partial
  flags.
- The cross-check between the category heuristic and the macro enumeration: it fires on a
  genuine mismatch, stays quiet when both agree, and raises the diagnostic counter so the
  report actually shows the message.
- Storage-format rendering: a budgeted row renders `n/m` and contains no zero, while a
  genuinely measured zero still renders as zero.
- The shared instance-relative impact bands, max-of-dimensions behavior, small-instance and
  large-instance cases, missing denominators, partial lower bounds, complete zero,
  archived-only handling, fail-closed decommission eligibility and rendering, CSV evidence,
  the disabled-scan counter and the visible Instance and Base URL block.

## What is not covered

Nothing that needs a running Confluence. That means `Analyzer`, which reads the search
index, the title search that finds parent pages, the page manager that performs the actual
write and the move, the permission gate, and both HTTP entry points. Test against a real instance before trusting
a change to any of those.

## Requirements

A JDK and a Groovy 3 jar. Nothing else, no Maven build, no Confluence, no ScriptRunner.

Download the jar from Maven Central if you do not already have one, for example
`groovy-3.0.21.jar` from `org/codehaus/groovy/groovy/3.0.21/`.

## Running the suite

The helper classes are cut out of the endpoint file, so the tests always run against the
shipped source rather than a copy that can drift. Two ranges are cut, because `Analyzer`
sits between them and is excluded. The two regex imports are prepended because the cut
starts below the import block while `PageExport` needs them.

```bash
cd confluence/tests

F=../confluenceDCappFootprint.groovy
A_START=$(grep -n '^class Cfp {' "$F" | cut -d: -f1)
A_END=$(( $(grep -n '^class Analyzer {' "$F" | cut -d: -f1) - 1 ))
B_START=$(grep -n '^class DecisionRead {' "$F" | cut -d: -f1)
B_END=$(( $(grep -n '^appFootprint(' "$F" | head -1 | cut -d: -f1) - 1 ))

{ echo 'import java.util.regex.Matcher'
  echo 'import java.util.regex.Pattern'
  sed -n "${A_START},${A_END}p" "$F"
  sed -n "${B_START},${B_END}p" "$F"
  cat confluenceDCappFootprint.tests.groovy
} > /tmp/conf_testsuite.groovy

GROOVY=/path/to/groovy-3.0.21.jar
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/conf_testsuite.groovy
```

A green run reports the red-before-green measurement and ends with `ALL TESTS PASSED`.

## Parse check

The parse check compiles the whole endpoint file to the CONVERSION phase. That validates
syntax without resolving any Confluence symbol, so it runs anywhere:

```bash
java -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain \
     ../../tools/parsecheck.groovy ../confluenceDCappFootprint.groovy
```

A green run prints `PARSE OK` followed by the parsed class names.

Note what this does and does not prove. The parse check finds syntax errors. It does not
resolve symbols, so a misspelled Confluence method name passes it and still fails inside
the instance.

## Last recorded run

2026-08-24: 564 assertions green, parse check green. Red-before-green measurement of the
same run: nine malformed fixtures, the parser refuses nine and the control refuses six;
eight position cases, the create-only control refuses six moves and claims four parents the
measurement does not confirm.
