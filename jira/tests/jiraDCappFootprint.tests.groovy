
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

/* ---- N. space key validation (OP-950 Q1) --------------------------------- */

/* The defect this replaces: the space key ran through cqlTerm(), which strips
   "~", so the personal space "~cfaysal" became the key "cfaysal". Confluence
   answered zero hits and no error, and the mistake was invisible. The sanitiser
   still behaves that way - it is a search-term cleaner and stays one - but the
   space key no longer goes anywhere near it. */
check("cqlTerm still strips the tilde", PageExport.cqlTerm("~cfaysal"), "cfaysal")
check("personal space key survives validation", PageExport.spaceKeyProblem("~cfaysal"), "")
ok("validated key is not the mangled one", PageExport.cqlTerm("~cfaysal") != "~cfaysal")

check("plain space key accepted", PageExport.spaceKeyProblem("DOCS"), "")
check("lower case space key accepted", PageExport.spaceKeyProblem("docs"), "")
check("digits accepted", PageExport.spaceKeyProblem("DOCS2026"), "")
check("underscore, hyphen and dot accepted", PageExport.spaceKeyProblem("A1_B-C.D"), "")
check("user key style personal space accepted", PageExport.spaceKeyProblem("~a1b2c3d4e5f60718"), "")
check("at sign in a personal key accepted", PageExport.spaceKeyProblem("~n@x"), "")
check("surrounding whitespace is trimmed", PageExport.spaceKeyProblem("  ~cfaysal  "), "")

ok("null key refused", !PageExport.spaceKeyProblem(null).isEmpty())
ok("empty key refused", !PageExport.spaceKeyProblem("").isEmpty())
ok("blank key refused", !PageExport.spaceKeyProblem("   ").isEmpty())
ok("bare tilde refused", !PageExport.spaceKeyProblem("~").isEmpty())
ok("inner tilde refused", !PageExport.spaceKeyProblem("a~b").isEmpty())
ok("inner tilde names the tilde rule", PageExport.spaceKeyProblem("a~b").contains("leading tilde"))
ok("whitespace inside refused", !PageExport.spaceKeyProblem("DO CS").isEmpty())
ok("whitespace refusal says so", PageExport.spaceKeyProblem("DO CS").contains("whitespace"))

/* Every character the CQL sanitiser used to strip is refused outright now:
   silently removing one is exactly what produced a search for a space that does
   not exist. CR and LF are checked embedded, because a trailing one never
   reaches the validator - PageExport.str trims on the way in, so the value that
   is validated is the value that is used. */
["*", "?", "\"", "\\"].each { String bad ->
    ok("space key refuses char " + ((int) bad.charAt(0)), !PageExport.spaceKeyProblem("DOCS" + bad).isEmpty())
}
["\n", "\r", "\t"].each { String bad ->
    ok("space key refuses embedded char " + ((int) bad.charAt(0)),
        !PageExport.spaceKeyProblem("DO" + bad + "CS").isEmpty())
    check("space key trims trailing char " + ((int) bad.charAt(0)),
        PageExport.spaceKeyProblem("DOCS" + bad), "")
}
ok("refusal names the offending character", PageExport.spaceKeyProblem("DOCS*").contains("\"*\""))

/* ---- N+1. parent instruction: id or title, never both (OP-950 Q4) -------- */

check("no parent at all is fine", PageExport.parentProblem("", "", "Report"), "")
check("null parent is fine", PageExport.parentProblem(null, null, "Report"), "")
check("only an id is fine", PageExport.parentProblem("12345", "", "Report"), "")
check("only a title is fine", PageExport.parentProblem("", "Team Reports", "Report"), "")
check("id and title together are refused",
    PageExport.parentProblem("12345", "Team Reports", "Report"), PageExport.PARENT_BOTH)
check("both are refused after trimming",
    PageExport.parentProblem("  12345  ", "  Team Reports  ", "Report"), PageExport.PARENT_BOTH)
