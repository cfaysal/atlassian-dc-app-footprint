/* =============================================================================
 * Confluence Data Center - where does ScriptRunner keep its macro registry?
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Version 0.2
 *
 * WHY THIS EXISTS
 *   The app footprint report queries the content index once per macro name it
 *   knows before the scan. Names come from plugin module descriptors and from the
 *   instance-wide macro catalogue. Neither carries a macro BODY: verified against
 *   the Confluence javadoc, MacroMetadata exposes name, plugin key, aliases,
 *   categories, title, description, icon and hidden, and nothing that returns a
 *   template or source.
 *
 *   The macro code of an app that builds its macros at runtime therefore lives in
 *   that app's own store. For ScriptRunner the SHAPE of that store is known from
 *   its own registry export, read on a customer instance without opening a single
 *   value: 17 macros, one JSON entry each, and a two-slot field named
 *   FIELD_SCRIPT_FILE_OR_SCRIPT. Slot 0 held multi-line code for 11 of them, 1543
 *   to 7187 characters, never ending in .groovy. Slot 1 held a path for the other
 *   6, always ending in .groovy, never multi-line.
 *
 *   Where any of it sits on a running instance is what this probe measures. An
 *   export format is not a storage layout.
 *
 * WHY THE MARKER AND NOT A TABLE PREFIX
 *   An Active Objects prefix is a hash over the owning plugin key and is not
 *   stable across instances. A published list of prefixes is a hint about one
 *   instance at one version, never a locator for the one in front of you. So this
 *   probe does not look for a name it expects. It looks for a value the
 *   administrator knows belongs to their own configuration and reports where that
 *   value actually sits. The prefix, once found, is an output and not an input.
 *
 * WHAT 0.1 GOT WRONG, measured on an Oracle instance
 *   0.1 compared with CAST(column AS VARCHAR(4000)) LIKE ?. On Oracle that throws
 *   for CLOB and NVARCHAR2 columns, and 0.1 swallowed the throw per column. A
 *   failed comparison and a genuine miss both came out as an empty hit list, so a
 *   run in which every single comparison failed was indistinguishable from a run
 *   that searched everything and found nothing. That is the exact failure this
 *   whole tool exists to prevent, and 0.1 shipped it.
 *   0.2 drops the cast, selects comparable columns from the catalogue by JDBC type
 *   instead, and counts and reports every failed comparison. A run now states how
 *   many comparisons it actually made.
 *
 * WHAT IT REFUSES TO DO
 *   It never prints a cell value. Table names, column names, counts and a
 *   yes-or-no on the marker, nothing else. Macro bodies and plugin configuration
 *   routinely carry credentials, internal addresses and SQL, and a probe that
 *   solved a locating problem by dumping the store would have created a worse one.
 *
 * COST, because this runs against production
 *   Tables with no rows are not searched at all, and only character-typed columns
 *   are compared. A time budget bounds the rest. Every one of those three is
 *   reported when it bites, so a short run never reads like a complete one.
 *
 * REPORTING DISCIPLINE
 *   Per item, never one verdict for the run. A failed read carries its reason and
 *   travels with the result rather than only into a log. Two controls come first:
 *   if the executor cannot be acquired, or the catalogue returns no table at all,
 *   then nothing below is a finding and the run says so. An empty result is not
 *   evidence of absence.
 *
 * PARAMETERS
 *   marker    The identifier to locate, taken by the administrator from their own
 *             ScriptRunner export: a macro entry id, a macro key or a macro name.
 *             Without it the probe inventories and cannot point at anything.
 *   verbose   true to emit every candidate table. Default false, which emits only
 *             the tables that produced a hit or a failed comparison. The summary
 *             is always complete either way.
 *   budgetMs  Time budget for the marker search. Default 60000. 0 means unlimited.
 *   tables    Cap on candidate tables examined. Default 2000.
 *
 * PLATFORM
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameters are untyped, so no jakarta.* or javax.* import appears.
 *   Every type under test is loaded by name, so this file cannot fail to start
 *   because of a package that is absent on the instance.
 * ========================================================================== */

