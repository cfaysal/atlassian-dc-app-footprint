/* =============================================================================
 * Confluence Data Center - where does ScriptRunner keep its macro registry?
 * ScriptRunner Custom REST Endpoint. Admin-gated. Strictly read-only.
 *
 * Version 0.1
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
 *   that app's own store, and for ScriptRunner the shape of that store is known
 *   from its own registry export, read on a customer instance without opening a
 *   single value: 17 macros, one JSON entry each, and a two-slot field named
 *   FIELD_SCRIPT_FILE_OR_SCRIPT. Slot 0 held multi-line code for 11 of them,
 *   1543 to 7187 characters, never ending in .groovy. Slot 1 held a path for the
 *   other 6, always ending in .groovy, never multi-line.
 *
 *   What the export does NOT say is where any of that sits while the instance is
 *   running. An export format is not a storage layout. This probe measures it.
 *
 * WHAT IT REFUSES TO DO
 *   It never prints a cell value. Table names, column names, counts and a
 *   yes-or-no on a marker, nothing else. Macro bodies and plugin configuration
 *   routinely carry credentials, internal addresses and SQL, and a probe that
 *   solves a locating problem by dumping the store has created a worse one.
 *
 * REPORTING DISCIPLINE
 *   Per item, never one verdict for the run. A failed read carries its reason and
 *   travels with the result rather than only into a log. Two controls come first:
 *   if the executor cannot be acquired, or the catalogue returns no table at all,
 *   then nothing below is a finding and the run says so. An empty result is not
 *   evidence of absence.
 *
 * PARAMETERS
 *   marker    optional. An identifier the administrator takes from their own
 *             ScriptRunner export, for example a macro entry id. The probe
 *             reports which table and column contains it and nothing about what
 *             sits around it. Without it the probe still lists candidates, it
 *             just cannot point at one.
 *   tables    optional, default 400. Cap on catalogue entries examined.
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

@BaseScript CustomEndpointDelegate delegate

class RegistryProbe {

    static final String VERSION = "0.1"

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"

    /* Candidate tables are not guessed from a vendor prefix. Active Objects names
     * a table after a hash of the owning plugin key, so the prefix belonging to
     * ScriptRunner cannot be derived from its name and has to be recognised in the
     * result. Everything starting AO_ is therefore a candidate, and the marker is
     * what turns a candidate into an answer.
     *
     * Filtered in Groovy rather than passed to getTables as a pattern. The
     * underscore is a single-character wildcard in a catalogue pattern and the
     * escape character is whatever getSearchStringEscape returns for the driver in
     * front of us, so a hard-coded escape would work on one database and quietly
     * over-match on another. */
    static final String AO_PREFIX = "AO_"

    /* High enough that a Confluence schema plus its Active Objects tables fits
     * whole. It is still a cap, so the run reports when it bites instead of
     * letting a truncated list read like a complete one. */
    static final int NAME_CAP = 4000
    static final int COLUMN_CAP = 40

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

    static int intParam(Object queryParams, String name, int fallback, int cap) {
        try {
            int parsed = Integer.parseInt(param(queryParams, name, String.valueOf(fallback)))
            return parsed < 1 ? fallback : (parsed > cap ? cap : parsed)
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

    static long count(Connection connection, String table) {
        /* The table name is a catalogue value and cannot be bound as a parameter.
         * It is therefore accepted only after it has been matched against the
         * catalogue itself and re-checked here: a name that is not a plain
         * identifier never reaches a statement. */
        if (!(table ==~ /[A-Za-z0-9_]{1,120}/)) {
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

    /* Column names only. The values behind them are the thing this probe exists to
     * locate and the thing it must not reveal. */
    static List<String> columns(DatabaseMetaData meta, String table) {
        List<String> names = new ArrayList<String>()
        ResultSet results = meta.getColumns(null, null, table, "%")
        try {
            while (results.next() && names.size() < COLUMN_CAP) {
                names.add(results.getString("COLUMN_NAME"))
            }
        } finally {
            results.close()
        }
        return names
    }

    /* Whether the marker occurs, never what sits next to it. The comparison is a
     * bound LIKE on one column at a time, so a marker containing SQL is data and
     * not syntax. */
    static boolean holdsMarker(Connection connection, String table, String column, String marker) {
        if (!(table ==~ /[A-Za-z0-9_]{1,120}/) || !(column ==~ /[A-Za-z0-9_]{1,120}/)) {
            return false
        }
        PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table + " WHERE CAST(" + column + " AS VARCHAR(4000)) LIKE ?")
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

    static Map run(String marker, int tableCap) {
        Map out = new LinkedHashMap()
        out.put("probe", "scriptRunnerRegistryProbe")
        out.put("version", VERSION)
        out.put("markerSupplied", Boolean.valueOf(marker != null && !marker.isEmpty()))

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

        try {
            withConnection(factory) { Connection connection ->
                DatabaseMetaData meta = connection.getMetaData()
                out.put("database", meta.getDatabaseProductName())

                /* ---- control 2: the catalogue answers --------------------------- */
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

                /* ---- candidates ------------------------------------------------ */
                List<String> aoNames = new ArrayList<String>()
                for (String name : allTables) {
                    if (name != null && name.toUpperCase(Locale.ROOT).startsWith(AO_PREFIX)) {
                        aoNames.add(name)
                    }
                }
                out.put("activeObjectsTables", Integer.valueOf(aoNames.size()))
                out.put("candidatesTruncated", Boolean.valueOf(aoNames.size() > tableCap))

                List<Map> candidates = new ArrayList<Map>()
                for (String name : aoNames) {
                    if (candidates.size() >= tableCap) {
                        break
                    }
                    Map entry = new LinkedHashMap()
                    entry.put("table", name)
                    try {
                        entry.put("rows", Long.valueOf(count(connection, name)))
                    } catch (Throwable error) {
                        entry.put("rows", null)
                        entry.put("rowsFailure", why(error))
                    }
                    List<String> columnNames = new ArrayList<String>()
                    try {
                        columnNames = columns(meta, name)
                        entry.put("columns", columnNames)
                    } catch (Throwable error) {
                        entry.put("columns", null)
                        entry.put("columnsFailure", why(error))
                    }
                    if (marker != null && !marker.isEmpty()) {
                        List<String> hits = new ArrayList<String>()
                        for (String column : columnNames) {
                            try {
                                if (holdsMarker(connection, name, column, marker)) {
                                    hits.add(column)
                                }
                            } catch (Throwable ignored) {
                                /* A column the database refuses to cast to text is not a
                                 * failure of the probe and not a hit either. Silence here
                                 * is correct: the per-table verdict is the hit list, and a
                                 * type mismatch is not evidence about the marker. */
                            }
                        }
                        entry.put("markerInColumns", hits)
                    }
                    candidates.add(entry)
                }
                out.put("candidatesExamined", Integer.valueOf(candidates.size()))
                out.put("candidates", candidates)

                /* ---- the other plausible store --------------------------------- */
                Map settings = new LinkedHashMap()
                try {
                    settings.put("rows", Long.valueOf(count(connection, "PLUGIN_SETTING")))
                    if (marker != null && !marker.isEmpty()) {
                        List<String> hits = new ArrayList<String>()
                        for (String column : ["SETTING_KEY", "SETTING_VALUE", "BANDANA_CONTEXT"]) {
                            try {
                                if (holdsMarker(connection, "PLUGIN_SETTING", column, marker)) {
                                    hits.add(column)
                                }
                            } catch (Throwable ignored) {
                                /* see above */
                            }
                        }
                        settings.put("markerInColumns", hits)
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

        return out
    }
}

scriptRunnerRegistryProbe(httpMethod: "GET", groups: ["confluence-administrators"]) { queryParams, body ->
    Class responseClass = RegistryProbe.resolveResponseClass()
    String marker = RegistryProbe.param(queryParams, "marker", "")
    int tableCap = RegistryProbe.intParam(queryParams, "tables", 400, 2000)
    Map result = RegistryProbe.run(marker, tableCap)
    return RegistryProbe.ok(responseClass, JsonOutput.prettyPrint(JsonOutput.toJson(result)),
                            "application/json; charset=UTF-8")
}