check("a whitespace title is no title", PageExport.parentProblem("12345", "   ", "Report"), "")
check("a whitespace id is no id", PageExport.parentProblem("   ", "Team Reports", "Report"), "")
ok("the refusal names both fields", PageExport.PARENT_BOTH.contains("id") && PageExport.PARENT_BOTH.contains("title"))
ok("an over-long parent title is refused",
    !PageExport.parentProblem("", "x" * (PageExport.MAX_TITLE_CHARS + 1), "Report").isEmpty())
check("a parent title at the limit is fine",
    PageExport.parentProblem("", "x" * PageExport.MAX_TITLE_CHARS, "Report"), "")

/* A page cannot be its own parent, and Confluence titles are unique per space.
   Caught before anything is created, not after the container page exists. */
ok("parent title equal to the report title is refused",
    !PageExport.parentProblem("", "Same Title", "Same Title").isEmpty())
ok("the comparison ignores case and padding",
    !PageExport.parentProblem("", "  same title  ", "Same Title").isEmpty())
check("a picked id with an equal report title is untouched",
    PageExport.parentProblem("12345", "", "Same Title"), "")

/* The container page this export creates is not an export page and must never be
   mistaken for one: without the marker a later run refuses to overwrite it. */
ok("parent body carries no export marker", !PageExport.PARENT_BODY.contains(PageExport.MARKER))
ok("parent body names its origin", PageExport.PARENT_BODY.contains("export"))
ok("a parent body page fails the decision read", PageExport.parseDecisions(PageExport.PARENT_BODY).outcome == DecisionRead.FAILED)

/* ---- parent position: the move decision ---------------------------------- */

/* The defect these cover, reproduced on a real instance: an administrator typed a
   parent title that did not exist. The parent page was created correctly and the
   report was not filed underneath it, because the parent was applied on the create
   branch of the report page only. A repeat run takes the update branch.

   The rule now distinguishes intent. A parent named in THIS run - picked from the
   search or created from a typed title - is an instruction and is carried out even
   when the report page already exists. No parent named means the position is not
   touched, which keeps the original protection for a page an administrator moved
   by hand. */

check("nothing named: the position is left alone",
    PageExport.moveDecision(null, "555"), PageExport.MOVE_NOT_REQUESTED)
check("an empty parent id is nothing named",
    PageExport.moveDecision("", "555"), PageExport.MOVE_NOT_REQUESTED)
check("a blank parent id is nothing named",
    PageExport.moveDecision("   ", "555"), PageExport.MOVE_NOT_REQUESTED)
check("nothing named on a top-level page is still nothing named",
    PageExport.moveDecision(null, null), PageExport.MOVE_NOT_REQUESTED)

check("named and the page sits elsewhere: move",
    PageExport.moveDecision("555", "999"), PageExport.MOVE_REQUESTED)
check("named and the page sits at the top level: move",
    PageExport.moveDecision("555", null), PageExport.MOVE_REQUESTED)
check("named and the current parent is an empty string: move",
    PageExport.moveDecision("555", ""), PageExport.MOVE_REQUESTED)

check("named and the page already sits there: skip",
    PageExport.moveDecision("555", "555"), PageExport.MOVE_ALREADY_THERE)
check("the skip tolerates padding on both sides",
    PageExport.moveDecision("  555  ", " 555 "), PageExport.MOVE_ALREADY_THERE)

/* An unreadable current position must not be guessed into a skip. Carrying out the
   instruction is the safe direction: only a positive match skips. */
check("an unknown current position resolves to move, never to skip",
    PageExport.moveDecision("555", null), PageExport.MOVE_REQUESTED)
ok("ids are compared exactly, not by prefix",
    PageExport.moveDecision("55", "555") == PageExport.MOVE_REQUESTED)

/* The three verdicts are distinct strings, so a caller cannot confuse two of
   them by accident. */
ok("the three move decisions are distinct",
    ([PageExport.MOVE_REQUESTED, PageExport.MOVE_NOT_REQUESTED, PageExport.MOVE_ALREADY_THERE] as Set).size() == 3)

/* ---- parent position: the measured verdict ------------------------------- */

/* movePageAsChild returning without throwing, and a PUT that Confluence accepted,
   are reports about themselves. Neither is a measurement of the page tree. What
   goes into the response is what the read-back found. */