import com.atlassian.sal.api.component.ComponentLocator
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

@BaseScript CustomEndpointDelegate delegate

class RegistryProbe {

    static final String VERSION = "0.2"

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"

    /* Every Active Objects table is a candidate. Filtered in Groovy rather than
     * passed to getTables as a pattern: the underscore is a single-character
     * wildcard in a catalogue pattern and the escape character is whatever
     * getSearchStringEscape returns for the driver in front of us, so a
     * hard-coded escape works on one database and quietly over-matches on
     * another. */
    static final String AO_PREFIX = "AO_"

    /* High enough that a Confluence schema plus its Active Objects tables fits
     * whole. It is still a cap, so the run reports when it bites. */
    static final int NAME_CAP = 4000
    static final int COLUMN_CAP = 60

    /* The JDBC types a LIKE can be applied to without a cast. Anything else is
     * skipped as not comparable, which is a stated outcome and not a failure. */
    static final Set<Integer> TEXT_TYPES = new HashSet<Integer>([
        Integer.valueOf(Types.CHAR), Integer.valueOf(Types.VARCHAR),
        Integer.valueOf(Types.LONGVARCHAR), Integer.valueOf(Types.CLOB),
        Integer.valueOf(Types.NCHAR), Integer.valueOf(Types.NVARCHAR),
        Integer.valueOf(Types.LONGNVARCHAR), Integer.valueOf(Types.NCLOB)
    ])

    static Class resolveResponseClass() {
        try {
            return Class.forName("jakarta.ws.rs.core.Response")
        } catch (ClassNotFoundException ignored) {
            return Class.forName("javax.ws.rs.core.Response")
        }
    }

    static Object duck(Object target, String method, Object[] arguments) {
        return InvokerHelper.invokeMethod(target, method, arguments)
    }

    static Object ok(Class responseClass, String entity, String contentType) {
        Object builder = duck(responseClass, "ok", [entity] as Object[])
        builder = duck(builder, "type", [contentType] as Object[])
        return duck(builder, "build", new Object[0])
    }

    /* Class plus message, clamped. A stack trace would carry server paths into a
     * response an administrator may paste somewhere. */
    static String why(Throwable error) {
        if (error == null) {
            return null
        }
        String message = error.getMessage()
        String detail = error.getClass().getSimpleName() + (message == null ? "" : ": " + message)
        return detail.length() > 300 ? detail.substring(0, 300) + " [clamped]" : detail
    }

    static String param(Object queryParams, String name, String fallback) {
        try {
            Object value = duck(queryParams, "getFirst", [name] as Object[])
            String text = (value == null) ? null : value.toString().trim()
            return (text == null || text.isEmpty()) ? fallback : text
        } catch (Throwable ignored) {
            return fallback
        }
    }

    static long longParam(Object queryParams, String name, long fallback, long cap) {
        try {
            long parsed = Long.parseLong(param(queryParams, name, String.valueOf(fallback)))
            return parsed < 0L ? fallback : (parsed > cap ? cap : parsed)
        } catch (Throwable ignored) {
            return fallback
        }
    }

    /* The callback interface is loaded by name and implemented with a JDK proxy,
     * so this file never names a SAL rdbms type statically. That is not style: a
     * statically named component of this family reaches a ScriptRunner endpoint as
     * a Spring proxy the chaining classloader cannot use, which is what broke the
     * sibling tool's export picker twice. */
    static Object withConnection(Object executorFactory, Closure body) {
        Class callbackType = Class.forName(CONNECTION_CALLBACK)
        Object executor = duck(executorFactory, "createReadOnly", new Object[0])
        Object callback = Proxy.newProxyInstance(
            callbackType.getClassLoader(), [callbackType] as Class[],
            new InvocationHandler() {
                Object invoke(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName()
                    if (name == "execute") {
                        return body.call(arguments[0])
                    }
                    if (name == "toString") {
                        return "scriptRunnerRegistryProbe-callback"
                    }
                    if (name == "hashCode") {
                        return Integer.valueOf(System.identityHashCode(proxy))
                    }
                    if (name == "equals") {
                        return Boolean.valueOf(proxy.is(arguments[0]))
                    }
                    return null
                }
            })
        return duck(executor, "execute", [callback] as Object[])
    }

