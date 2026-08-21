
/* ===========================================================================
 * Offline harness for the Jira-free parts of jiraDCappFootprint.groovy.
 * The class definitions are prepended verbatim from the real file.
 * ======================================================================== */

int passed = 0
int failed = 0
List<String> failures = []

def check = { String name, Object actual, Object expected ->
    if (actual == expected) {
        passed++
    } else {
        failed++
        failures << (name + "\n     expected: " + expected + "\n     actual  : " + actual)
    }
}

def ok = { String name, boolean condition ->
    if (condition) {
        passed++
    } else {
        failed++
        failures << name
    }
}

/* ---- 1. html escaping ---------------------------------------------------- */

check("html null", Fp.html(null), "")
check("html plain", Fp.html("abc"), "abc")
check("html all", Fp.html("<a href=\"x\">&'</a>"),
    "&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;")
check("html ampersand first", Fp.html("&lt;"), "&amp;lt;")
check("html number", Fp.html(42), "42")

/* ---- 2. number formatting ------------------------------------------------ */

check("number null", Fp.number(null, Locale.GERMANY), Fp.NA)
check("number de", Fp.number(1234567L, Locale.GERMANY), "1.234.567")
check("number en", Fp.number(1234567L, Locale.ENGLISH), "1,234,567")
check("number int", Fp.number(0, Locale.ENGLISH), "0")

/* ---- 3. csv quoting ------------------------------------------------------ */

check("csv null", Fp.csv(null), "\"\"")
check("csv quote", Fp.csv("say \"hi\", ok"), "\"say \"\"hi\"\", ok\"")

/* ---- 4. parameters ------------------------------------------------------- */

class FakeParams {
    Map<String, String> values = [:]
    Object getFirst(String name) { return values.get(name) }
}

FakeParams params = new FakeParams()
params.values = [flag: "TRUE", off: "0", junk: "maybe", blank: "   ", n: "500", neg: "-5"]

check("boolean true", Fp.booleanParam(params, "flag", false), true)
check("boolean zero", Fp.booleanParam(params, "off", true), false)
check("boolean junk keeps default", Fp.booleanParam(params, "junk", true), true)
check("boolean missing", Fp.booleanParam(params, "nope", true), true)
check("boolean blank keeps default", Fp.booleanParam(params, "blank", true), true)
check("string default", Fp.stringParam(params, "nope", "html"), "html")
check("string trims", Fp.stringParam(params, "n", "x"), "500")
check("long parse", Fp.longParam(params, "n", 1L), 500L)
check("long negative keeps default", Fp.longParam(params, "neg", 7L), 7L)
check("long junk keeps default", Fp.longParam(params, "junk", 9L), 9L)
check("null params", Fp.stringParam(null, "x", "d"), "d")

/* ---- 5. link building ---------------------------------------------------- */

Map<String, Object> base = [format: null, includeSystem: "true", includeDrafts: null] as LinkedHashMap
check("link keeps set values", Fp.link(base, null), "?includeSystem=true")
check("link override adds", Fp.link(base, [format: "json"]), "?format=json&includeSystem=true")
check("link override removes", Fp.link(base, [includeSystem: null]), "?")
check("link encodes", Fp.link([q: "a b&c"] as LinkedHashMap, null), "?q=a+b%26c")

/* ---- 6. category heuristic ----------------------------------------------- */

Map<String, String> categoryCases = [
    "WebResourceModuleDescriptor": "UI",
    "WebResourceTransformerModuleDescriptor": "UI",
    "WebItemModuleDescriptor": "UI",
    "IssueTabPanelModuleDescriptor": "UI",
    "ProjectTabPanelModuleDescriptor": "UI",
    "RestModuleDescriptor": "REST / API",
    "ResourceDescriptor": "REST / API",
    "CustomFieldTypeModuleDescriptor": "Custom Fields",
    "CustomFieldSearcherModuleDescriptor": "Custom Fields",
    "WorkflowConditionModuleDescriptor": "Workflow",
    "WorkflowPostFunctionModuleDescriptor": "Workflow",
    "JqlFunctionModuleDescriptor": "JQL / Search",
    "ServletModuleDescriptor": "HTTP / Servlet",
    "ServletFilterModuleDescriptor": "HTTP / Servlet",
    "EventListenerModuleDescriptor": "Events / Listeners",
    "ScheduledJobModuleDescriptor": "Jobs / Services",
    "GadgetModuleDescriptor": "Reports / Dashboards",
    "ReportModuleDescriptor": "Reports / Dashboards",
    "ProjectPermissionModuleDescriptor": "Permissions / Security",
    "ProjectRoleModuleDescriptor": "Project",
    "IssueOperationModuleDescriptor": "Issue",
    "ComponentImportModuleDescriptor": "Other",
    "SomethingElse": "Other"
]
categoryCases.each { descriptor, expected ->
    check("category " + descriptor, Fp.extensionCategory(descriptor), expected)
}
check("category null", Fp.extensionCategory(null), "Other")