Map<String, Object> vNone = PageExport.parentOutcome(null, true, "999", null)
check("no parent named: applied is null, not false", vNone.get("applied"), null)
check("no parent named: no reason either", vNone.get("reason"), null)
Map<String, Object> vBlank = PageExport.parentOutcome("   ", true, null, null)
check("a blank request is the same as no request", vBlank.get("applied"), null)

Map<String, Object> vHit = PageExport.parentOutcome("555", true, "555", null)
check("the read-back named the requested parent: true", vHit.get("applied"), PageExport.PARENT_APPLIED_TRUE)
check("a true verdict carries no reason", vHit.get("reason"), null)
Map<String, Object> vHitPad = PageExport.parentOutcome(" 555 ", true, "555 ", null)
check("the comparison tolerates padding", vHitPad.get("applied"), PageExport.PARENT_APPLIED_TRUE)

/* A read that answered and found the page at the top level is a real measurement
   of a move that did not happen. It is false, not unknown. */
Map<String, Object> vTop = PageExport.parentOutcome("555", true, null, null)
check("read answered, page at the top level: false", vTop.get("applied"), PageExport.PARENT_APPLIED_FALSE)
ok("the false reason says where the page actually sits",
    vTop.get("reason").toString().contains("top level"))

Map<String, Object> vElse = PageExport.parentOutcome("555", true, "999", null)
check("read answered, page under another parent: false", vElse.get("applied"), PageExport.PARENT_APPLIED_FALSE)
ok("the false reason names the page it sits under", vElse.get("reason").toString().contains("999"))
ok("the false reason says the report was written anyway",
    vElse.get("reason").toString().contains("written"))

/* An empty or failed read-back is the case this field exists for. It must never
   be reported as a successful move, and never as a failed one either. */
Map<String, Object> vUnknown = PageExport.parentOutcome("555", false, null, null)
check("the read-back failed: unknown", vUnknown.get("applied"), PageExport.PARENT_APPLIED_UNKNOWN)
ok("unknown is not true", vUnknown.get("applied") != PageExport.PARENT_APPLIED_TRUE)
ok("unknown is not false", vUnknown.get("applied") != PageExport.PARENT_APPLIED_FALSE)
ok("unknown carries a reason", vUnknown.get("reason") != null && !vUnknown.get("reason").toString().isEmpty())
ok("the unknown reason says it was not measured",
    vUnknown.get("reason").toString().contains("not measured"))

/* A failed read-back that also carries an id must still be unknown: the id cannot
   have come from a read that did not answer. */
Map<String, Object> vUnknownId = PageExport.parentOutcome("555", false, "555", null)
check("a failed read-back is unknown even with an id in hand",
    vUnknownId.get("applied"), PageExport.PARENT_APPLIED_UNKNOWN)

/* The move error only sharpens the wording. It never decides the verdict: the
   measurement does. */
Map<String, Object> vErrTrue = PageExport.parentOutcome("555", true, "555", "SomeException: nope")
check("a reported move failure does not beat a read-back that found the parent",
    vErrTrue.get("applied"), PageExport.PARENT_APPLIED_TRUE)
Map<String, Object> vErrFalse = PageExport.parentOutcome("555", true, null, "SomeException: nope")
check("a move failure with a read-back that answered stays false",
    vErrFalse.get("applied"), PageExport.PARENT_APPLIED_FALSE)
ok("the false reason quotes the move failure",
    vErrFalse.get("reason").toString().contains("SomeException: nope"))
Map<String, Object> vErrUnknown = PageExport.parentOutcome("555", false, null, "SomeException: nope")
check("a move failure with no read-back is unknown", vErrUnknown.get("applied"), PageExport.PARENT_APPLIED_UNKNOWN)
ok("the unknown reason quotes the move failure",
    vErrUnknown.get("reason").toString().contains("SomeException: nope"))
ok("a blank move failure adds nothing to the wording",
    PageExport.parentOutcome("555", false, null, "   ").get("reason") == vUnknown.get("reason"))

/* The three states are strings on purpose: a browser that writes
   if (!body.parentApplied) would read a mixed boolean-or-string field as a
   success, which is the silent mismatch this measurement exists to prevent. */
