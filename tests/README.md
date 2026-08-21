# Offline tests for jiraDCappFootprint

The endpoint itself needs Jira classes and cannot run outside an instance. The helper
classes `Fp`, `AppModuleInfo`, `ScreenPlacementInfo`, `CustomFieldFootprint`,
`WorkflowSnapshot`, `WorkflowReference` and `AppFootprint` are deliberately free of every
Jira type, and this suite tests exactly those, with no Jira on the classpath and no running
instance.

## What is covered

- HTML escaping, number formatting in both `de` and `en` styles, CSV quoting.
- Query parameter evaluation, including the default taken when the input is garbage.
- Link construction for the toggle buttons.
- The module category heuristic as a table, including the regression test for
  `WebResourceModuleDescriptor`, which must never be classified as "REST / API" again.
- Equivalence of the tokenised counter to a naive substring scan. This is a property test
  over randomly generated descriptor XML, more than 8000 cases, including self-overlapping
  needles and needles containing non-token characters.
- The fast-reject helpers `mayOccur` and `mergeTokens`.
- Aggregation in `AppFootprint.finish()`, including the partial-measurement flag.
- A head-to-head benchmark of the old algorithm against the three-stage scan, asserting
  that both produce identical results.

## Requirements

A JDK and a Groovy 3 jar. Nothing else, no Maven build, no Jira, no ScriptRunner.

Download the jar from Maven Central if you do not already have one, for example
`groovy-3.0.21.jar` from `org/codehaus/groovy/groovy/3.0.21/`.

## Running the suite

The suite is compiled together with the helper classes, which are cut out of the endpoint
file so that the tests always run against the shipped source rather than a copy that can
drift.

```bash
cd tests

F=../endpoints/jiraDCappFootprint.groovy
START=$(grep -n '^class Fp {' "$F" | cut -d: -f1)
END=$(( $(grep -n '^ \* REST Endpoint$' "$F" | cut -d: -f1) - 2 ))
sed -n "${START},${END}p" "$F" > /tmp/classes.groovy
cat /tmp/classes.groovy jiraDCappFootprint.tests.groovy > /tmp/testsuite.groovy

GROOVY=/path/to/groovy-3.0.21.jar
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain /tmp/testsuite.groovy
```

A green run ends with `ALL TESTS PASSED`.

## Parse check

The parse check compiles the whole endpoint file to the CONVERSION phase. That validates
syntax without resolving any Jira symbol, so it runs anywhere:

```bash
java -Dfile.encoding=UTF-8 -cp "$GROOVY" groovy.ui.GroovyMain \
     ../tools/parsecheck.groovy ../endpoints/jiraDCappFootprint.groovy
```

A green run prints `PARSE OK` followed by the parsed class names.

Note what this does and does not prove. The parse check finds syntax errors. It does not
resolve symbols, so a misspelled Jira method name passes it and still fails inside the
instance. Test against a real instance before trusting a change.

## Last recorded run

2026-08-21: 95 tests green, parse check green. Benchmark over 100 workflows, 19.3 million
characters of XML and 100 apps: old scan 6546 ms, new scan 1214 ms, identical results.