/* the regression this fixes: WebResource must not land in REST / API */
ok("regression WebResource not REST", Fp.extensionCategory("WebResourceModuleDescriptor") != "REST / API")

/* ---- 7. tokenizer equivalence -------------------------------------------- */

Random random = new Random(20260821L)
List<String> alphabet = ["com", "atlassian", "jira", "plugin", "onresolve", "workflow",
                         "Function", "Condition", "a", "ab", "abab", "x-y", "z_1", "Q\$inner"]

String randomToken(Random rnd, List<String> parts) {
    int pieces = 1 + rnd.nextInt(4)
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < pieces; i++) {
        if (i > 0) {
            sb.append(".")
        }
        sb.append(parts.get(rnd.nextInt(parts.size())))
    }
    return sb.toString()
}

int equivalenceCases = 0
for (int round = 0; round < 400; round++) {

    StringBuilder xml = new StringBuilder("<workflow>")
    List<String> used = []
    int elements = 5 + random.nextInt(40)
    for (int i = 0; i < elements; i++) {
        String token = randomToken(random, alphabet)
        used << token
        xml.append("<arg name=\"class.name\">").append(token).append("</arg>\n")
        if (random.nextInt(4) == 0) {
            xml.append("<!-- ").append(token).append(" mentioned twice -->\n")
        }
    }
    xml.append("</workflow>")
    String text = xml.toString()

    Map<String, Integer> tokens = Fp.tokenize(text)
    String blob = Fp.blob(tokens)

    List<String> needles = []
    needles.addAll(used.unique().take(12))
    needles << randomToken(random, alphabet)
    needles << "com.atlassian"
    needles << "abab"
    needles << "aaaa"
    needles << "a"
    needles << "arg name"
    needles << "class.name"
    needles << "<arg"
    needles << ""
    needles << "Q\$inner"

    for (String needle : needles) {
        int naive = Fp.occurrences(text, needle)
        int fast = Fp.countIn(tokens, blob, text, needle)
        equivalenceCases++
        if (naive != fast) {
            failed++
            failures << ("tokenizer mismatch for needle '" + needle + "': naive=" + naive + " fast=" + fast)
            break
        }
    }
}
passed++
println "tokenizer equivalence cases: " + equivalenceCases

/* explicit self-overlap check */
check("overlap naive", Fp.occurrences("abababab", "abab"), 2)
Map<String, Integer> ovTokens = Fp.tokenize("abababab")
check("overlap fast", Fp.countIn(ovTokens, Fp.blob(ovTokens), "abababab", "abab"), 2)

/* needle with a non-token character falls back to the raw scan */
String mixed = "aaa:bbb aaa:bbb"
Map<String, Integer> mixedTokens = Fp.tokenize(mixed)
check("non-token needle", Fp.countIn(mixedTokens, Fp.blob(mixedTokens), mixed, "aaa:bbb"),
    Fp.occurrences(mixed, "aaa:bbb"))

/* short needle falls back to the raw scan */
check("short needle", Fp.countIn(mixedTokens, Fp.blob(mixedTokens), mixed, "aa"),
    Fp.occurrences(mixed, "aa"))

check("tokenSafe key", Fp.tokenSafe("com.onresolve.jira.groovy.groovyrunner"), true)
check("tokenSafe colon", Fp.tokenSafe("com.foo:bar"), false)
check("tokenSafe empty", Fp.tokenSafe(""), false)

/* ---- 8. aggregation ------------------------------------------------------ */

AppFootprint app = new AppFootprint()
app.pluginKey = "com.example.app"
app.displayName = "Example"

["WebResourceModuleDescriptor", "WebResourceModuleDescriptor", "RestModuleDescriptor",
 "CustomFieldTypeModuleDescriptor"].each { String descriptorName ->
    AppModuleInfo module = new AppModuleInfo()
    module.descriptorName = descriptorName
    module.category = Fp.extensionCategory(descriptorName)
    module.enabled = Boolean.TRUE
    app.modules << module
}
AppModuleInfo disabledModule = new AppModuleInfo()
disabledModule.descriptorName = "RestModuleDescriptor"
disabledModule.category = "REST / API"
disabledModule.enabled = Boolean.FALSE
app.modules << disabledModule