ok("the three applied states are distinct strings",
    ([PageExport.PARENT_APPLIED_TRUE, PageExport.PARENT_APPLIED_FALSE,
      PageExport.PARENT_APPLIED_UNKNOWN] as Set).size() == 3)
ok("no applied state is a boolean",
    !(PageExport.PARENT_APPLIED_TRUE instanceof Boolean) &&
    !(PageExport.PARENT_APPLIED_FALSE instanceof Boolean) &&
    !(PageExport.PARENT_APPLIED_UNKNOWN instanceof Boolean))
ok("every applied state is a non-empty string, so none is falsy in a browser",
    !PageExport.PARENT_APPLIED_TRUE.isEmpty() && !PageExport.PARENT_APPLIED_FALSE.isEmpty() &&
    !PageExport.PARENT_APPLIED_UNKNOWN.isEmpty())

/* ---- parent position: the four decisions end to end ---------------------- */

/* The whole rule in one table: what the run decides to do, and what it then
   reports having measured. The move call itself needs an instance and stays out. */

def scenario = { String wantId, String nowId, boolean readOk, String gotId ->
    String verdictDecision = PageExport.moveDecision(wantId, nowId)
    Map<String, Object> verdict = PageExport.parentOutcome(wantId, readOk, gotId, null)
    return verdictDecision + "/" + String.valueOf(verdict.get("applied"))
}

check("a parent was named and the page moved under it",
    scenario("555", "999", true, "555"), PageExport.MOVE_REQUESTED + "/" + PageExport.PARENT_APPLIED_TRUE)
check("a parent was named and the page did not move",
    scenario("555", "999", true, "999"), PageExport.MOVE_REQUESTED + "/" + PageExport.PARENT_APPLIED_FALSE)
check("no parent was named, so nothing was moved and nothing is claimed",
    scenario(null, "999", true, "999"), PageExport.MOVE_NOT_REQUESTED + "/null")
check("the page already sat under the named parent, so no move and a true verdict",
    scenario("555", "555", true, "555"), PageExport.MOVE_ALREADY_THERE + "/" + PageExport.PARENT_APPLIED_TRUE)
check("a named parent with a read-back that did not answer is unknown",
    scenario("555", "999", false, null), PageExport.MOVE_REQUESTED + "/" + PageExport.PARENT_APPLIED_UNKNOWN)
check("a skipped move with a read-back that did not answer is also unknown",
    scenario("555", "555", false, null), PageExport.MOVE_ALREADY_THERE + "/" + PageExport.PARENT_APPLIED_UNKNOWN)

/* ---- parent position: the ancestor chain of a REST response -------------- */

/* The Jira endpoint reaches Confluence over the application link, so its
   measurement is a parsed JSON response rather than a Page. Ancestors run from the
   root of the space downwards, so the direct parent is the last entry.

   Whether the ancestors array on a PUT actually moves a page in Confluence Data
   Center 10 is NOT VERIFIED - no primary source was readable. That is exactly why
   the read-back below has to be trustworthy: it is the only thing that decides
   what the response claims. */

Map<String, Object> jNull = PageExport.innermostAncestor((Map<String, Object>) null)
check("no response at all is not a measurement", jNull.get("measured"), Boolean.FALSE)
check("no response names no parent", jNull.get("parentId"), null)

Map<String, Object> jAbsent = PageExport.innermostAncestor([id: "1", title: "x"] as Map<String, Object>)
check("a response without an ancestors array is not a measurement",
    jAbsent.get("measured"), Boolean.FALSE)
check("a response without an ancestors array names no parent", jAbsent.get("parentId"), null)

Map<String, Object> jNotList = PageExport.innermostAncestor([ancestors: "nope"] as Map<String, Object>)
check("an ancestors value that is not a list is not a measurement",
    jNotList.get("measured"), Boolean.FALSE)
Map<String, Object> jNullNode = PageExport.innermostAncestor([ancestors: null] as Map<String, Object>)
check("an explicitly null ancestors value is not a measurement",
    jNullNode.get("measured"), Boolean.FALSE)

/* An empty array IS a measurement: Confluence answered and the page sits at the
   top level of the space. */
