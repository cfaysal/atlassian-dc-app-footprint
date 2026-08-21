# Contributing

Thanks for looking. This repository holds two self-contained ScriptRunner endpoints, so
the contribution rules are unusual in a few places. Please read the two hard rules before
opening a pull request.

## Two hard rules

**1. A failed read is never rendered as a measured zero.**

If a count cannot be taken, the report must say so at that cell. `n/m`, `err` or `off`,
never a `0` that a reader will mistake for evidence of absence. Partial results are marked
and are lower bounds, not estimates. A pull request that turns a suppressed exception into
a plausible-looking number will be rejected no matter how much cleaner the code reads.

The reason is simple: these reports are used to decide whether an app can be removed. A
false zero is not a cosmetic bug, it is a wrong answer to an expensive question.

**2. No write path, no outbound call.**

Both endpoints are read-only audit instruments. They must not create, modify or delete
configuration, and they must not open a network connection to anything. Contributions that
add either are out of scope for this project.

## Practical constraints

Each script is a single file, because a ScriptRunner custom endpoint is pasted as one
file. That rules out the obvious refactor of splitting it into modules. Helper classes
live in the same file and are deliberately kept free of Jira and Confluence types so they
stay testable offline.

Keep the Jira script **javax / jakarta neutral**. It must not gain a `javax.*` or
`jakarta.*` import. The JAX-RS `Response` class is resolved at runtime for exactly this
reason, so that one file runs on ScriptRunner 8.x through 10.x and later.

Keep comments and identifiers in English.

## Before opening a pull request

1. Run the parse check. It compiles the file to the CONVERSION phase, which catches syntax
   errors without needing Jira or Confluence on the classpath:

   ```bash
   java -Dfile.encoding=UTF-8 -cp groovy-3.0.21.jar groovy.ui.GroovyMain \
        tools/parsecheck.groovy jira/jiraDCappFootprint.groovy
   ```

   A green run prints `PARSE OK`.

2. Run the offline test suite if you touched the Jira script. See
   [`jira/tests/README.md`](jira/tests/README.md).

3. Say in the pull request which instance you tested against, including the product
   version and the ScriptRunner version. "Builds fine" is not a test result for a script
   whose entire purpose is measurement.

4. If your change alters what a number means, update the counting semantics section of the
   README in the same pull request. A stale explanation of a number is worse than no
   explanation.

## Reporting a wrong number

That is the most valuable issue you can open. Please include the product and ScriptRunner
version, the exact query parameters you used, what the report showed, and what you believe
the correct value is and how you established it. A screenshot of the cell helps.

Do not paste customer data. An anonymised app key and a count are enough.