CustomFieldFootprint measured = new CustomFieldFootprint()
measured.name = "Measured"
measured.issuesWithValue = 100L
measured.issuesWithValueState = Fp.MEASURED
ScreenPlacementInfo p1 = new ScreenPlacementInfo(screenId: 1L, tabId: 10L)
ScreenPlacementInfo p2 = new ScreenPlacementInfo(screenId: 1L, tabId: 11L)
ScreenPlacementInfo p3 = new ScreenPlacementInfo(screenId: 2L, tabId: 12L)
measured.screenPlacements.addAll([p1, p2, p3])

CustomFieldFootprint skipped = new CustomFieldFootprint()
skipped.name = "Skipped"
skipped.issuesWithValueState = Fp.BUDGET
skipped.diagnostics << "screen placements -> RuntimeException: boom"

app.customFields.addAll([measured, skipped])

WorkflowReference activeReference = new WorkflowReference(
    name: "WF A", active: Boolean.TRUE, keyReferences: 3, references: 3, detection: "Plugin key")
WorkflowReference inactiveReference = new WorkflowReference(
    name: "WF B", active: Boolean.FALSE, keyReferences: 0, classReferences: 2, references: 2,
    detection: "Module class")
app.workflowReferences.addAll([activeReference, inactiveReference])
app.diagnostics << "plugin state -> IllegalStateException: nope"

app.finish()

check("enabled modules", app.enabledModuleCount, 4)
check("category UI", app.categoryCounts.get("UI"), 2)
check("category REST", app.categoryCounts.get("REST / API"), 1)
check("module type order", new ArrayList<String>(app.moduleTypeCounts.keySet()),
    ["WebResourceModuleDescriptor", "CustomFieldTypeModuleDescriptor", "RestModuleDescriptor"])
check("issue associations", app.issueFieldAssociations, 100L)
ok("issue associations partial", app.issueFieldAssociationsPartial)
check("screen placements", app.screenPlacements, 3)
check("unique screens", app.uniqueScreens, 2)
check("unique screens per field", measured.getUniqueScreenCount(), 2)
check("workflow count", app.workflowCount, 2)
check("active workflows", app.activeWorkflowCount, 1)
check("workflow references", app.workflowReferenceCount, 5)
check("footprint signals", app.footprintSignals, 2 + 2 + 3)
ok("detected", app.detected)
check("diagnostic count", app.diagnosticCount, 2)

Map<String, Object> appMap = app.asMap(false)
ok("asMap has footprint", appMap.containsKey("footprint"))
ok("asMap hides modules", !appMap.containsKey("modules"))
check("asMap partial flag", appMap.get("footprint").get("issueFieldAssociationsPartial"), true)
ok("asMap with modules", app.asMap(true).containsKey("modules"))

AppFootprint empty = new AppFootprint()
empty.finish()
ok("empty app not detected", !empty.detected)
ok("empty app not partial", !empty.issueFieldAssociationsPartial)
check("empty app signals", empty.footprintSignals, 0)

/* ---- 9. diagnostics note -------------------------------------------------- */

List<String> sink = []
Fp.note(sink, "context", new IllegalStateException("boom"))
check("note format", sink[0], "context -> IllegalStateException: boom")
Fp.note(sink, "context", null)
check("note null error", sink[1], "context -> unknown error")
Fp.note(sink, "long", new RuntimeException("x" * 400))
ok("note truncated", sink[2].length() <= 220)

/* ---- 10. reject helpers --------------------------------------------------- */

Map<String, Integer> rejectTokens = Fp.tokenize("com.vendor.product.SomeFunction and more.tokens.here")
String rejectBlob = Fp.blob(rejectTokens)
check("mayOccur present", Fp.mayOccur(rejectBlob, "com.vendor.product.SomeFunction"), true)
check("mayOccur substring", Fp.mayOccur(rejectBlob, "vendor.product"), true)
check("mayOccur absent", Fp.mayOccur(rejectBlob, "com.other.plugin.key"), false)
check("mayOccur short is inconclusive", Fp.mayOccur(rejectBlob, "zz"), true)
check("mayOccur unsafe is inconclusive", Fp.mayOccur(rejectBlob, "a:b:c"), true)
check("mayOccur empty", Fp.mayOccur(rejectBlob, ""), false)