Map<String, Object> jEmpty = PageExport.innermostAncestor([ancestors: []] as Map<String, Object>)
check("an empty ancestors array is a measurement", jEmpty.get("measured"), Boolean.TRUE)
check("an empty ancestors array means the top level of the space", jEmpty.get("parentId"), null)
ok("an absent array and an empty array are told apart",
    jAbsent.get("measured") != jEmpty.get("measured"))

/* rowsOf answers an absent key and an empty array with the same empty list, which
   is the one distinction that matters here. This is why innermostAncestor does not
   use it, and the contrast is asserted so nobody simplifies it away later. */
ok("rowsOf cannot tell an absent array from an empty one",
    PageExport.rowsOf([id: "1"] as Map<String, Object>, "ancestors").isEmpty() &&
    PageExport.rowsOf([ancestors: []] as Map<String, Object>, "ancestors").isEmpty())

Map<String, Object> jChain = PageExport.innermostAncestor(
    [ancestors: [[id: "100"], [id: "200"], [id: "300"]]] as Map<String, Object>)
check("the direct parent is the last entry", jChain.get("parentId"), "300")
check("a populated ancestors array is a measurement", jChain.get("measured"), Boolean.TRUE)

check("a single ancestor is the direct parent",
    PageExport.innermostAncestor([ancestors: [[id: "100"]]] as Map<String, Object>).get("parentId"), "100")
check("a numeric id survives as a string",
    PageExport.innermostAncestor([ancestors: [[id: 100]]] as Map<String, Object>).get("parentId"), "100")
check("padding is stripped from the id",
    PageExport.innermostAncestor([ancestors: [[id: " 100 "]]] as Map<String, Object>).get("parentId"), "100")
check("an entry without an id falls back to the last usable one",
    PageExport.innermostAncestor(
        [ancestors: [[id: "100"], [title: "no id here"]]] as Map<String, Object>).get("parentId"), "100")
check("an entry that is not a map falls back to the last usable one",
    PageExport.innermostAncestor(
        [ancestors: [[id: "100"], "junk"]] as Map<String, Object>).get("parentId"), "100")
check("an array of nothing usable names no parent but stays measured",
    PageExport.innermostAncestor([ancestors: [[title: "x"]]] as Map<String, Object>).get("parentId"), null)
check("an array of nothing usable is still a measurement",
    PageExport.innermostAncestor([ancestors: [[title: "x"]]] as Map<String, Object>).get("measured"), Boolean.TRUE)

/* The chain feeds the verdict, so a read-back that did not answer has to arrive as
   unknown and a measured top-level page as false. */