    /* An identifier that came out of the catalogue and is re-checked here before it
     * reaches a statement, because a table name cannot be bound as a parameter. */
    static boolean plainIdentifier(String value) {
        return value != null && value ==~ /[A-Za-z0-9_$#]{1,120}/
    }

    static long count(Connection connection, String table) {
        if (!plainIdentifier(table)) {
            return -1L
        }
        PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table)
        try {
            ResultSet results = statement.executeQuery()
            try {
                return results.next() ? results.getLong(1) : -1L
            } finally {
                results.close()
            }
        } finally {
            statement.close()
        }
    }

    /* Column names with their JDBC type. The values behind them are the thing this
     * probe locates and the thing it must not reveal. */
    static List<Map> describe(DatabaseMetaData meta, String table) {
        List<Map> found = new ArrayList<Map>()
        ResultSet results = meta.getColumns(null, null, table, "%")
        try {
            while (results.next() && found.size() < COLUMN_CAP) {
                Map column = new LinkedHashMap()
                column.put("name", results.getString("COLUMN_NAME"))
                column.put("type", Integer.valueOf(results.getInt("DATA_TYPE")))
                found.add(column)
            }
        } finally {
            results.close()
        }
        return found
    }

    /* Whether the marker occurs, never what sits next to it.
     *
     * No cast. 0.1 wrapped the column in CAST(... AS VARCHAR(4000)) so that any
     * column could be compared, and on Oracle that throws for CLOB and NVARCHAR2.
     * Comparable columns are selected by JDBC type instead, and the marker is
     * bound rather than written into the statement, so a marker containing SQL is
     * data and a marker compared against an NVARCHAR2 column is not a literal
     * type mismatch. */
    static boolean holdsMarker(Connection connection, String table, String column, String marker) {
        PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE ?")
        try {
            statement.setString(1, "%" + marker + "%")
            ResultSet results = statement.executeQuery()
            try {
                return results.next() && results.getLong(1) > 0L
            } finally {
                results.close()
            }
        } finally {
            statement.close()
        }
    }