Map<String, Integer> mergedTokens = Fp.mergeTokens([
    ["alpha.token": 2, "beta.token": 1],
    ["alpha.token": 3, "gamma.token": 5]
])
check("merge sums", mergedTokens.get("alpha.token"), 5)
check("merge keeps", mergedTokens.get("gamma.token"), 5)
check("merge size", mergedTokens.size(), 3)

/* ---- 11. head to head: original algorithm versus three-stage lookup -------- */

int workflowCount = 100
int appCount = 100
int classesPerApp = 30

List<String> sharedVocabulary = []
for (int i = 0; i < 60; i++) {
    sharedVocabulary << ("com.atlassian.jira.workflow.function.issue.Standard" + i + "Function")
}

List<List<String>> appClasses = []
List<String> appKeys = []
for (int a = 0; a < appCount; a++) {
    appKeys << ("com.vendor" + a + ".product.plugin")
    List<String> classes = []
    for (int c = 0; c < classesPerApp; c++) {
        classes << ("com.vendor" + a + ".product.module.Handler" + c)
    }
    appClasses << classes
}

/* only the first eight apps actually appear in any descriptor */
List<String> presentStrings = []
for (int a = 0; a < 8; a++) {
    presentStrings << appKeys[a]
    presentStrings.addAll(appClasses[a].take(5))
}

List<String> workflowXmls = []
Random benchRandom = new Random(7L)
for (int w = 0; w < workflowCount; w++) {
    StringBuilder wf = new StringBuilder(140000)
    wf.append("<workflow name=\"WF").append(w).append("\">\n")
    for (int step = 0; step < 900; step++) {
        wf.append("  <step id=\"").append(step).append("\" name=\"Step ").append(step).append("\">\n")
        wf.append("    <meta name=\"jira.status.id\">").append(step % 12).append("</meta>\n")
        wf.append("    <function type=\"class\"><arg name=\"class.name\">")
        wf.append(sharedVocabulary.get(step % sharedVocabulary.size()))
        wf.append("</arg></function>\n")
        if (step % 90 == 0) {
            wf.append("    <function type=\"class\"><arg name=\"class.name\">")
            wf.append(presentStrings.get(benchRandom.nextInt(presentStrings.size())))
            wf.append("</arg></function>\n")
        }
        wf.append("  </step>\n")
    }
    wf.append("</workflow>")
    workflowXmls << wf.toString()
}

long totalXmlChars = workflowXmls.sum { it.length() }

/* baseline: exactly what version 2.1 did */
long tOld = System.nanoTime()
Map<String, Integer> baseline = [:]
for (int a = 0; a < appCount; a++) {
    int total = 0
    for (String xml : workflowXmls) {
        int keyHits = Fp.occurrences(xml, appKeys[a])
        if (keyHits > 0) {
            total += keyHits
        } else {
            for (String cls : appClasses[a]) {
                total += Fp.occurrences(xml, cls)
            }
        }
    }
    baseline.put(appKeys[a], total)
}
long oldMs = (System.nanoTime() - tOld) / 1000000L

/* version 3.0: build the index once, then reject globally, per workflow, count */
long tNew = System.nanoTime()
List<Map<String, Integer>> tokenMaps = workflowXmls.collect { Fp.tokenize(it) }
List<String> blobs = tokenMaps.collect { Fp.blob(it) }
Map<String, Integer> globalTokens = Fp.mergeTokens(tokenMaps)
String globalBlob = Fp.blob(globalTokens)
long indexMs = (System.nanoTime() - tNew) / 1000000L

Map<String, Integer> improved = [:]
for (int a = 0; a < appCount; a++) {
    int total = 0
    boolean keyPossible = Fp.mayOccur(globalBlob, appKeys[a])
    List<String> possible = appClasses[a].findAll { Fp.mayOccur(globalBlob, it) }
    if (keyPossible || !possible.isEmpty()) {
        for (int w = 0; w < workflowXmls.size(); w++) {
            int keyHits = keyPossible ?
                Fp.countIn(tokenMaps[w], blobs[w], workflowXmls[w], appKeys[a]) : 0
            if (keyHits > 0) {
                total += keyHits
            } else {
                for (String cls : possible) {
                    total += Fp.countIn(tokenMaps[w], blobs[w], workflowXmls[w], cls)
                }
            }
        }
    }
    improved.put(appKeys[a], total)
}
long newMs = (System.nanoTime() - tNew) / 1000000L

check("head to head identical results", improved, baseline)
ok("head to head found references", baseline.values().sum() > 0)