check("a response with no ancestors array becomes unknown, never false",
    PageExport.parentOutcome("555", jAbsent.get("measured") == Boolean.TRUE,
        (String) jAbsent.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_UNKNOWN)
check("a measured top-level page becomes false, never unknown",
    PageExport.parentOutcome("555", jEmpty.get("measured") == Boolean.TRUE,
        (String) jEmpty.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_FALSE)
check("a measured chain that names the requested parent becomes true",
    PageExport.parentOutcome("300", jChain.get("measured") == Boolean.TRUE,
        (String) jChain.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_TRUE)
check("a measured chain that names a grandparent is not a match",
    PageExport.parentOutcome("200", jChain.get("measured") == Boolean.TRUE,
        (String) jChain.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_FALSE)

/* The ancestors payload the write sends, mirrored: one entry naming the parent id.
   If that array ever stops matching what the read-back compares against, the two
   halves of the measurement have drifted apart. */
check("the payload shape the write sends is the shape the read-back understands",
    PageExport.innermostAncestor([ancestors: [[id: "555"]]] as Map<String, Object>).get("parentId"), "555")

/* ---- parent position: red before green ----------------------------------- */

/* The same discipline the decision parser gets. The control is the behaviour that
   actually shipped and that the Director reproduced on a real instance: the parent
   was applied on the create branch of the report page only, so a run that took the
   update branch never moved anything, and the response reported the parent from the
   request as though it had been applied.

   Both halves of that are modelled here and measured on every run, so the new rule
   cannot quietly collapse back into it. */

def controlMoveDecision = { String wantId, String nowId ->
    /* The create branch is unreachable for a page that already exists, so the
       shipped code left every existing report page exactly where it was. */
    return PageExport.MOVE_NOT_REQUESTED
}

def controlApplied = { String wantId, boolean readOk, String gotId ->
    /* The shipped response carried the parent from the request and never from a
       read-back, so a named parent read as applied whatever the page tree said. */
    return (wantId == null || wantId.trim().isEmpty()) ? null : PageExport.PARENT_APPLIED_TRUE
}

List<List<Object>> parentCases = [
    /* requested, current parent, read-back answered, actual parent, decision, verdict */
    ["555", "999", Boolean.TRUE,  "555", PageExport.MOVE_REQUESTED,     PageExport.PARENT_APPLIED_TRUE],
    ["555", "999", Boolean.TRUE,  "999", PageExport.MOVE_REQUESTED,     PageExport.PARENT_APPLIED_FALSE],
    ["555", null,  Boolean.TRUE,  null,  PageExport.MOVE_REQUESTED,     PageExport.PARENT_APPLIED_FALSE],
    ["555", "555", Boolean.TRUE,  "555", PageExport.MOVE_ALREADY_THERE, PageExport.PARENT_APPLIED_TRUE],
    ["555", "999", Boolean.FALSE, null,  PageExport.MOVE_REQUESTED,     PageExport.PARENT_APPLIED_UNKNOWN],
    ["555", "555", Boolean.FALSE, null,  PageExport.MOVE_ALREADY_THERE, PageExport.PARENT_APPLIED_UNKNOWN],
    [null,  "999", Boolean.TRUE,  "999", PageExport.MOVE_NOT_REQUESTED, null],
    ["",    null,  Boolean.TRUE,  null,  PageExport.MOVE_NOT_REQUESTED, null],
]

int parentMoveDiffs = 0
int parentControlClaims = 0

parentCases.each { List<Object> row ->
    String requested = (String) row[0]
    String current = (String) row[1]
    boolean readBackOk = Boolean.TRUE.equals(row[2])
    String actual = (String) row[3]
    String label = "[" + String.valueOf(requested) + " under " + String.valueOf(current) +
        ", read " + (readBackOk ? "answered" : "failed") + "]"

    String decision = PageExport.moveDecision(requested, current)
    Object applied = PageExport.parentOutcome(requested, readBackOk, actual, null).get("applied")
    check("decision " + label, decision, row[4])
    check("verdict " + label, applied, row[5])

    if (controlMoveDecision(requested, current) != decision) {
        parentMoveDiffs++
    }
    Object claimed = controlApplied(requested, readBackOk, actual)
    if (claimed == PageExport.PARENT_APPLIED_TRUE && applied != PageExport.PARENT_APPLIED_TRUE) {
        parentControlClaims++
    }
}

ok("the create-only control refuses moves the new rule makes", parentMoveDiffs > 0)
ok("the control claims a parent the measurement does not confirm", parentControlClaims > 0)
ok("every case in the table is distinct", (parentCases.collect { it.toString() } as Set).size() == parentCases.size())

println "red-before-green (parent): " + parentCases.size() + " cases, create-only control refuses " +
    parentMoveDiffs + " moves, claims " + parentControlClaims + " unconfirmed parents"

/* ---- measurement notes: the box colour follows its content --------------- */

/* The defect, reported from a live run: the box was painted yellow while its own
   text read "Nothing failed and nothing was suppressed: these are statements this
   report makes on purpose". The stylesheet coloured .diag as a warning
   unconditionally, so every Measurement notes box was a warning whatever it
   contained.

   The assertions are on the emitted class, not on the text. */

check("observations only: informational", Fp.diagClass(0, 0, false, 0), Fp.DIAG_INFO)

/* The four things that actually degrade this report, each on its own. */
check("an unresolved type field: warning", Fp.diagClass(2, 0, false, 0), Fp.DIAG_WARN)
check("an issue count skipped by the budget: warning", Fp.diagClass(0, 4, false, 0), Fp.DIAG_WARN)
check("a truncated screen reach: warning", Fp.diagClass(0, 0, true, 0), Fp.DIAG_WARN)
check("a suppressed read error: warning", Fp.diagClass(0, 0, false, 7), Fp.DIAG_WARN)
check("a single unresolved type field is enough", Fp.diagClass(1, 0, false, 0), Fp.DIAG_WARN)
check("a single skipped issue count is enough", Fp.diagClass(0, 1, false, 0), Fp.DIAG_WARN)
check("a single read error is enough", Fp.diagClass(0, 0, false, 1), Fp.DIAG_WARN)
check("all four at once: warning", Fp.diagClass(2, 4, true, 7), Fp.DIAG_WARN)

/* Warning wins over an observation standing next to it: a real limitation is not
   made less true by a deliberate statement in the same box. */
ok("a box carrying both kinds is a warning",
    Fp.diagBoxShown(0, 12, 0, false) && Fp.diagClass(0, 0, false, 4) == Fp.DIAG_WARN)

/* The exact case the Director reported: ten observations, nothing failed, nothing
   suppressed. The box appears, and it is informational. */
ok("ten observations and nothing else: the box shows", Fp.diagBoxShown(0, 10, 0, false))
check("ten observations and nothing else: informational", Fp.diagClass(0, 0, false, 0), Fp.DIAG_INFO)

/* No number of observations can colour the box. They are not an input to the
   decision at all, which is what makes that impossible rather than unlikely. */
ok("an observation-only box is never a warning", Fp.diagClass(0, 0, false, 0) != Fp.DIAG_WARN)

/* Visibility is unchanged: the box is not hidden and not split in two. */
ok("a box with nothing at all does not appear", !Fp.diagBoxShown(0, 0, 0, false))
ok("an unresolved type field alone makes the box appear", Fp.diagBoxShown(1, 0, 0, false))
ok("a diagnostic alone makes the box appear", Fp.diagBoxShown(0, 1, 0, false))
ok("a skipped issue count alone makes the box appear", Fp.diagBoxShown(0, 0, 1, false))
ok("a truncated screen reach alone makes the box appear", Fp.diagBoxShown(0, 0, 0, true))

/* Both variants keep the base class, so the box geometry and the list rules in the
   stylesheet apply to either colour. */
ok("the informational variant keeps the base box class", Fp.DIAG_INFO.startsWith("diag "))
ok("the warning variant keeps the base box class", Fp.DIAG_WARN.startsWith("diag "))
ok("the two variants are different classes", Fp.DIAG_INFO != Fp.DIAG_WARN)
ok("the informational variant carries its own modifier", Fp.DIAG_INFO.contains("diag-info"))
ok("the warning variant carries its own modifier", Fp.DIAG_WARN.contains("diag-warn"))
ok("neither variant is the bare base class", Fp.DIAG_INFO != "diag" && Fp.DIAG_WARN != "diag")
ok("the informational variant is not the warning modifier", !Fp.DIAG_INFO.contains("diag-warn"))
ok("the warning variant is not the informational modifier", !Fp.DIAG_WARN.contains("diag-info"))

/* Red before green. The control is the stylesheet that shipped: one class for every
   box, so the colour could not follow the content. */
def controlDiagClass = { int unresolvedCount, int budgetSkipCount, boolean reachTruncated,
                         int readErrorCount -> return "diag" }
List<List<Object>> diagCases = [
    [0, 0, Boolean.FALSE, 0],
    [2, 0, Boolean.FALSE, 0],
    [0, 4, Boolean.FALSE, 0],
    [0, 0, Boolean.TRUE,  0],
    [0, 0, Boolean.FALSE, 7],
]
int diagColourDiffs = 0
diagCases.each { List<Object> row ->
    String mine = Fp.diagClass((int) row[0], (int) row[1], Boolean.TRUE.equals(row[2]), (int) row[3])
    if (controlDiagClass((int) row[0], (int) row[1], Boolean.TRUE.equals(row[2]), (int) row[3]) != mine) {
        diagColourDiffs++
    }
}
ok("the single-class control cannot express either variant", diagColourDiffs == diagCases.size())
ok("the control paints an observation-only box the same as a degraded one",
    controlDiagClass(0, 0, false, 0) == controlDiagClass(2, 4, true, 7) &&
    Fp.diagClass(0, 0, false, 0) != Fp.diagClass(2, 4, true, 7))

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