    static Map run(String marker, boolean verbose, long budgetMs, int tableCap) {
        boolean searching = marker != null && !marker.isEmpty()

        Map out = new LinkedHashMap()
        out.put("probe", "scriptRunnerRegistryProbe")
        out.put("version", VERSION)
        out.put("markerSupplied", Boolean.valueOf(searching))

        /* Reserved up front so the summary reads before the candidate list rather
         * than after it. A later put on the same key keeps this position. */
        out.put("markerHits", null)
        out.put("comparisonsMade", null)
        out.put("comparisonsFailed", null)
        out.put("tablesSearched", null)
        out.put("tablesSkippedEmpty", null)
        out.put("budgetExhausted", null)

        /* ---- control 1: the executor ---------------------------------------- */
        Object factory = null
        try {
            factory = ComponentLocator.getComponent(Class.forName(EXECUTOR_FACTORY))
        } catch (Throwable error) {
            out.put("executorAcquired", Boolean.FALSE)
            out.put("failure", "The read-only executor factory could not be obtained: " + why(error) +
                ". No statement was attempted and nothing below is a finding.")
            return out
        }
        if (factory == null) {
            out.put("executorAcquired", Boolean.FALSE)
            out.put("failure", "The read-only executor factory resolved but no component was returned. " +
                "No statement was attempted and nothing below is a finding.")
            return out
        }
        out.put("executorAcquired", Boolean.TRUE)

        List<String> hits = new ArrayList<String>()
        int[] tally = [0, 0, 0, 0] as int[]   /* made, failed, searched, skippedEmpty */
        boolean[] exhausted = [false] as boolean[]
        long deadline = budgetMs <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() + budgetMs

        try {
            withConnection(factory) { Connection connection ->
                DatabaseMetaData meta = connection.getMetaData()
                out.put("database", meta.getDatabaseProductName())

                /* ---- control 2: the catalogue answers ------------------------ */
                List<String> allTables = new ArrayList<String>()
                ResultSet tables = meta.getTables(null, null, "%", ["TABLE"] as String[])
                try {
                    while (tables.next() && allTables.size() < NAME_CAP) {
                        allTables.add(tables.getString("TABLE_NAME"))
                    }
                } finally {
                    tables.close()
                }
                out.put("tablesVisible", Integer.valueOf(allTables.size()))
                out.put("tablesTruncated", Boolean.valueOf(allTables.size() >= NAME_CAP))
                if (allTables.isEmpty()) {
                    out.put("failure", "The catalogue returned no table at all. The read path itself is " +
                        "suspect, so an empty candidate list below means nothing.")
                    return null
                }

                List<String> aoNames = new ArrayList<String>()
                for (String name : allTables) {
                    if (name != null && name.toUpperCase(Locale.ROOT).startsWith(AO_PREFIX)) {
                        aoNames.add(name)
                    }
                }
                out.put("activeObjectsTables", Integer.valueOf(aoNames.size()))
                out.put("candidatesTruncated", Boolean.valueOf(aoNames.size() > tableCap))

                List<Map> reported = new ArrayList<Map>()
                int examined = 0
                for (String name : aoNames) {
                    if (examined >= tableCap) {
                        break
                    }
                    examined++

                    Map entry = new LinkedHashMap()
                    entry.put("table", name)
                    boolean interesting = false

                    long rows = -1L
                    try {
                        rows = count(connection, name)
                        entry.put("rows", Long.valueOf(rows))
                    } catch (Throwable error) {
                        entry.put("rows", null)
                        entry.put("rowsFailure", why(error))
                        interesting = true
                    }

                    List<Map> described = new ArrayList<Map>()
                    try {
                        described = describe(meta, name)
                    } catch (Throwable error) {
                        entry.put("columnsFailure", why(error))
                        interesting = true
                    }
                    List<String> columnNames = new ArrayList<String>()
                    List<String> comparable = new ArrayList<String>()
                    for (Map column : described) {
                        columnNames.add((String) column.get("name"))
                        if (TEXT_TYPES.contains(column.get("type")) &&
                            plainIdentifier((String) column.get("name"))) {
                            comparable.add((String) column.get("name"))
                        }
                    }
                    entry.put("columns", columnNames)
                    entry.put("comparableColumns", Integer.valueOf(comparable.size()))

                    /* An empty table cannot hold the marker, so searching it buys
                     * nothing and costs a statement per column against production. */
                    if (searching && rows == 0L) {
                        tally[3]++
                        entry.put("markerSearch", "skipped, table is empty")
                    } else if (searching) {
                        if (System.currentTimeMillis() > deadline) {
                            exhausted[0] = true
                            entry.put("markerSearch", "not measured, time budget exhausted")
                            interesting = true
                        } else {
                            tally[2]++
                            List<String> found = new ArrayList<String>()
                            int failed = 0
                            String sample = null
                            for (String column : comparable) {
                                if (System.currentTimeMillis() > deadline) {
                                    exhausted[0] = true
                                    break
                                }
                                try {
                                    tally[0]++
                                    if (holdsMarker(connection, name, column, marker)) {
                                        found.add(column)
                                        hits.add(name + "." + column)
                                    }
                                } catch (Throwable error) {
                                    /* Counted and named, never swallowed. A comparison that
                                     * could not run is not a miss, and 0.1 could not tell the
                                     * two apart. */
                                    failed++
                                    tally[1]++
                                    if (sample == null) {
                                        sample = column + ": " + why(error)
                                    }
                                }
                            }
                            entry.put("markerInColumns", found)
                            entry.put("comparisonsFailed", Integer.valueOf(failed))
                            if (sample != null) {
                                entry.put("firstFailure", sample)
                            }
                            if (!found.isEmpty() || failed > 0) {
                                interesting = true
                            }
                        }
                    }

                    if (verbose || interesting) {
                        reported.add(entry)
                    }
                }

                out.put("candidatesExamined", Integer.valueOf(examined))
                out.put("candidatesReported", Integer.valueOf(reported.size()))
                out.put("candidates", reported)

                /* ---- the other plausible store ------------------------------- */
                Map settings = new LinkedHashMap()
                try {
                    settings.put("rows", Long.valueOf(count(connection, "PLUGIN_SETTING")))
                    if (searching) {
                        List<String> found = new ArrayList<String>()
                        List<String> failures = new ArrayList<String>()
                        for (Map column : describe(meta, "PLUGIN_SETTING")) {
                            String columnName = (String) column.get("name")
                            if (!TEXT_TYPES.contains(column.get("type")) || !plainIdentifier(columnName)) {
                                continue
                            }
                            try {
                                tally[0]++
                                if (holdsMarker(connection, "PLUGIN_SETTING", columnName, marker)) {
                                    found.add(columnName)
                                    hits.add("PLUGIN_SETTING." + columnName)
                                }
                            } catch (Throwable error) {
                                tally[1]++
                                failures.add(columnName + ": " + why(error))
                            }
                        }
                        settings.put("markerInColumns", found)
                        settings.put("failures", failures)
                    }
                } catch (Throwable error) {
                    settings.put("failure", why(error))
                }
                out.put("pluginSetting", settings)
                return null
            }
        } catch (Throwable error) {
            out.put("failure", "The read failed after the executor was acquired: " + why(error) +
                ". Any partial result above is exactly that and no absence is established.")
        }

        out.put("markerHits", hits)
        out.put("comparisonsMade", Integer.valueOf(tally[0]))
        out.put("comparisonsFailed", Integer.valueOf(tally[1]))
        out.put("tablesSearched", Integer.valueOf(tally[2]))
        out.put("tablesSkippedEmpty", Integer.valueOf(tally[3]))
        out.put("budgetExhausted", Boolean.valueOf(exhausted[0]))

        /* The sentence that keeps a reader from turning an empty hit list into a
         * conclusion. It is emitted by the probe rather than left to the reader,
         * because 0.1 proved the reader will not supply it. */
        if (searching && hits.isEmpty()) {
            out.put("verdict", tally[0] == 0
                ? "NOT SEARCHED. No comparison ran at all, so this says nothing about where the marker is."
                : (tally[1] > 0
                    ? "INCOMPLETE. " + tally[1] + " of " + tally[0] + " comparisons failed, so a miss is " +
                      "not established. Read firstFailure on the affected tables."
                    : "SEARCHED, NO HIT in " + tally[0] + " comparisons across " + tally[2] + " non-empty " +
                      "tables. Absence still holds only for the columns a LIKE can be applied to."))
        } else if (searching) {
            out.put("verdict", "HIT. See markerHits.")
        }

        return out
    }
}

scriptRunnerRegistryProbe(httpMethod: "GET", groups: ["confluence-administrators"]) { queryParams, body ->
    Class responseClass = RegistryProbe.resolveResponseClass()
    String marker = RegistryProbe.param(queryParams, "marker", "")
    boolean verbose = "true".equalsIgnoreCase(RegistryProbe.param(queryParams, "verbose", "false"))
    long budgetMs = RegistryProbe.longParam(queryParams, "budgetMs", 60000L, 900000L)
    int tableCap = (int) RegistryProbe.longParam(queryParams, "tables", 2000L, 10000L)
    Map result = RegistryProbe.run(marker, verbose, budgetMs, tableCap)
    return RegistryProbe.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(result)),
                            "application/json; charset=UTF-8")
}