println "---------------------------------------------------------------"
println "bench  workflows=" + workflowCount + ", xml chars=" + totalXmlChars +
    ", distinct tokens global=" + globalTokens.size()
println "bench  apps=" + appCount + ", module classes per app=" + classesPerApp
println "bench  v2.1 naive scan      : " + oldMs + " ms"
println "bench  v3.0 three-stage     : " + newMs + " ms (index build " + indexMs + " ms)"
println "bench  speedup              : " + (oldMs > 0 && newMs > 0 ? String.format(Locale.ENGLISH, "%.1fx", (oldMs as double) / (newMs as double)) : "n/a")
println "bench  references found     : " + baseline.values().sum()
println "---------------------------------------------------------------"

/* ---- 12. HTTP surface counting -------------------------------------------- */

check("rest modules counted", app.restModules, 1)
check("servlet modules counted", app.servletModules, 0)

AppFootprint surface = new AppFootprint()
["RestModuleDescriptor", "ServletModuleDescriptor", "ServletFilterModuleDescriptor",
 "DownloadableWebResourceModuleDescriptor", "WebItemModuleDescriptor"].each { String name ->
    AppModuleInfo module = new AppModuleInfo()
    module.descriptorName = name
    module.category = Fp.extensionCategory(name)
    module.enabled = Boolean.TRUE
    surface.modules << module
}
AppModuleInfo disabledRest = new AppModuleInfo()
disabledRest.descriptorName = "RestModuleDescriptor"
disabledRest.enabled = Boolean.FALSE
surface.modules << disabledRest
surface.finish()

check("rest surface", surface.restModules, 1)
check("servlet surface", surface.servletModules, 3)

/* ---- 13. blast radius ------------------------------------------------------ */

AppFootprint reach = new AppFootprint()

WorkflowReference wfA = new WorkflowReference(name: "WF A", references: 1, reachState: Fp.MEASURED)
wfA.projectKeys.addAll(["ALPHA", "BETA"])
WorkflowReference wfB = new WorkflowReference(name: "WF B", references: 1, reachState: Fp.MEASURED)
wfB.projectKeys.addAll(["BETA", "GAMMA"])
reach.workflowReferences.addAll([wfA, wfB])

CustomFieldFootprint viaScreen = new CustomFieldFootprint()
viaScreen.name = "Field"
viaScreen.issuesWithValueState = Fp.DISABLED
viaScreen.reachState = Fp.MEASURED
viaScreen.reachProjectKeys.addAll(["ALPHA", "DELTA"])
reach.customFields << viaScreen

Map<String, Long> issuesByProject = [ALPHA: 100L, BETA: 50L, GAMMA: 25L, DELTA: 5L]
reach.finish(issuesByProject)

check("impact union deduplicated", reach.impactedProjectKeys, ["ALPHA", "BETA", "DELTA", "GAMMA"])
check("impact issues counted once per project", reach.impactedIssues, 180L)
check("impact state", reach.impactState, Fp.MEASURED)
ok("impact not partial", !reach.impactPartial)

/* a project without a count makes the total a lower bound, never a silent zero */
Map<String, Long> incomplete = [ALPHA: 100L, BETA: 50L, DELTA: 5L]
reach.finish(incomplete)
check("impact issues lower bound", reach.impactedIssues, 155L)
ok("impact partial when a project is uncounted", reach.impactPartial)

/* a failed path marks the app partial, it does not silently shrink the radius */
WorkflowReference broken = new WorkflowReference(name: "WF C", references: 1, reachState: Fp.ERROR)
reach.workflowReferences << broken
reach.finish(issuesByProject)
ok("impact partial on failed path", reach.impactPartial)
check("impact keeps measured projects", reach.impactedProjectKeys.size(), 4)

/* nothing evaluated at all stays n/e, never zero */
AppFootprint untouched = new AppFootprint()
WorkflowReference unevaluated = new WorkflowReference(name: "WF D", references: 2)
untouched.workflowReferences << unevaluated
untouched.finish(issuesByProject)
check("impact not evaluated", untouched.impactState, Fp.NOT_EVALUATED)
check("impact issues null when not evaluated", untouched.impactedIssues, null)
ok("impact not partial when never evaluated", !untouched.impactPartial)

/* finish() without a count map keeps the project list but no issue number */
reach.finish()
check("impact projects without count map", reach.impactedProjectKeys.size(), 4)
check("impact issues null without count map", reach.impactedIssues, null)

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
