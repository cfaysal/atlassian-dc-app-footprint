
/* ===========================================================================
 * Offline harness for the Confluence-free parts of confluenceDCappFootprint.groovy.
 * The class definitions are prepended verbatim from the real file, in two cuts:
 * Cfp .. AppFootprint, and DecisionRead .. PageExport. Analyzer sits between
 * them, touches Confluence types, and is therefore not part of the cut.
 * ======================================================================== */

/* ---- 0. stand-ins for the platform types the cut mentions ---------------- */

/* Cfp names five types in method signatures that do not exist outside a running
 * Confluence. None of them carries behaviour that is under test here: the only
 * methods using Plugin / I18NBean / ModuleDescriptor / MacroMetadata are
 * resolvePluginName and resolveMacroDisplayName, and both are out of scope.
 * MultivaluedMap is declared with the one method the parameter helpers call, so
 * FakeParams below is a real stand-in and not a cast. */
interface MultivaluedMap { Object getFirst(String name) }
interface Plugin { }
interface I18NBean { }
interface ModuleDescriptor<T> { }
interface MacroMetadata { }

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

check("html null", Cfp.html(null), "")
check("html plain", Cfp.html("abc"), "abc")
check("html all", Cfp.html("<a href=\"x\">&'</a>"),
    "&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;")
check("html ampersand first", Cfp.html("&lt;"), "&amp;lt;")
check("html number", Cfp.html(42), "42")

/* ---- 2. number formatting ------------------------------------------------ */

check("number null", Cfp.number(null, Locale.GERMANY), Cfp.NA)
check("number de", Cfp.number(1234567L, Locale.GERMANY), "1.234.567")
check("number en", Cfp.number(1234567L, Locale.ENGLISH), "1,234,567")
check("number int", Cfp.number(0, Locale.ENGLISH), "0")
check("number null locale falls back to en", Cfp.number(1234567L, null), "1,234,567")

/* ---- 3. csv quoting ------------------------------------------------------ */

check("csv null", Cfp.csv(null), "\"\"")
check("csv quote", Cfp.csv("say \"hi\", ok"), "\"say \"\"hi\"\", ok\"")

/* ---- 4. parameters ------------------------------------------------------- */

class FakeParams implements MultivaluedMap {
    Map<String, String> values = new LinkedHashMap<String, String>()
    Object getFirst(String name) { return values.get(name) }
}

FakeParams params = new FakeParams()
params.values.put("flag", "TRUE")
params.values.put("off", "0")
params.values.put("junk", "maybe")
params.values.put("blank", "   ")
params.values.put("n", "500")
params.values.put("neg", "-5")
params.values.put("pad", "  html  ")

check("boolean true", Cfp.booleanParam(params, "flag", false), true)
check("boolean zero", Cfp.booleanParam(params, "off", true), false)
check("boolean junk keeps default", Cfp.booleanParam(params, "junk", true), true)
check("boolean missing", Cfp.booleanParam(params, "nope", true), true)
check("boolean blank keeps default", Cfp.booleanParam(params, "blank", true), true)
check("boolean null params", Cfp.booleanParam(null, "flag", true), true)
check("string default", Cfp.stringParam(params, "nope", "html"), "html")
check("string trims", Cfp.stringParam(params, "pad", "x"), "html")
check("string blank falls back", Cfp.stringParam(params, "blank", "html"), "html")
check("null params", Cfp.stringParam(null, "x", "d"), "d")
check("long parse", Cfp.longParam(params, "n", 1L), 500L)
check("long zero is allowed", Cfp.longParam(params, "off", 7L), 0L)
check("long negative keeps default", Cfp.longParam(params, "neg", 7L), 7L)
check("long junk keeps default", Cfp.longParam(params, "junk", 9L), 9L)
check("long missing", Cfp.longParam(params, "nope", 9L), 9L)

/* ---- 5. link building ---------------------------------------------------- */

Map<String, Object> base = new LinkedHashMap<String, Object>()
base.put("format", null)
base.put("includeSystem", "true")
base.put("includeArchived", null)

Map<String, Object> addFormat = new LinkedHashMap<String, Object>()
addFormat.put("format", "json")
Map<String, Object> dropSystem = new LinkedHashMap<String, Object>()
dropSystem.put("includeSystem", null)
Map<String, Object> encodeMe = new LinkedHashMap<String, Object>()
encodeMe.put("q", "a b&c")

check("link keeps set values", Cfp.link(base, null), "?includeSystem=true")
check("link override adds", Cfp.link(base, addFormat), "?format=json&includeSystem=true")
check("link override removes", Cfp.link(base, dropSystem), "?")
check("link encodes", Cfp.link(encodeMe, null), "?q=a+b%26c")

/* ---- 6. index field, content types, diagnostics -------------------------- */

Map<String, String[]> document = new HashMap<String, String[]>()
document.put("macroName", ["jira", "jiraissues"] as String[])
document.put("empty", new String[0])
check("firstValue", Cfp.firstValue(document, "macroName"), "jira")
check("firstValue empty array", Cfp.firstValue(document, "empty"), null)
check("firstValue missing field", Cfp.firstValue(document, "nope"), null)
check("firstValue null document", Cfp.firstValue(null, "macroName"), null)

Map<String, Integer> contentTypes = new LinkedHashMap<String, Integer>()
contentTypes.put("page", 3)
contentTypes.put("blogpost", 1)
check("contentTypeText null", Cfp.contentTypeText(null), Cfp.NA)
check("contentTypeText empty", Cfp.contentTypeText(new LinkedHashMap<String, Integer>()), Cfp.NA)
check("contentTypeText joins", Cfp.contentTypeText(contentTypes), "page: 3, blogpost: 1")

List<String> sink = new ArrayList<String>()
Cfp.note(sink, "macro usage", new IllegalStateException("boom"))
check("note format", sink.get(0), "macro usage -> IllegalStateException: boom")
Cfp.note(sink, "macro usage", null)
check("note null error", sink.get(1), "macro usage -> unknown error")
Cfp.note(sink, "long", new RuntimeException("x" * 400))
ok("note truncated", sink.get(2).length() <= 240)
Cfp.note(null, "no sink", new RuntimeException("ignored"))
check("note tolerates a null sink", sink.size(), 3)

/* ---- 7. category heuristic ----------------------------------------------- */

Map<String, String> categoryCases = new LinkedHashMap<String, String>()
categoryCases.put("SpaceBlueprintModuleDescriptor", "Space Blueprints")
categoryCases.put("BlueprintModuleDescriptor", "Blueprints")
categoryCases.put("ContentTemplateModuleDescriptor", "Content Templates")
categoryCases.put("TemplateModuleDescriptor", "Templates")
categoryCases.put("MacroModuleDescriptor", "Macros")
categoryCases.put("XhtmlMacroModuleDescriptor", "Macros")
categoryCases.put("CustomContentModuleDescriptor", "Custom Content")
categoryCases.put("ContentTypeModuleDescriptor", "Custom Content")
categoryCases.put("WebItemModuleDescriptor", "UI")
categoryCases.put("WebSectionModuleDescriptor", "UI")
categoryCases.put("WebPanelModuleDescriptor", "UI")
categoryCases.put("WebResourceModuleDescriptor", "UI")
categoryCases.put("WebResourceTransformerModuleDescriptor", "UI")
categoryCases.put("KeyboardShortcutModuleDescriptor", "UI")
categoryCases.put("RestModuleDescriptor", "REST / API")
categoryCases.put("ResourceDescriptor", "REST / API")
categoryCases.put("ServletModuleDescriptor", "HTTP / Servlet")
categoryCases.put("ServletFilterModuleDescriptor", "HTTP / Servlet")
categoryCases.put("SearchExtractorModuleDescriptor", "Search / Index")
categoryCases.put("IndexTaskModuleDescriptor", "Search / Index")
categoryCases.put("QueryModuleDescriptor", "Search / Index")
categoryCases.put("EditorPluginModuleDescriptor", "Editor")
categoryCases.put("EventListenerModuleDescriptor", "Events / Listeners")
categoryCases.put("WebhookModuleDescriptor", "Events / Listeners")
categoryCases.put("ScheduledJobModuleDescriptor", "Jobs / Services")
categoryCases.put("ServiceModuleDescriptor", "Jobs / Services")
categoryCases.put("PermissionModuleDescriptor", "Permissions / Security")
categoryCases.put("ThemeModuleDescriptor", "Theme / Layout")
categoryCases.put("LayoutModuleDescriptor", "Theme / Layout")
categoryCases.put("DecoratorModuleDescriptor", "Theme / Layout")
categoryCases.put("ComponentImportModuleDescriptor", "Other")
categoryCases.put("SomethingElse", "Other")
categoryCases.each { String descriptor, String expected ->
    check("category " + descriptor, Cfp.extensionCategory(descriptor), expected)
}
check("category null", Cfp.extensionCategory(null), "Other")
check("category is case insensitive",
    Cfp.extensionCategory("com.example.XHTMLMACROModuleDescriptor"), "Macros")

/* CATEGORY_RULES is ordered and the first match wins: Templates is listed
 * before Macros, so a name carrying both markers lands in Templates. */
check("ordered first match template before macro",
    Cfp.extensionCategory("MacroTemplateModuleDescriptor"), "Templates")
ok("rule order is Templates then Macros",
    Cfp.CATEGORY_RULES.findIndexOf { it.get(0) == "Templates" } <
    Cfp.CATEGORY_RULES.findIndexOf { it.get(0) == "Macros" })

/* regression: a descriptor class name carrying xhtmlmacro is a macro module */
check("regression xhtmlmacro is a macro",
    Cfp.extensionCategory("com.atlassian.confluence.macro.xhtml.XhtmlMacroModuleDescriptor"), "Macros")
/* regression: WebResource carries "resource" and must never land in REST / API */
ok("regression WebResource not REST",
    Cfp.extensionCategory("WebResourceModuleDescriptor") != "REST / API")
ok("regression DownloadableWebResource not REST",
    Cfp.extensionCategory("DownloadableWebResourceModuleDescriptor") != "REST / API")

/* ---- 8. macro usage state ------------------------------------------------ */

MacroFootprint macro = new MacroFootprint()
check("macro default state is disabled", macro.usageState, Cfp.DISABLED)
ok("disabled is not measured", !macro.isMeasured())
macro.usageState = Cfp.MEASURED
ok("measured is measured", macro.isMeasured())
macro.usageState = Cfp.PARTIAL
ok("partial counts as measured", macro.isMeasured())
macro.usageState = Cfp.BUDGET
ok("budget is not measured", !macro.isMeasured())
macro.usageState = Cfp.ERROR
ok("error is not measured", !macro.isMeasured())

/* content ids are present, the state says they were not measured: the macro is
 * never reported as used, and the figure is never rendered as a number. */
macro.currentContentIds.addAll(["c1", "c2"])
macro.archivedContentIds.add("a1")
macro.usageState = Cfp.BUDGET
ok("budgeted macro is not currently used", !macro.isCurrentlyUsed())
ok("budgeted macro is not archived used", !macro.isArchivedUsed())
check("budgeted macro renders n/m, not a count",
    PageExport.usageText(macro.usageState, Integer.valueOf(macro.getCurrentContentCount()), Locale.ENGLISH), "n/m")
macro.usageState = Cfp.MEASURED
ok("measured macro is currently used", macro.isCurrentlyUsed())
ok("measured macro is archived used", macro.isArchivedUsed())

MacroFootprint dedupe = new MacroFootprint()
dedupe.currentContentIds.addAll(["a", "b"])
dedupe.archivedContentIds.addAll(["b", "c"])
dedupe.otherContentIds.add("a")
check("current content count", dedupe.getCurrentContentCount(), 2)
check("archived content count", dedupe.getArchivedContentCount(), 2)
check("other content count", dedupe.getOtherContentCount(), 1)
check("total content deduplicated across dimensions", dedupe.getTotalContentCount(), 3)

MacroFootprint typed = new MacroFootprint()
typed.currentContentTypeById.put("1", "page")
typed.currentContentTypeById.put("2", "page")
typed.currentContentTypeById.put("3", "blogpost")
typed.currentContentTypeById.put("4", "  ")
check("content types ordered by count then name",
    new ArrayList<String>(typed.getCurrentContentTypeCounts().keySet()), ["page", "blogpost", "unknown"])
check("blank content type becomes unknown", typed.getCurrentContentTypeCounts().get("unknown"), 1)
check("archived content types empty", typed.getArchivedContentTypeCounts().size(), 0)

MacroFootprint mapped = new MacroFootprint()
mapped.macroName = "jira"
mapped.usageState = Cfp.BUDGET
mapped.currentContentIds.add("c1")
Map<String, Object> macroMap = mapped.asMap()
check("macro asMap keeps the state", macroMap.get("usageState"), Cfp.BUDGET)
check("macro asMap does not claim usage", ((Map<String, Object>) macroMap.get("current")).get("used"), false)
Map<String, Object> macroArchivedOff = (Map<String, Object>) macroMap.get("archived")
check("macro asMap marks archive measurement disabled by default",
    macroArchivedOff.get("state"), Cfp.DISABLED)
check("macro asMap masks unmeasured archived content",
    macroArchivedOff.get("contentCount"), null)
check("macro asMap masks unmeasured archived spaces",
    macroArchivedOff.get("spaceCount"), null)


/* ---- 9. app aggregation --------------------------------------------------- */

ExtensionModuleInfo module(String descriptorName, boolean enabled) {
    ExtensionModuleInfo info = new ExtensionModuleInfo()
    info.descriptorName = descriptorName
    info.category = Cfp.extensionCategory(descriptorName)
    info.enabled = enabled ? Boolean.TRUE : Boolean.FALSE
    return info
}

AppFootprint app = new AppFootprint()
app.pluginKey = "com.vendor.example"
app.displayName = "Example"
app.enabled = true
app.modules.add(module("MacroModuleDescriptor", true))
app.modules.add(module("XhtmlMacroModuleDescriptor", true))
app.modules.add(module("WebItemModuleDescriptor", true))
app.modules.add(module("WebItemModuleDescriptor", true))
app.modules.add(module("RestModuleDescriptor", true))
app.modules.add(module("MacroModuleDescriptor", false))

MacroFootprint one = new MacroFootprint()
one.macroName = "alpha"
one.moduleEnabled = Boolean.TRUE
one.usageState = Cfp.MEASURED
one.currentContentIds.addAll(["c1", "c2"])
one.currentSpaceKeys.add("SPACEA")

MacroFootprint two = new MacroFootprint()
two.macroName = "beta"
two.moduleEnabled = Boolean.TRUE
two.usageState = Cfp.MEASURED
two.currentContentIds.addAll(["c2", "c3"])
two.currentSpaceKeys.addAll(["SPACEA", "SPACEB"])
two.archivedContentIds.add("a1")
two.archivedSpaceKeys.add("SPACEZ")
two.diagnostics.add("space read -> RuntimeException: boom")

MacroFootprint three = new MacroFootprint()
three.macroName = "gamma"
three.moduleEnabled = Boolean.FALSE
three.usageState = Cfp.MEASURED

app.macros.addAll([one, two, three])
app.diagnostics.add("plugin state -> IllegalStateException: nope")
app.finish()

check("enabled modules", app.enabledModuleCount, 5)
check("enabled macros counts only enabled modules", app.enabledMacroCount, 2)
check("category Macros", app.categoryCount("Macros"), 2)
check("category UI", app.categoryCount("UI"), 2)
check("category REST", app.categoryCount("REST / API"), 1)
check("category absent is zero not null", app.categoryCount("Editor"), 0)
/* count descending, then name ascending, and disabled modules never appear */
check("module type order", new ArrayList<String>(app.moduleTypeCounts.keySet()),
    ["WebItemModuleDescriptor", "MacroModuleDescriptor", "RestModuleDescriptor", "XhtmlMacroModuleDescriptor"])
check("module type counts", app.moduleTypeCounts.get("WebItemModuleDescriptor"), 2)
check("disabled module type is absent", app.moduleTypeCounts.get("MacroModuleDescriptor"), 1)
check("current used macros", app.currentUsedMacroCount, 2)
check("archived used macros", app.archivedUsedMacroCount, 1)
check("current associations", app.currentAssociations, 4L)
check("archived associations", app.archivedAssociations, 1L)
check("current unique content", app.currentUniqueContentCount, 3)
check("archived unique content", app.archivedUniqueContentCount, 1)
check("current spaces", app.currentSpaceCount, 2)
check("archived spaces", app.archivedSpaceCount, 1)
ok("current footprint detected", app.hasCurrentFootprint())
ok("archived footprint detected", app.hasArchivedFootprint())
ok("current usage not partial", !app.currentUsagePartial)
ok("archived usage not partial", !app.archivedUsagePartial)
check("diagnostics rolled up from app and macros", app.diagnosticCount, 2)

ImpactAssessment impact = new ImpactAssessment()
impact.level = "high"
impact.label = "High"
impact.rank = 3
impact.reasons.add("macro usage in current spaces")

Map<String, Object> appMap = app.asMap(false, impact)
ok("asMap hides modules by default", !appMap.containsKey("modules"))
ok("asMap with modules", app.asMap(true, impact).containsKey("modules"))
check("asMap impact level", ((Map<String, Object>) appMap.get("impact")).get("level"), "high")
check("asMap impact reasons", ((List<String>) ((Map<String, Object>) appMap.get("impact")).get("reasons")).size(), 1)
check("asMap current partial flag",
    ((Map<String, Object>) appMap.get("currentFootprint")).get("partial"), false)
check("asMap current associations",
    ((Map<String, Object>) appMap.get("currentFootprint")).get("macroContentAssociations"), 4L)
check("asMap macro list", ((List<Object>) appMap.get("macros")).size(), 3)
Map<String, Object> archivedAppMap = (Map<String, Object>) appMap.get("archivedFootprint")
check("app asMap marks archive measurement disabled by default",
    archivedAppMap.get("state"), Cfp.DISABLED)
check("app asMap masks unmeasured archived content",
    archivedAppMap.get("uniqueContent"), null)
check("app asMap masks unmeasured archived associations",
    archivedAppMap.get("macroContentAssociations"), null)
Map<String, Object> archiveEnabledAppMap = app.asMap(false, impact, true)
check("app asMap exposes measured archived content after opt-in",
    ((Map<String, Object>) archiveEnabledAppMap.get("archivedFootprint")).get("uniqueContent"), 1)

/* one unmeasured macro makes both dimensions partial, and never a footprint */
AppFootprint partialApp = new AppFootprint()
partialApp.modules.add(module("MacroModuleDescriptor", true))
MacroFootprint budgeted = new MacroFootprint()
budgeted.moduleEnabled = Boolean.TRUE
budgeted.usageState = Cfp.BUDGET
budgeted.currentContentIds.add("c9")
partialApp.macros.add(budgeted)
partialApp.finish()
ok("current usage partial", partialApp.currentUsagePartial)
ok("archived usage partial", partialApp.archivedUsagePartial)
check("budgeted macro is not a used macro", partialApp.currentUsedMacroCount, 0)
ok("budgeted macro is no current footprint", !partialApp.hasCurrentFootprint())
check("budgeted macro still contributes its associations", partialApp.currentAssociations, 1L)

/* persistence capabilities are enabled-only and cover both blueprint flavours */
AppFootprint persistence = new AppFootprint()
persistence.modules.add(module("BlueprintModuleDescriptor", true))
persistence.modules.add(module("SpaceBlueprintModuleDescriptor", true))
persistence.modules.add(module("ContentTemplateModuleDescriptor", true))
persistence.modules.add(module("TemplateModuleDescriptor", true))
persistence.modules.add(module("CustomContentModuleDescriptor", true))
persistence.modules.add(module("WebItemModuleDescriptor", true))
persistence.modules.add(module("BlueprintModuleDescriptor", false))
persistence.finish()
check("provided blueprints", persistence.getProvidedBlueprintCount(), 2)
check("provided templates", persistence.getProvidedTemplateCount(), 2)
check("custom content modules", persistence.getCustomContentModuleCount(), 1)
ok("inventory only persistence signals", persistence.hasInventoryOnlyPersistenceSignals())
check("persistence modules are enabled only", persistence.getPersistenceModules().size(), 5)

AppFootprint emptyApp = new AppFootprint()
emptyApp.finish()
check("empty app has no modules", emptyApp.enabledModuleCount, 0)
check("empty app has no macros", emptyApp.enabledMacroCount, 0)
ok("empty app has no footprint", !emptyApp.hasCurrentFootprint())
ok("empty app has no persistence signals", !emptyApp.hasInventoryOnlyPersistenceSignals())
check("empty app has no diagnostics", emptyApp.diagnosticCount, 0)
ok("empty app usage not partial", !emptyApp.currentUsagePartial)

/* ---- 10. instance-relative impact policy -------------------------------- */

List<List<Object>> impactBoundaryCases = [
    [0L, 10000L, "NO_DETECTABLE_FOOTPRINT", 0],
    [1L, 10000L, "LOW", 4],
    [499L, 10000L, "LOW", 4],
    [500L, 10000L, "MEDIUM", 5],
    [1999L, 10000L, "MEDIUM", 5],
    [2000L, 10000L, "HIGH", 6],
    [4999L, 10000L, "HIGH", 6],
    [5000L, 10000L, "CRITICAL", 7],
    [25000L, 10000L, "CRITICAL", 7]
]
for (List<Object> row : impactBoundaryCases) {
    ImpactDimension dimension = new ImpactDimension(
        "currentContent", "Current content reach",
        (Long) row.get(0), (Long) row.get(1), false)
    ImpactAssessment policyResult = ImpactPolicy.assess([dimension], false)
    check("relative impact level " + row.get(0) + "/" + row.get(1),
        policyResult.level, row.get(2))
    check("relative impact rank " + row.get(0) + "/" + row.get(1),
        policyResult.rank, row.get(3))
}

ImpactAssessment cappedImpact = ImpactPolicy.assess([
    new ImpactDimension("associations", "Association density", 250L, 100L, false)
], false)
check("relative impact percentage is capped", cappedImpact.maxPercent.toPlainString(), "100.000000")

ImpactDimension recurringPercent = new ImpactDimension(
    "content", "Content reach", 1L, Long.valueOf(3L), false)
ok("impact reason rounds recurring percentages",
    recurringPercent.reason().contains("33.33%"))

ImpactAssessment maxImpact = ImpactPolicy.assess([
    new ImpactDimension("content", "Content reach", 1L, 100L, false),
    new ImpactDimension("spaces", "Space reach", 6L, 10L, false)
], false)
check("highest relative dimension wins", maxImpact.level, "CRITICAL")
check("highest relative dimension is named", maxImpact.reasons.size(), 1)
ok("highest relative reason names spaces", maxImpact.reasons.get(0).contains("Space reach"))

ImpactAssessment lowerBoundImpact = ImpactPolicy.assess([
    new ImpactDimension("content", "Content reach", 25L, 100L, true)
], false)
check("partial positive evidence can promote", lowerBoundImpact.level, "HIGH")
ok("partial positive assessment remains partial", lowerBoundImpact.partial)
ok("partial reason is marked lower bound", lowerBoundImpact.reasons.get(0).contains("lower bound"))

ImpactAssessment missingDenominatorImpact = ImpactPolicy.assess([
    new ImpactDimension("content", "Content reach", 5L, null, false)
], false)
check("positive value without denominator requires review",
    missingDenominatorImpact.level, "REVIEW_REQUIRED")

ImpactAssessment incompleteZeroImpact = ImpactPolicy.assess([
    new ImpactDimension("content", "Content reach", 0L, 100L, false)
], true)
check("incomplete zero requires review", incompleteZeroImpact.level, "REVIEW_REQUIRED")

Map<String, Object> relativeImpactMap = maxImpact.asMap()
check("impact map exposes maximum percentage",
    ((BigDecimal) relativeImpactMap.get("maxPercent")).toPlainString(), "60.000000")
check("impact map exposes dimension evidence",
    ((List<Map<String, Object>>) relativeImpactMap.get("dimensions")).size(), 2)

ImpactAssessment candidateZero = new ImpactAssessment(level: "NO_DETECTABLE_FOOTPRINT")
ImpactAssessment candidateLegacy = new ImpactAssessment(level: "LEGACY_ONLY")
ImpactAssessment candidateReview = new ImpactAssessment(level: "REVIEW_REQUIRED")
ImpactAssessment candidateNotScanned = new ImpactAssessment(level: "NOT_SCANNED")
ok("non-system measured zero is a decommission candidate",
    ImpactPolicy.isDecommissionCandidate(false, candidateZero))
ok("system app is never a decommission candidate",
    !ImpactPolicy.isDecommissionCandidate(true, candidateZero))
ok("unknown system status is never a decommission candidate",
    !ImpactPolicy.isDecommissionCandidate(null, candidateZero))
ok("legacy-only app is not a decommission candidate",
    !ImpactPolicy.isDecommissionCandidate(false, candidateLegacy))
ok("review-required app is not a decommission candidate",
    !ImpactPolicy.isDecommissionCandidate(false, candidateReview))
ok("not-scanned app is not a decommission candidate",
    !ImpactPolicy.isDecommissionCandidate(false, candidateNotScanned))

ImpactAssessment assessedApp = ImpactAnalyzer.assessConfluence(
    app, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("Confluence highest product dimension wins", assessedApp.level, "HIGH")
ok("Confluence reasons expose space reach",
    assessedApp.reasons.any { String reason -> reason.contains("Current space reach") })

AppFootprint broadSmallInstance = new AppFootprint()
MacroFootprint broadMacro = new MacroFootprint()
broadMacro.moduleEnabled = Boolean.TRUE
broadMacro.usageState = Cfp.MEASURED
broadMacro.currentContentIds.add("one")
broadMacro.currentSpaceKeys.addAll(["A", "B", "C", "D", "E", "F", "G", "H"])
broadSmallInstance.macros.add(broadMacro)
broadSmallInstance.finish()
ImpactAssessment broadSmallImpact = ImpactAnalyzer.assessConfluence(
    broadSmallInstance, true, true, Long.valueOf(1000L), Long.valueOf(10L), false)
check("eight of ten Confluence spaces is critical", broadSmallImpact.level, "CRITICAL")

ImpactAssessment broadLargeImpact = ImpactAnalyzer.assessConfluence(
    broadSmallInstance, true, true, Long.valueOf(1000L), Long.valueOf(1000L), false)
check("eight of one thousand Confluence spaces is low", broadLargeImpact.level, "LOW")

ImpactAssessment partialAppImpact = ImpactAnalyzer.assessConfluence(
    partialApp, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("partial positive Confluence evidence can promote", partialAppImpact.level, "LOW")
ok("partial positive Confluence evidence stays partial", partialAppImpact.partial)

AppFootprint partialZeroApp = new AppFootprint()
MacroFootprint partialZeroMacro = new MacroFootprint()
partialZeroMacro.moduleEnabled = Boolean.TRUE
partialZeroMacro.usageState = Cfp.BUDGET
partialZeroApp.macros.add(partialZeroMacro)
partialZeroApp.finish()
ImpactAssessment partialZeroImpact = ImpactAnalyzer.assessConfluence(
    partialZeroApp, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("partial Confluence zero requires review", partialZeroImpact.level, "REVIEW_REQUIRED")

AppFootprint archivedOnlyApp = new AppFootprint()
MacroFootprint archivedOnlyMacro = new MacroFootprint()
archivedOnlyMacro.moduleEnabled = Boolean.TRUE
archivedOnlyMacro.usageState = Cfp.MEASURED
archivedOnlyMacro.archivedContentIds.add("old")
archivedOnlyMacro.archivedSpaceKeys.add("ARCHIVE")
archivedOnlyApp.macros.add(archivedOnlyMacro)
archivedOnlyApp.finish()
ImpactAssessment archivedOnlyImpact = ImpactAnalyzer.assessConfluence(
    archivedOnlyApp, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("archived-only Confluence app keeps legacy state", archivedOnlyImpact.level, "LEGACY_ONLY")

ImpactAssessment persistenceImpact = ImpactAnalyzer.assessConfluence(
    persistence, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("persistence-only Confluence app requires review", persistenceImpact.level, "REVIEW_REQUIRED")

ImpactAssessment emptyImpact = ImpactAnalyzer.assessConfluence(
    emptyApp, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("complete Confluence zero is no detectable footprint",
    emptyImpact.level, "NO_DETECTABLE_FOOTPRINT")

ImpactAssessment archiveDisabledImpact = ImpactAnalyzer.assessConfluence(
    emptyApp, true, false, Long.valueOf(100L), Long.valueOf(10L), false)
check("disabled archive scan cannot establish a complete zero",
    archiveDisabledImpact.level, "REVIEW_REQUIRED")

ImpactAssessment disabledImpact = ImpactAnalyzer.assessConfluence(
    app, false, false, Long.valueOf(100L), Long.valueOf(10L), false)
check("disabled Confluence usage scan is not scanned", disabledImpact.level, "NOT_SCANNED")

ImpactAssessment denominatorFailureImpact = ImpactAnalyzer.assessConfluence(
    app, true, true, null, Long.valueOf(10L), true)
check("failed Confluence denominator keeps known high evidence",
    denominatorFailureImpact.level, "HIGH")
ok("failed Confluence denominator marks result partial", denominatorFailureImpact.partial)

/* ---- 10. B8 macro cross-check --------------------------------------------- */

/* One enabled module classified as "Macros", two macros enumerated: the two
 * sides disagree, so the mismatch is reported instead of being hidden. */
AppFootprint b8Mismatch = new AppFootprint()
b8Mismatch.modules.add(module("MacroModuleDescriptor", true))
MacroFootprint b8a = new MacroFootprint()
b8a.moduleEnabled = Boolean.TRUE
MacroFootprint b8b = new MacroFootprint()
b8b.moduleEnabled = Boolean.TRUE
b8Mismatch.macros.addAll([b8a, b8b])
b8Mismatch.finish()
check("cross-check fires on a mismatch", b8Mismatch.diagnostics.size(), 1)
ok("cross-check names both sides",
    b8Mismatch.diagnostics.get(0).startsWith("macro cross-check: 1 enabled module(s) classified as \"Macros\", 2 macro(s) enumerated"))
check("cross-check is counted", b8Mismatch.diagnosticCount, 1)
ok("cross-check passes the html card gate", b8Mismatch.diagnosticCount > 0)

/* Both sides agree and both are enabled-only: a disabled macro module plus a
 * disabled macro must not make the counts drift apart. */
AppFootprint b8Agree = new AppFootprint()
b8Agree.modules.add(module("MacroModuleDescriptor", true))
b8Agree.modules.add(module("MacroModuleDescriptor", false))
MacroFootprint b8c = new MacroFootprint()
b8c.moduleEnabled = Boolean.TRUE
MacroFootprint b8d = new MacroFootprint()
b8d.moduleEnabled = Boolean.FALSE
b8Agree.macros.addAll([b8c, b8d])
b8Agree.finish()
check("cross-check quiet on agreement", b8Agree.diagnostics.size(), 0)
check("cross-check quiet leaves the counter at zero", b8Agree.diagnosticCount, 0)
ok("cross-check does not reach the html card gate", !(b8Agree.diagnosticCount > 0))
/* regression: comparing against macros.size() instead of enabledMacroCount
 * would fire on every app that ships a disabled macro module */
ok("regression cross-check ignores disabled macros",
    b8Agree.macros.size() != b8Agree.enabledMacroCount && b8Agree.diagnostics.isEmpty())
/* the same in the other direction: a disabled module must not be counted either */
AppFootprint b8Disabled = new AppFootprint()
b8Disabled.modules.add(module("MacroModuleDescriptor", false))
b8Disabled.finish()
check("disabled macro module is not classified", b8Disabled.categoryCount("Macros"), 0)
check("no macros, no module, no diagnostic", b8Disabled.diagnostics.size(), 0)

/* ---- 11. export payload accessors ----------------------------------------- */

Map<String, Object> nested = new LinkedHashMap<String, Object>()
nested.put("k", "v")
List<Object> rawRows = new ArrayList<Object>()
Map<String, Object> rowOne = new LinkedHashMap<String, Object>()
rowOne.put("pluginKey", "a.b")
Map<String, Object> rowTwo = new LinkedHashMap<String, Object>()
rowTwo.put("pluginKey", "c.d")
rawRows.add(rowOne)
rawRows.add("not a row")
rawRows.add(rowTwo)

Map<String, Object> accessors = new LinkedHashMap<String, Object>()
accessors.put("text", "  value  ")
accessors.put("blank", "   ")
accessors.put("num", Integer.valueOf(7))
accessors.put("big", Long.valueOf(1234567L))
accessors.put("numText", " 42 ")
accessors.put("junk", "abc")
accessors.put("boolTrue", Boolean.TRUE)
accessors.put("boolText", "TRUE")
accessors.put("nested", nested)
accessors.put("rows", rawRows)

check("str trims", PageExport.str(accessors, "text", "d"), "value")
check("str blank falls back", PageExport.str(accessors, "blank", "d"), "d")
check("str missing falls back", PageExport.str(accessors, "nope", "d"), "d")
check("str null source", PageExport.str(null, "text", "d"), "d")
check("lng number", PageExport.lng(accessors, "num"), 7L)
check("lng numeric text", PageExport.lng(accessors, "numText"), 42L)
check("lng junk is zero", PageExport.lng(accessors, "junk"), 0L)
check("lng missing is zero", PageExport.lng(accessors, "nope"), 0L)
check("flag boolean", PageExport.flag(accessors, "boolTrue"), true)
check("flag text", PageExport.flag(accessors, "boolText"), true)
check("flag junk", PageExport.flag(accessors, "junk"), false)
check("flag missing", PageExport.flag(accessors, "nope"), false)
check("sub reads a nested map", PageExport.sub(accessors, "nested").get("k"), "v")
check("sub missing is empty not null", PageExport.sub(accessors, "nope").size(), 0)
check("sub of a non-map is empty", PageExport.sub(accessors, "text").size(), 0)
check("rowsOf skips non-maps", PageExport.rowsOf(accessors, "rows").size(), 2)
check("rowsOf missing is empty", PageExport.rowsOf(accessors, "nope").size(), 0)
check("numberOf formats de", PageExport.numberOf(accessors, "big", Locale.GERMANY), "1.234.567")
check("numberOf formats en", PageExport.numberOf(accessors, "big", Locale.ENGLISH), "1,234,567")
check("numberOf of a missing key is a real zero", PageExport.numberOf(accessors, "nope", Locale.ENGLISH), "0")

/* ---- 12. measurement state ------------------------------------------------ */

AppFootprint noMacros = new AppFootprint()
check("usageState dimension off", PageExport.usageState(noMacros, true, false), PageExport.NOT_APPLICABLE)
check("usageState scan off", PageExport.usageState(noMacros, false, true), Cfp.DISABLED)
check("usageState without macros is measured", PageExport.usageState(noMacros, true, true), Cfp.MEASURED)

AppFootprint mixedApp = new AppFootprint()
MacroFootprint measuredMacro = new MacroFootprint()
measuredMacro.usageState = Cfp.MEASURED
MacroFootprint budgetMacro = new MacroFootprint()
budgetMacro.usageState = Cfp.BUDGET
mixedApp.macros.addAll([measuredMacro, budgetMacro])
check("usageState mixed is partial", PageExport.usageState(mixedApp, true, true), Cfp.PARTIAL)

AppFootprint noneMeasured = new AppFootprint()
MacroFootprint erroredMacro = new MacroFootprint()
erroredMacro.usageState = Cfp.ERROR
noneMeasured.macros.add(erroredMacro)
check("usageState nothing measured is budget", PageExport.usageState(noneMeasured, true, true), Cfp.BUDGET)

check("summaryState dimension off", PageExport.summaryState(false, true, false), PageExport.NOT_APPLICABLE)
check("summaryState scan off", PageExport.summaryState(true, false, false), Cfp.DISABLED)
check("summaryState partial", PageExport.summaryState(true, true, true), Cfp.PARTIAL)
check("summaryState measured", PageExport.summaryState(true, true, false), Cfp.MEASURED)

check("usageText not applicable", PageExport.usageText(PageExport.NOT_APPLICABLE, 0L, Locale.ENGLISH), Cfp.NA)
check("usageText measured", PageExport.usageText(Cfp.MEASURED, 1234L, Locale.GERMANY), "1.234")
check("usageText measured zero stays zero", PageExport.usageText(Cfp.MEASURED, 0L, Locale.ENGLISH), "0")
check("usageText partial marks a lower bound", PageExport.usageText(Cfp.PARTIAL, 12L, Locale.ENGLISH), "12 *")
check("usageText disabled", PageExport.usageText(Cfp.DISABLED, 0L, Locale.ENGLISH), "off")
check("usageText budget", PageExport.usageText(Cfp.BUDGET, 0L, Locale.ENGLISH), "n/m")
check("usageText error", PageExport.usageText(Cfp.ERROR, 0L, Locale.ENGLISH), "n/m")
ok("a budgeted zero is never the string zero",
    PageExport.usageText(Cfp.BUDGET, 0L, Locale.ENGLISH) != "0")

/* ---- 13. storage cell helpers --------------------------------------------- */

check("plainText strips tags", PageExport.plainText("<p><strong>KEEP</strong></p>"), "KEEP")
check("plainText decodes entities", PageExport.plainText("<p>a &amp; b &lt;c&gt; &quot;d&quot;</p>"), "a & b <c> \"d\"")
check("plainText nbsp is empty", PageExport.plainText("<p>&#160;</p>"), "")
check("plainText collapses whitespace", PageExport.plainText("<p>a\n\n   b</p>"), "a b")
check("plainText null", PageExport.plainText(null), "")
check("cellsOf counts open and self closed", PageExport.cellsOf("<td><p>a</p></td><td/><td><p>b</p></td>").size(), 3)
check("cellsOf self closed cell is empty", PageExport.cellsOf("<td/>").get(0), "")
check("cellsOf null row", PageExport.cellsOf(null).size(), 0)
check("headerIndex by name", PageExport.headerIndex(["<p>App</p>", "<p>App Key</p>"], PageExport.COL_KEY), 1)
check("headerIndex ignores case", PageExport.headerIndex(["<p>app key</p>"], PageExport.COL_KEY), 0)
check("headerIndex missing is minus one", PageExport.headerIndex(["<p>App</p>"], PageExport.COL_DECISION), -1)
check("errorDetail with message", PageExport.errorDetail(new IllegalStateException("boom")), "IllegalStateException: boom")
check("errorDetail without message", PageExport.errorDetail(new RuntimeException()), "RuntimeException")
check("decisionCell empty", PageExport.decisionCell(null), "<td><p>&#160;</p></td>")
check("decisionCell is never escaped", PageExport.decisionCell("<p><strong>KEEP</strong></p>"),
    "<td><p><strong>KEEP</strong></p></td>")


/* ---- 14. page fixtures ---------------------------------------------------- */

/* A page body carrying the export marker, exactly as a generated page does. */
String marked(String bodyHtml) {
    return "<p><em>Generated 2026-08-21 - export marker " + PageExport.MARKER +
        " - do not remove this line.</em></p>" + bodyHtml
}

String tableOf(List<String> headers, List<List<String>> rows) {
    StringBuilder out = new StringBuilder("<table><tbody><tr>")
    for (String header : headers) {
        out.append("<th><p>").append(header).append("</p></th>")
    }
    out.append("</tr>")
    for (List<String> row : rows) {
        out.append("<tr>")
        for (String cellHtml : row) {
            out.append("<td>").append(cellHtml).append("</td>")
        }
        out.append("</tr>")
    }
    out.append("</tbody></table>")
    return out.toString()
}

/* The ten column layout the export writes: key at index 1, decision at index 9. */
List<String> generatedHeaders() {
    List<String> headers = new ArrayList<String>()
    headers.addAll(["App", PageExport.COL_KEY, "Vendor", "Version", "Impact",
                    "Current Content", "Current Spaces", "Archived Content", "Status",
                    PageExport.COL_DECISION])
    return headers
}

List<String> generatedRow(String pluginKey, String decisionHtml) {
    List<String> cells = new ArrayList<String>()
    cells.addAll(["<p>App</p>", "<p>" + pluginKey + "</p>", "<p>Vendor</p>", "<p>2.4.1</p>",
                  "<p>Low</p>", "<p>0</p>", "<p>0</p>", "<p>0</p>", "<p>Enabled</p>", decisionHtml])
    return cells
}

List<List<String>> rowsOf(List<String> a, List<String> b) {
    List<List<String>> rows = new ArrayList<List<String>>()
    rows.add(a)
    if (b != null) {
        rows.add(b)
    }
    return rows
}

/* ---- 15. decision read: the three outcomes -------------------------------- */

DecisionRead noPage = new DecisionRead()
check("NONE is the default outcome", noPage.outcome, DecisionRead.NONE)
check("NONE carries no decisions", noPage.decisions.size(), 0)
ok("NONE allows the write", noPage.isWriteAllowed())

String goodPage = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>KEEP</p>"), generatedRow("c.d", "<p>REMOVE</p>"))))
DecisionRead parsed = PageExport.parseDecisions(goodPage)
check("PARSED outcome", parsed.outcome, DecisionRead.PARSED)
check("PARSED count", parsed.decisions.size(), 2)
check("PARSED value", parsed.decisions.get("a.b"), "<p>KEEP</p>")
check("PARSED second value", parsed.decisions.get("c.d"), "<p>REMOVE</p>")
ok("PARSED allows the write", parsed.isWriteAllowed())
check("PARSED reports no reason", parsed.reason, null)

/* an unannotated page is a legitimate zero, not a failure */
String unannotated = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>&#160;</p>"), generatedRow("c.d", "<p> </p>"))))
DecisionRead emptyParsed = PageExport.parseDecisions(unannotated)
check("unannotated page is PARSED", emptyParsed.outcome, DecisionRead.PARSED)
check("unannotated page has zero decisions", emptyParsed.decisions.size(), 0)
ok("unannotated page allows the write", emptyParsed.isWriteAllowed())

DecisionRead failedRead = new DecisionRead().fail("something is off")
check("FAILED outcome", failedRead.outcome, DecisionRead.FAILED)
ok("FAILED blocks the write", !failedRead.isWriteAllowed())
check("FAILED keeps the reason", failedRead.reason, "something is off")

/* a failure after a partial parse must not leave the partial result behind */
DecisionRead lateFailure = new DecisionRead()
lateFailure.decisions.put("a.b", "<p>KEEP</p>")
lateFailure.fail("late failure")
check("fail clears a partial parse", lateFailure.decisions.size(), 0)

Map<String, Object> readMap = parsed.asMap()
check("asMap outcome", readMap.get("outcome"), DecisionRead.PARSED)
check("asMap decision count", readMap.get("decisions"), Integer.valueOf(2))
check("asMap page version", readMap.get("pageVersion"), Integer.valueOf(0))

/* ---- 16. decision read: every failure trigger ----------------------------- */

List<String> brokenNames = new ArrayList<String>()
List<String> brokenPages = new ArrayList<String>()
def addBroken = { String name, String storage ->
    brokenNames.add(name)
    brokenPages.add(storage)
}

/* a page that collides on the title but was not produced by this export */
String foreignPage = tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>KEEP</p>"), generatedRow("c.d", "<p>REMOVE</p>")))

List<String> metricHeaders = new ArrayList<String>()
metricHeaders.addAll(["Metric", "Value"])
List<String> metricRow = new ArrayList<String>()
metricRow.addAll(["<p>Apps in report</p>", "<p>12</p>"])
String noDecisionTable = marked(tableOf(metricHeaders, rowsOf(metricRow, null)))

List<String> keyOnlyHeaders = new ArrayList<String>()
keyOnlyHeaders.addAll(["App", PageExport.COL_KEY, "Vendor"])
List<String> keyOnlyRow = new ArrayList<String>()
keyOnlyRow.addAll(["<p>App</p>", "<p>a.b</p>", "<p>Vendor</p>"])
String noDecisionColumn = marked(tableOf(keyOnlyHeaders, rowsOf(keyOnlyRow, null)))

List<String> stuntedRow = new ArrayList<String>()
stuntedRow.addAll(["<p>App</p>", "<p>c.d</p>", "<p>Vendor</p>"])
String shortRowPage = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>KEEP</p>"), stuntedRow)))

String duplicateKeyPage = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>KEEP</p>"), generatedRow("a.b", "<p>REMOVE</p>"))))

String truncatedPage = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "<p>KEEP</p>"), null))).replace("</tbody></table>", "")

addBroken("empty body", "")
addBroken("blank body", "   ")
addBroken("null body", (String) null)
addBroken("missing export marker", foreignPage)
addBroken("no table with both headers", noDecisionTable)
addBroken("table without the decision column", noDecisionColumn)
addBroken("short data row", shortRowPage)
addBroken("duplicate app key", duplicateKeyPage)
addBroken("truncated tbody", truncatedPage)

for (int i = 0; i < brokenNames.size(); i++) {
    String name = brokenNames.get(i)
    DecisionRead read = PageExport.parseDecisions(brokenPages.get(i))
    check("FAILED on " + name, read.outcome, DecisionRead.FAILED)
    ok("reason given on " + name, read.reason != null && !read.reason.trim().isEmpty())
    ok("no decisions leak from " + name, read.decisions.isEmpty())
    ok("write blocked on " + name, !read.isWriteAllowed())
}

/* the marker rule, stated on its own: a foreign page is never overwritten */
DecisionRead foreignRead = PageExport.parseDecisions(foreignPage)
check("title collision without the marker is FAILED", foreignRead.outcome, DecisionRead.FAILED)
ok("the marker is named in the reason", foreignRead.reason.contains(PageExport.MARKER))
ok("a foreign page is never overwritten", !foreignRead.isWriteAllowed())
/* the very same body with the marker parses, so it is the marker that decides */
check("the same body with the marker parses",
    PageExport.parseDecisions(marked(foreignPage)).outcome, DecisionRead.PARSED)

/* ---- 17. decision read: structure ----------------------------------------- */

/* Decision first, key in the middle: both are located by header name. */
List<String> movedHeaders = new ArrayList<String>()
movedHeaders.addAll([PageExport.COL_DECISION, "Vendor", "App", PageExport.COL_KEY, "Status"])
List<String> movedRowA = new ArrayList<String>()
movedRowA.addAll(["<p>KEEP</p>", "<p>Vendor</p>", "<p>App</p>", "<p>a.b</p>", "<p>Enabled</p>"])
List<String> movedRowB = new ArrayList<String>()
movedRowB.addAll(["<p>REMOVE</p>", "<p>Vendor</p>", "<p>App</p>", "<p>c.d</p>", "<p>Enabled</p>"])
String movedPage = marked(tableOf(movedHeaders, rowsOf(movedRowA, movedRowB)))
DecisionRead moved = PageExport.parseDecisions(movedPage)
check("moved columns still parse", moved.outcome, DecisionRead.PARSED)
check("moved columns keep every decision", moved.decisions.size(), 2)
check("decision found at index 0", moved.decisions.get("a.b"), "<p>KEEP</p>")
check("key found at index 3", moved.decisions.get("c.d"), "<p>REMOVE</p>")

/* a column inserted between key and decision must not orphan a note */
List<String> insertedHeaders = new ArrayList<String>()
insertedHeaders.addAll(["App", PageExport.COL_KEY, "New Column", PageExport.COL_DECISION])
List<String> insertedRow = new ArrayList<String>()
insertedRow.addAll(["<p>App</p>", "<p>a.b</p>", "<p>anything</p>", "<p>KEEP</p>"])
DecisionRead inserted = PageExport.parseDecisions(marked(tableOf(insertedHeaders, rowsOf(insertedRow, null))))
check("inserted column still parses", inserted.outcome, DecisionRead.PARSED)
check("inserted column keeps the decision", inserted.decisions.get("a.b"), "<p>KEEP</p>")

/* every tbody is read, not just the first: the orphan table is a second one */
List<String> orphanHeaders = new ArrayList<String>()
orphanHeaders.addAll([PageExport.COL_KEY, PageExport.COL_DECISION])
List<String> orphanRow = new ArrayList<String>()
orphanRow.addAll(["<p>gone.key</p>", "<p>KEEP - still needed by finance</p>"])
String twoTablePage = marked(
    tableOf(generatedHeaders(), rowsOf(generatedRow("a.b", "<p>KEEP</p>"), null)) +
    "<h2>Decisions Without a Matching App</h2>" +
    tableOf(orphanHeaders, rowsOf(orphanRow, null)))
DecisionRead twoTables = PageExport.parseDecisions(twoTablePage)
check("second tbody is read too", twoTables.decisions.size(), 2)
check("first table decision", twoTables.decisions.get("a.b"), "<p>KEEP</p>")
check("second table decision", twoTables.decisions.get("gone.key"), "<p>KEEP - still needed by finance</p>")

/* cell content comes back byte identical, nested markup and entities included */
String verbatim = "<p><strong>KEEP</strong> until Q4 &amp; then <em>review</em> - owner: ops</p>"
String verbatimPage = marked(tableOf(generatedHeaders(), rowsOf(generatedRow("a.b", verbatim), null)))
check("cell content survives verbatim", PageExport.parseDecisions(verbatimPage).decisions.get("a.b"), verbatim)
String paddedPage = marked(tableOf(generatedHeaders(),
    rowsOf(generatedRow("a.b", "\n    " + verbatim + "   \n"), null)))
check("padding is trimmed, markup is not",
    PageExport.parseDecisions(paddedPage).decisions.get("a.b"), verbatim)
/* an entity must not be decoded on the way back in */
ok("entities are not decoded", PageExport.parseDecisions(verbatimPage).decisions.get("a.b").contains("&amp;"))

/* ---- 18. red before green: the pre-rewrite parser as a control ------------ */

/* Reproduces the shape of confluence-addon-analysis.groovy: only the first
 * <tbody> is read (its defect at line 659), the columns are addressed by fixed
 * index (674-678), every exception is swallowed, and the result is a bare map
 * with no outcome and no failure channel. Nothing else in this suite uses it -
 * it exists so the assertions above can be shown to discriminate on every run. */
class LegacyControl {

    static Map<String, String> parse(String storage) {
        Map<String, String> decisions = new LinkedHashMap<String, String>()
        try {
            Matcher bodyMatcher = PageExport.TBODY.matcher(storage)
            if (!bodyMatcher.find()) {
                return decisions
            }
            List<String> rows = new ArrayList<String>()
            Matcher rowMatcher = PageExport.ROW.matcher(bodyMatcher.group(1))
            while (rowMatcher.find()) {
                rows.add(rowMatcher.group(1))
            }
            for (int i = 1; i < rows.size(); i++) {
                List<String> cells = PageExport.cellsOf(rows.get(i))
                decisions.put(PageExport.plainText(cells.get(1)), cells.get(9).trim())
            }
        } catch (Throwable ignored) {
            /* the whole point: a failed read is indistinguishable from an
             * unannotated page, because both hand back an empty map */
        }
        return decisions
    }

    /* The most generous refusal signal a caller can derive from that shape.
     * It is the only one available, and it is wrong in both directions. */
    static boolean refusesWrite(String storage) {
        return parse(storage).isEmpty()
    }
}

int parserRefusals = 0
int controlRefusals = 0
for (int i = 0; i < brokenNames.size(); i++) {
    if (!PageExport.parseDecisions(brokenPages.get(i)).isWriteAllowed()) {
        parserRefusals++
    }
    if (LegacyControl.refusesWrite(brokenPages.get(i))) {
        controlRefusals++
    }
}
check("parser refuses every malformed page", parserRefusals, brokenNames.size())
check("control refuses only some of them", controlRefusals, 6)
ok("the write gate discriminates", parserRefusals > controlRefusals)
println "red-before-green: " + brokenNames.size() + " malformed fixtures, parser refuses " +
    parserRefusals + ", control refuses " + controlRefusals

/* the three malformed pages the control would have written */
check("control writes over a foreign page", LegacyControl.parse(foreignPage).size(), 2)
ok("parser refuses the foreign page", !PageExport.parseDecisions(foreignPage).isWriteAllowed())
check("control writes a partial parse on a short row", LegacyControl.parse(shortRowPage).size(), 1)
ok("parser refuses the short row", !PageExport.parseDecisions(shortRowPage).isWriteAllowed())
check("control silently drops one of two duplicate decisions", LegacyControl.parse(duplicateKeyPage).size(), 1)
ok("parser refuses the duplicate key", !PageExport.parseDecisions(duplicateKeyPage).isWriteAllowed())

/* the two well formed pages the control reads wrong */
check("control loses every decision when a column moves", LegacyControl.parse(movedPage).size(), 0)
check("parser finds them by header name", PageExport.parseDecisions(movedPage).decisions.size(), 2)
check("control reads only the first tbody", LegacyControl.parse(twoTablePage).size(), 1)
check("parser reads every tbody", PageExport.parseDecisions(twoTablePage).decisions.size(), 2)

/* ---- 19. rendering: n/m is never a zero ----------------------------------- */

Map<String, Object> subMap(Map<String, Object> parent, String key) {
    Map<String, Object> child = new LinkedHashMap<String, Object>()
    parent.put(key, child)
    return child
}

Map<String, Object> buildPayload() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>()

    Map<String, Object> report = new LinkedHashMap<String, Object>()
    report.put("generatedAt", "2026-08-21T09:00:00Z")
    report.put("version", "4.3")
    payload.put("report", report)

    Map<String, Object> options = new LinkedHashMap<String, Object>()
    options.put("includeSystem", Boolean.FALSE)
    options.put("includeDisabled", Boolean.TRUE)
    options.put("includeArchived", Boolean.TRUE)
    options.put("scanUsage", Boolean.TRUE)
    options.put("scanAliases", Boolean.FALSE)
    options.put("scanBudgetMs", Long.valueOf(120000L))
    payload.put("options", options)

    Map<String, Object> summary = new LinkedHashMap<String, Object>()
    summary.put("apps", Integer.valueOf(2))
    summary.put("disabledApps", Integer.valueOf(0))
    summary.put("decommissionCandidates", Integer.valueOf(1))
    summary.put("diagnostics", Integer.valueOf(0))
    Map<String, Object> impact = new LinkedHashMap<String, Object>()
    impact.put("critical", Integer.valueOf(1))
    summary.put("impact", impact)
    Map<String, Object> capabilities = new LinkedHashMap<String, Object>()
    capabilities.put("providedMacros", Integer.valueOf(4))
    capabilities.put("enabledMacros", Integer.valueOf(4))
    summary.put("capabilities", capabilities)
    Map<String, Object> current = new LinkedHashMap<String, Object>()
    current.put("partial", Boolean.FALSE)
    current.put("usedAppMacros", Integer.valueOf(3))
    summary.put("current", current)
    Map<String, Object> archived = new LinkedHashMap<String, Object>()
    archived.put("partial", Boolean.FALSE)
    summary.put("archived", archived)
    summary.put("nativeUserMacros", new LinkedHashMap<String, Object>())
    payload.put("summary", summary)

    List<Map<String, Object>> apps = new ArrayList<Map<String, Object>>()

    Map<String, Object> budgetApp = new LinkedHashMap<String, Object>()
    budgetApp.put("pluginKey", "com.vendor.budget")
    budgetApp.put("displayName", "Budget App")
    budgetApp.put("vendor", "Vendor")
    budgetApp.put("version", "2.4.1")
    budgetApp.put("enabled", Boolean.TRUE)
    budgetApp.put("impactLabel", "Review required")
    budgetApp.put("currentState", Cfp.BUDGET)
    budgetApp.put("archivedState", Cfp.BUDGET)
    budgetApp.put("currentContent", Long.valueOf(0L))
    budgetApp.put("currentSpaces", Long.valueOf(0L))
    budgetApp.put("archivedContent", Long.valueOf(0L))
    apps.add(budgetApp)

    Map<String, Object> measuredApp = new LinkedHashMap<String, Object>()
    measuredApp.put("pluginKey", "com.vendor.measured")
    measuredApp.put("displayName", "Measured App")
    measuredApp.put("vendor", "Vendor")
    measuredApp.put("version", "2.4.1")
    measuredApp.put("enabled", Boolean.TRUE)
    measuredApp.put("impactLabel", "No detectable footprint")
    measuredApp.put("currentState", Cfp.MEASURED)
    measuredApp.put("archivedState", Cfp.MEASURED)
    measuredApp.put("currentContent", Long.valueOf(0L))
    measuredApp.put("currentSpaces", Long.valueOf(0L))
    measuredApp.put("archivedContent", Long.valueOf(0L))
    apps.add(measuredApp)

    payload.put("apps", apps)
    return payload
}

String rowFor(String storage, String pluginKey) {
    for (String fragment : storage.split("<tr>")) {
        if (fragment.contains(pluginKey)) {
            return fragment
        }
    }
    return ""
}

Map<String, Object> payload = buildPayload()
ExportOutcome fresh = PageExport.render(payload, new DecisionRead(), Locale.GERMANY)

String budgetRow = rowFor(fresh.storage, "com.vendor.budget")
String measuredRow = rowFor(fresh.storage, "com.vendor.measured")
ok("budget row found", !budgetRow.isEmpty())
ok("budget row renders n/m", budgetRow.contains("<p>n/m</p>"))
ok("budget row shows no zero", !budgetRow.contains("<p>0</p>"))
ok("measured row found", !measuredRow.isEmpty())
ok("measured zero renders as zero", measuredRow.contains("<p>0</p>"))
ok("measured row shows no n/m", !measuredRow.contains("<p>n/m</p>"))
ok("page export renders the decommission-candidate count",
    fresh.storage.contains("Decommission candidates") && fresh.storage.contains("<p>1</p>"))
ok("the page carries the export marker", fresh.storage.contains(PageExport.MARKER))
check("a fresh page carries no decisions", fresh.decisionsRead, 0)
check("a fresh page carries no orphans", fresh.orphanKeys.size(), 0)
check("a fresh page raises no warning", fresh.warnings.size(), 0)

/* an app row without a state must default to not measured, never to zero */
Map<String, Object> statelessPayload = buildPayload()
((List<Map<String, Object>>) statelessPayload.get("apps")).get(1).remove("currentState")
ExportOutcome stateless = PageExport.render(statelessPayload, new DecisionRead(), Locale.GERMANY)
ok("a missing state falls back to n/m",
    rowFor(stateless.storage, "com.vendor.measured").contains("<p>n/m</p>"))

/* round trip: a freshly generated page re-reads as PARSED with zero decisions */
DecisionRead freshRead = PageExport.parseDecisions(fresh.storage)
check("a generated page re-reads as PARSED", freshRead.outcome, DecisionRead.PARSED)
check("a generated page re-reads zero decisions", freshRead.decisions.size(), 0)
ok("a generated page allows the next write", freshRead.isWriteAllowed())

/* ---- 20. rendering: carry-over and loss reporting ------------------------- */

DecisionRead carried = new DecisionRead()
carried.outcome = DecisionRead.PARSED
carried.decisions.put("com.vendor.budget", "<p><strong>KEEP</strong> until Q4 &amp; then <em>review</em></p>")
carried.decisions.put("com.vendor.measured", "<p>REMOVE</p>")
carried.decisions.put("com.vendor.gone", "<p>KEEP - still needed by finance</p>")

ExportOutcome carriedOut = PageExport.render(buildPayload(), carried, Locale.GERMANY)
check("decisions read", carriedOut.decisionsRead, 3)
check("decisions carried", carriedOut.decisionsCarried, 2)
ok("fewer carried than read", carriedOut.decisionsCarried < carriedOut.decisionsRead)
check("the orphan is named", new ArrayList<String>(carriedOut.orphanKeys), ["com.vendor.gone"])
check("one warning raised", carriedOut.warnings.size(), 1)
ok("the warning counts both sides", carriedOut.warnings.get(0).contains("1 of 3 decision(s)"))
ok("the warning is rendered on the page", carriedOut.storage.contains("Carry-over warning"))
ok("the orphan section is rendered", carriedOut.storage.contains("Decisions Without a Matching App"))
ok("the orphan key is on the page", carriedOut.storage.contains("com.vendor.gone"))
ok("the carried decision is not escaped", carriedOut.storage.contains("<td><p><strong>KEEP</strong> until Q4 &amp; then <em>review</em></p></td>"))

/* the orphan survives the round trip: nothing is lost by being unmatched */
DecisionRead reread = PageExport.parseDecisions(carriedOut.storage)
check("re-read outcome", reread.outcome, DecisionRead.PARSED)
check("re-read keeps all three decisions", reread.decisions.size(), 3)
check("re-read is verbatim", reread.decisions.get("com.vendor.budget"),
    "<p><strong>KEEP</strong> until Q4 &amp; then <em>review</em></p>")
check("re-read keeps the orphan", reread.decisions.get("com.vendor.gone"),
    "<p>KEEP - still needed by finance</p>")
ok("re-read allows the next write", reread.isWriteAllowed())

/* second round trip: rendering the re-read result carries everything again */
ExportOutcome secondRun = PageExport.render(buildPayload(), reread, Locale.GERMANY)
check("second run reads three", secondRun.decisionsRead, 3)
check("second run carries two", secondRun.decisionsCarried, 2)
check("second run keeps the same orphan", new ArrayList<String>(secondRun.orphanKeys), ["com.vendor.gone"])

/* ---- N. title search tokens (OP-950 Q2) ---------------------------------- */

/* The defect this replaces: the parent lookup was getPage(spaceKey, title), an
   exact match, so typing "footprint" never found the export page. The token
   helper is the pure half of the replacement - one clause per whole word, with
   the trailing star appended by the caller to the last one only. */
check("null query has no tokens", PageExport.titleTokens(null), [])
check("empty query has no tokens", PageExport.titleTokens(""), [])
check("one word", PageExport.titleTokens("footprint"), ["footprint"])
check("two words", PageExport.titleTokens("App Footprint"), ["App", "Footprint"])
check("the real export title", PageExport.titleTokens("Confluence App Footprint - Executive Summary"),
    ["Confluence", "App", "Footprint", "Executive", "Summary"])
check("runs of whitespace collapse", PageExport.titleTokens("  spaced   out  "), ["spaced", "out"])
check("punctuation alone yields nothing", PageExport.titleTokens(" --- "), [])
check("punctuation splits like a tokeniser", PageExport.titleTokens("Q1-2026 Review"), ["Q1", "2026", "Review"])
check("digits are words", PageExport.titleTokens("2026"), ["2026"])

/* Non-ASCII letters are letters. A German title must not lose its umlauts, which
   would turn a searchable word into one that matches nothing. */
check("umlauts survive", PageExport.titleTokens("Bericht \u00fcber Pr\u00fcfung"), ["Bericht", "\u00fcber", "Pr\u00fcfung"])

/* No character with a meaning in the query language survives into a term, so the
   caller's appended star is the only wildcard in the query - and a term can never
   begin with one, which is the behaviour this project refuses to ship unverified. */
check("wildcards do not survive", PageExport.titleTokens("a*b?c"), ["a", "b", "c"])
check("quotes and backslashes do not survive", PageExport.titleTokens("say \"hi\"\\now"), ["say", "hi", "now"])
ok("no token carries a star", PageExport.titleTokens("a*b ?c d*").every { !it.contains("*") })
ok("no token carries a question mark", PageExport.titleTokens("a*b ?c d*").every { !it.contains("?") })

/* The clause count is bounded: every token becomes one clause of an AND query. */
check("token count is capped",
    PageExport.titleTokens("one two three four five six seven eight nine ten").size(),
    PageExport.MAX_TITLE_TOKENS)
check("the cap keeps the first words",
    PageExport.titleTokens("one two three four five six seven eight nine ten").get(0), "one")

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

/* ---- parent position: the ancestor chain --------------------------------- */

/* The Confluence endpoint reads Page.getAncestors() after the save and hands the
   ids in here, so the rule stays free of Confluence types and is checked offline.
   Ancestors run from the root of the space downwards, so the direct parent is the
   last entry. */

Map<String, Object> aNull = PageExport.innermostAncestor((List<String>) null)
check("no chain at all is not a measurement", aNull.get("measured"), Boolean.FALSE)
check("no chain names no parent", aNull.get("parentId"), null)

/* An empty chain IS a measurement: the read answered and the page sits at the top
   level of the space. It must not collapse into the unreadable case. */
Map<String, Object> aEmpty = PageExport.innermostAncestor(new ArrayList<String>())
check("an empty chain is a measurement", aEmpty.get("measured"), Boolean.TRUE)
check("an empty chain means the top level of the space", aEmpty.get("parentId"), null)
ok("an unreadable chain and a top-level page are told apart",
    aNull.get("measured") != aEmpty.get("measured"))

Map<String, Object> aChain = PageExport.innermostAncestor(["100", "200", "300"] as List<String>)
check("the direct parent is the last entry", aChain.get("parentId"), "300")
check("a chain is a measurement", aChain.get("measured"), Boolean.TRUE)

check("a single ancestor is the direct parent",
    PageExport.innermostAncestor(["100"] as List<String>).get("parentId"), "100")
check("padding is stripped from the id",
    PageExport.innermostAncestor([" 100 "] as List<String>).get("parentId"), "100")
check("a null tail entry falls back to the last usable id",
    PageExport.innermostAncestor(["100", null] as List<String>).get("parentId"), "100")
check("a blank tail entry falls back to the last usable id",
    PageExport.innermostAncestor(["100", "   "] as List<String>).get("parentId"), "100")
check("a chain of nothing but blanks names no parent but stays measured",
    PageExport.innermostAncestor([null, "  "] as List<String>).get("parentId"), null)
check("a chain of nothing but blanks is still a measurement",
    PageExport.innermostAncestor([null, "  "] as List<String>).get("measured"), Boolean.TRUE)

/* The chain feeds the verdict, so the unreadable case has to arrive as unknown
   and the top-level case as false. That is the whole point of the split. */
check("an unreadable chain becomes unknown, never false",
    PageExport.parentOutcome("555", aNull.get("measured") == Boolean.TRUE,
        (String) aNull.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_UNKNOWN)
check("a measured top-level page becomes false, never unknown",
    PageExport.parentOutcome("555", aEmpty.get("measured") == Boolean.TRUE,
        (String) aEmpty.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_FALSE)
check("a measured chain that names the requested parent becomes true",
    PageExport.parentOutcome("300", aChain.get("measured") == Boolean.TRUE,
        (String) aChain.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_TRUE)
check("a measured chain that names a grandparent is not a match",
    PageExport.parentOutcome("200", aChain.get("measured") == Boolean.TRUE,
        (String) aChain.get("parentId"), null).get("applied"), PageExport.PARENT_APPLIED_FALSE)

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
   report makes on purpose". The stylesheet defined .diag twice on one line and the
   second rule silently overrode the informational background, so every Measurement
   notes box was a warning whatever it contained.

   The assertions are on the emitted class, not on the text. */

check("observations only: informational", Cfp.diagClass(0, 0), Cfp.DIAG_INFO)
check("a macro scan skipped by the budget: warning", Cfp.diagClass(3, 0), Cfp.DIAG_WARN)
check("a suppressed read error: warning", Cfp.diagClass(0, 1), Cfp.DIAG_WARN)
check("both a budget skip and a read error: warning", Cfp.diagClass(3, 1), Cfp.DIAG_WARN)
check("a single budget skip is enough", Cfp.diagClass(1, 0), Cfp.DIAG_WARN)
check("a single read error is enough", Cfp.diagClass(0, 1), Cfp.DIAG_WARN)

/* Warning wins over an observation standing next to it: a real limitation is not
   made less true by a deliberate statement in the same box. */
ok("a box carrying both kinds is a warning",
    Cfp.diagBoxShown(2, 12) && Cfp.diagClass(2, 4) == Cfp.DIAG_WARN)

/* The exact case the Director reported: ten observations, nothing failed, nothing
   suppressed. The box appears, and it is informational. */
ok("ten observations and nothing else: the box shows", Cfp.diagBoxShown(0, 10))
check("ten observations and nothing else: informational", Cfp.diagClass(0, 0), Cfp.DIAG_INFO)

/* No number of observations can colour the box. They are not an input to the
   decision at all, which is what makes that impossible rather than unlikely. */
check("observations are not an input to the colour", Cfp.diagClass(0, 0), Cfp.diagClass(0, 0))
ok("an observation-only box is never a warning",
    Cfp.diagClass(0, 0) != Cfp.DIAG_WARN)

/* Visibility is unchanged: the box is not hidden and not split in two. */
ok("a box with nothing at all does not appear", !Cfp.diagBoxShown(0, 0))
ok("a budget skip alone makes the box appear", Cfp.diagBoxShown(1, 0))
ok("a diagnostic alone makes the box appear", Cfp.diagBoxShown(0, 1))

/* Both variants keep the base class, so the box geometry and the list rules in the
   stylesheet apply to either colour. */
ok("the informational variant keeps the base box class", Cfp.DIAG_INFO.startsWith("diag "))
ok("the warning variant keeps the base box class", Cfp.DIAG_WARN.startsWith("diag "))
ok("the two variants are different classes", Cfp.DIAG_INFO != Cfp.DIAG_WARN)
ok("the informational variant carries its own modifier", Cfp.DIAG_INFO.contains("diag-info"))
ok("the warning variant carries its own modifier", Cfp.DIAG_WARN.contains("diag-warn"))
ok("neither variant is the bare base class", Cfp.DIAG_INFO != "diag" && Cfp.DIAG_WARN != "diag")
ok("the informational variant is not the warning modifier", !Cfp.DIAG_INFO.contains("diag-warn"))
ok("the warning variant is not the informational modifier", !Cfp.DIAG_WARN.contains("diag-info"))

/* Red before green. The control is the stylesheet that shipped: one class for every
   box, so the colour could not follow the content. */
def controlDiagClass = { int budgetSkipCount, int readErrorCount -> return "diag" }
List<List<Integer>> diagCases = [[0, 0], [3, 0], [0, 1], [3, 1], [1, 0]]
int diagColourDiffs = 0
diagCases.each { List<Integer> row ->
    if (controlDiagClass(row[0], row[1]) == Cfp.diagClass(row[0], row[1])) {
        return
    }
    diagColourDiffs++
}
ok("the single-class control cannot express either variant", diagColourDiffs == diagCases.size())
ok("the control paints an observation-only box the same as a degraded one",
    controlDiagClass(0, 0) == controlDiagClass(3, 1) && Cfp.diagClass(0, 0) != Cfp.diagClass(3, 1))

/* ---- 26. service-locator source contract ---------------------------------- */

File endpointSource = new File("confluence/confluenceDCappFootprint.groovy")
if (!endpointSource.isFile()) {
    endpointSource = new File("../confluenceDCappFootprint.groovy")
}
ok("service-locator contract can read the endpoint source", endpointSource.isFile())
String endpointText = endpointSource.isFile() ? endpointSource.getText("UTF-8") : ""
ok("no deprecated PageManager lookup remains", !endpointText.contains("pageManager.getPage("))
ok("no deprecated SpaceManager lookup remains", !endpointText.contains("spaceManager.getSpace("))
ok("no deprecated getAllSpaces call remains", !endpointText.contains(".getAllSpaces("))
ok("no deprecated SettingsManager type remains",
    !(endpointText =~ /\bSettingsManager\b/).find())
ok("deprecation warnings are not suppressed", !endpointText.contains('SuppressWarnings("deprecation")'))
check("all title lookups use PageService", endpointText.count("pageService.getTitleAndSpaceKeyPageLocator("), 5)
check("all id lookups use PageService", endpointText.count("pageService.getIdPageLocator("), 2)
check("the space lookup uses SpaceService", endpointText.count("spaceService.getKeySpaceLocator("), 1)
ok("persistence PageService is imported",
    endpointText.contains("import com.atlassian.confluence.content.service.PageService"))
ok("persistence SpaceService is imported",
    endpointText.contains("import com.atlassian.confluence.content.service.SpaceService"))
ok("API SpaceService is imported with a distinct name",
    endpointText.contains("import com.atlassian.confluence.api.service.content.SpaceService as ApiSpaceService"))
ok("API Expansion is imported for an explicitly typed empty varargs call",
    endpointText.contains("import com.atlassian.confluence.api.model.Expansion"))
ok("API SpaceFinder is imported for ScriptRunner static checking",
    endpointText.contains("import com.atlassian.confluence.api.service.content.SpaceService.SpaceFinder"))
/* These four assertions used to pin the paginated API-layer space read: the
   explicitly typed SpaceFinder, the CURRENT restriction, fetchMany and hasMore.
   They are inverted rather than deleted, because the path they described is the
   one measured broken on a customer instance under OP-1063: resolving
   api.service.content.SpaceService throws, so the stage refused every time it was
   opened. What has to hold now is that nothing resolves or calls that type again,
   while the imports stay with the reason attached. */
check("the API space finder is no longer resolved",
    endpointText.count("ComponentLocator.getComponent(ApiSpaceService.class)"), 0)
check("no API space finder is constructed",
    endpointText.count("apiSpaceService.find("), 0)
check("no API-layer status restriction is applied",
    endpointText.count(".withStatus(ApiSpaceStatus.CURRENT)"), 0)
check("no API-layer pagination remains", endpointText.count("fetchMany("), 0)
check("no API-layer paging check remains", endpointText.count("spacePage.hasMore()"), 0)
ok("the retired API space read names the type rather than the whole layer",
    endpointText.contains("com.atlassian.confluence.api.service.content.SpaceService. That concrete type is") &&
    endpointText.contains("api.service.settings"))
ok("GlobalSettingsManager is imported",
    endpointText.contains("import com.atlassian.confluence.setup.settings.GlobalSettingsManager"))
check("all settings reads resolve GlobalSettingsManager",
    endpointText.count("ComponentLocator.getComponent(GlobalSettingsManager.class)"), 3)
check("main report renders the instance name once",
    endpointText.count('<div><strong>Instance:</strong> ${esc(instanceSiteTitle ?: Cfp.NA)}</div>'), 1)
check("main report renders the Base URL once",
    endpointText.count('<div><strong>Base URL:</strong> <span class="mono">${esc(instanceBaseUrl ?: Cfp.NA)}</span></div>'), 1)
check("main report renders the Confluence version once",
    endpointText.count('<div><strong>Confluence:</strong> ${esc(instanceVersion ?: Cfp.NA)} (build ${esc(instanceBuild ?: Cfp.NA)})</div>'), 1)
ok("main report renders the active Confluence scan options",
    endpointText.contains('<div><strong>Options:</strong> <span class="mono">includeSystem=${includeSystem} includeDisabled=${includeDisabled} includeArchived=${includeArchived} includeModules=${includeModules} scanUsage=${scanUsage} scanAliases=${scanAliases} scanBudgetMs=${scanBudgetMs}</span></div>'))
ok("JSON report exports the Confluence Base URL",
    endpointText.contains('exportReport.put("baseUrl", instanceBaseUrl)'))
ok("Confluence CSV exports relative impact evidence",
    endpointText.contains("impact,impactMaxPercent,impactPartial,impactReasons,impactDimensions"))
ok("Confluence counts the disabled usage scan explicitly",
    endpointText.contains("int notScannedApps = 0"))
ok("Confluence renders a not-scanned impact counter",
    endpointText.contains('<span class="badge badge-none">NOT SCANNED ${notScannedApps}</span>'))
ok("Confluence uses the shared 50 percent band",
    endpointText.contains('static final BigDecimal CRITICAL_PERCENT = new BigDecimal("50")'))
ok("Confluence uses the shared 20 percent band",
    endpointText.contains('static final BigDecimal HIGH_PERCENT = new BigDecimal("20")'))
ok("Confluence uses the shared 5 percent band",
    endpointText.contains('static final BigDecimal MEDIUM_PERCENT = new BigDecimal("5")'))
ok("Confluence contains no absolute impact count thresholds",
    !(endpointText =~ /(?:CRITICAL|HIGH|MEDIUM)_(?:CONTENT|ASSOCIATIONS|SPACES|ISSUES|PROJECTS|FIELDS|WORKFLOWS)/).find())
ok("Confluence decommission candidates use the shared guarded predicate",
    endpointText.contains("ImpactPolicy.isDecommissionCandidate(app.systemProvided, impact)"))
ok("Confluence system metadata failures remain unknown",
    endpointText.contains("app.systemProvided = null"))
ok("Confluence includeArchived defaults to false",
    endpointText.contains('Cfp.booleanParam(queryParams, "includeArchived", false)'))
ok("Confluence Archived button loads archive evidence on demand",
    endpointText.contains('archivedOverrides.put("includeArchived", includeArchived ? null : "true")'))
ok("Confluence preserves the archive opt-in across other report links",
    endpointText.contains('includeArchived: includeArchived ? "true" : null'))
ok("Confluence only inventories archived spaces after archive opt-in",
    (endpointText =~ /if \(includeArchived\) \{\s*try \{\s*archivedSpaceKeys\.addAll\(spaceManager\.getAllSpaceKeys\(SpaceStatus\.ARCHIVED\)\)/).find())
ok("Confluence reports archive inventory as off until requested",
    endpointText.contains("includeArchived ? num(archivedSpaceKeys.size()) + ' archived spaces' : 'archived spaces off'") &&
    endpointText.contains("archivedSpaces: includeArchived ? archivedSpaceKeys.size() : null"))
ok("Confluence JSON summary masks archive usage until requested",
    endpointText.contains("usedAppMacros: archiveUsageEnabled ? totalArchivedUsedMacros : null") &&
    endpointText.contains("archivedUsed: archiveUsageEnabled ? archivedUsedUserMacros : null"))
ok("Confluence Macro CSV masks archived counters and exports archive state",
    endpointText.contains("archivedContent,archivedSpaces,archivedState") &&
    !endpointText.contains("csv.append(macro.getArchivedContentCount()).append(\",\")"))
ok("Confluence App CSV masks archived counters and exports archive state",
    endpointText.contains("archivedSpaces,archivedComplete,archivedState,diagnostics") &&
    !endpointText.contains("csv.append(app.archivedUsedMacroCount).append(\",\")"))
ok("Confluence renders a decommission-candidate notice",
    endpointText.contains("Decommission candidates ("))
ok("Confluence JSON summary exports the candidate count",
    endpointText.contains("decommissionCandidates: decommissionCandidates.size()"))
ok("Confluence page-export summary exports the candidate count",
    endpointText.contains('exportSummary.put("decommissionCandidates", Integer.valueOf(decommissionCandidates.size()))'))

/* ---- 27. the space picker reads the database, never a Spring proxy -------- */

/* The picker used to resolve com.atlassian.confluence.api.service.content.SpaceService.
   That concrete type is a Spring AOP proxy and its resolution throws inside a
   ScriptRunner endpoint, so the stage refused on every instance it was opened on.
   These assertions hold the replacement: the list comes from a SELECT on SPACES,
   the status is a BOUND parameter, and every way the read can fail stays a failed
   read rather than becoming an empty or an unrestricted list. */

def pickerShape = { List<String> missing, String failure ->
    Map<String, Object> shape = new LinkedHashMap<String, Object>()
    shape.put("table", "spaces")
    shape.put("missing", missing)
    shape.put("failure", failure)
    return shape
}

def pickerFound = { List<Map<String, String>> rows, boolean truncated, String failure ->
    Map<String, Object> found = new LinkedHashMap<String, Object>()
    found.put("rows", rows)
    found.put("truncated", Boolean.valueOf(truncated))
    found.put("failure", failure)
    found.put("cap", Integer.valueOf(SpaceCatalog.CAP))
    return found
}

def pickerRow = { String key, String name ->
    Map<String, String> row = new LinkedHashMap<String, String>()
    row.put("spacekey", key)
    row.put("spacename", name)
    return row
}

/* --- the statement itself: SELECT only, and the status is bound ----------- */

ok("picker verifies the column it restricts on", SpaceCatalog.COLUMNS.contains("spacestatus"))
ok("picker verifies the columns it reads",
    SpaceCatalog.COLUMNS.containsAll(["spacekey", "spacename"]))
ok("picker restricts on the space status", SpaceCatalog.SQL.contains("s.spacestatus = ?"))
check("picker binds exactly one value", SpaceCatalog.SQL.count("?"), 1)
ok("picker never pastes the status into the statement", !SpaceCatalog.SQL.contains("CURRENT"))
check("the bound status is the stored spelling of SpaceStatus.CURRENT",
    SpaceCatalog.STATUS_CURRENT, "CURRENT")
ok("picker statement is a SELECT", SpaceCatalog.SQL.trim().startsWith("SELECT "))
ok("picker statement writes nothing",
    !(SpaceCatalog.SQL.toUpperCase(Locale.ROOT) =~ /\b(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|TRUNCATE)\b/).find())
ok("picker orders in SQL by the order it announces",
    SpaceCatalog.SQL.contains("ORDER BY LOWER(s.spacename), LOWER(s.spacekey)") &&
    SpaceCatalog.ORDER == "space name, then space key")
ok("picker puts no string literal into a text expression", !SpaceCatalog.SQL.contains("''"))

/* --- the list is built ---------------------------------------------------- */

Map<String, Object> pickerOk = SpaceCatalog.spaceList(pickerShape([], null),
    pickerFound([pickerRow("DOCS", "Documentation"), pickerRow("OPS", "  "), pickerRow(null, "orphan")],
        false, null))
check("a successful read reports ok", pickerOk.get("ok"), Boolean.TRUE)
check("a successful read carries no error", pickerOk.get("error"), null)
check("a successful read lists every row that names a space",
    ((List<Map<String, Object>>) pickerOk.get("spaces")).size(), 2)
check("a listed space carries its key",
    ((List<Map<String, Object>>) pickerOk.get("spaces")).get(0).get("key"), "DOCS")
check("a listed space carries its name",
    ((List<Map<String, Object>>) pickerOk.get("spaces")).get(0).get("name"), "Documentation")
check("a nameless space is labelled with its key",
    ((List<Map<String, Object>>) pickerOk.get("spaces")).get(1).get("name"), "OPS")
check("a row without a key costs itself and not the list",
    ((List<Map<String, Object>>) pickerOk.get("spaces")).size(), 2)
check("a successful read carries the cap it read under", pickerOk.get("cap"), Integer.valueOf(SpaceCatalog.CAP))
check("a successful read carries the ordering the cap cut by", pickerOk.get("order"), SpaceCatalog.ORDER)
check("an uncut read says so", pickerOk.get("truncated"), Boolean.FALSE)

Map<String, Object> pickerCut = SpaceCatalog.spaceList(pickerShape([], null),
    pickerFound([pickerRow("A", "Alpha")], true, null))
check("a cut read says so", pickerCut.get("truncated"), Boolean.TRUE)
check("a cut read is still a successful read", pickerCut.get("ok"), Boolean.TRUE)

/* --- a missing column is named, and never becomes a list ------------------ */

Map<String, Object> pickerMissing = SpaceCatalog.spaceList(pickerShape(["spacestatus"], null), null)
check("a missing column is a failed read", pickerMissing.get("ok"), Boolean.FALSE)
ok("a missing column is named", String.valueOf(pickerMissing.get("error")).contains("spacestatus"))
ok("a missing column names the table it was expected in",
    String.valueOf(pickerMissing.get("error")).contains("spaces"))
ok("a missing column is not an instance without spaces",
    String.valueOf(pickerMissing.get("error")).contains(SpaceCatalog.NOT_EMPTY))
check("a missing column yields no list at all",
    ((List<Map<String, Object>>) pickerMissing.get("spaces")).size(), 0)

Map<String, Object> pickerTwoMissing = SpaceCatalog.spaceList(
    pickerShape(["spacename", "spacestatus"], null), null)
ok("both missing columns are named",
    String.valueOf(pickerTwoMissing.get("error")).contains("spacename, spacestatus"))

check("a clean shape reports no problem", SpaceCatalog.shapeProblem(pickerShape([], null)), null)
ok("a single missing column is named in the singular",
    SpaceCatalog.shapeProblem(pickerShape(["spacekey"], null)).contains("the column spacekey"))
ok("several missing columns are named in the plural",
    SpaceCatalog.shapeProblem(pickerShape(["spacekey", "spacename"], null)).contains("the columns spacekey, spacename"))

/* --- a failed read never becomes an empty list ---------------------------- */

Map<String, Object> pickerCatalogue = SpaceCatalog.spaceList(
    pickerShape([], "The database catalogue could not be read: SQLException - no connection"), null)
check("an unreadable catalogue is a failed read", pickerCatalogue.get("ok"), Boolean.FALSE)
ok("an unreadable catalogue carries its reason",
    String.valueOf(pickerCatalogue.get("error")).contains("SQLException - no connection"))
ok("an unreadable catalogue is not an instance without spaces",
    String.valueOf(pickerCatalogue.get("error")).contains(SpaceCatalog.NOT_EMPTY))
check("an unreadable catalogue yields no list", ((List) pickerCatalogue.get("spaces")).size(), 0)

Map<String, Object> pickerStatementFailed = SpaceCatalog.spaceList(pickerShape([], null),
    pickerFound([], false, "The statement failed: SQLSyntaxErrorException - ORA-00942"))
check("a failed statement is a failed read", pickerStatementFailed.get("ok"), Boolean.FALSE)
ok("a failed statement carries its reason",
    String.valueOf(pickerStatementFailed.get("error")).contains("ORA-00942"))
ok("a failed statement is not an instance without spaces",
    String.valueOf(pickerStatementFailed.get("error")).contains(SpaceCatalog.NOT_EMPTY))
check("a failed statement yields no list", ((List) pickerStatementFailed.get("spaces")).size(), 0)

Map<String, Object> pickerNothingRead = SpaceCatalog.spaceList(pickerShape([], null), null)
check("a read that returned nothing at all is a failed read", pickerNothingRead.get("ok"), Boolean.FALSE)
ok("a read that returned nothing at all is not an instance without spaces",
    String.valueOf(pickerNothingRead.get("error")).contains(SpaceCatalog.NOT_EMPTY))
check("a read that returned nothing at all yields no list",
    ((List) pickerNothingRead.get("spaces")).size(), 0)

Map<String, Object> pickerNoShape = SpaceCatalog.spaceList(null, pickerFound([pickerRow("X", "X")], false, null))
check("a missing shape check is a failed read", pickerNoShape.get("ok"), Boolean.FALSE)
check("a missing shape check never yields an unverified list",
    ((List) pickerNoShape.get("spaces")).size(), 0)

/* --- an empty instance is not a failure ----------------------------------- */

Map<String, Object> pickerEmpty = SpaceCatalog.spaceList(pickerShape([], null), pickerFound([], false, null))
check("an instance without a current space is a successful read", pickerEmpty.get("ok"), Boolean.TRUE)
check("an instance without a current space lists nothing",
    ((List) pickerEmpty.get("spaces")).size(), 0)

/* --- the source contract of the spaces branch ----------------------------- */

Matcher spacesBranch = Pattern.compile("(?s)if \\(requestedAction == \"spaces\"\\) \\{(.*?)\\n    if \\(requestedAction == \"pages\"\\)")
    .matcher(endpointText)
ok("the spaces branch can be located", spacesBranch.find())
String spacesBranchText = spacesBranch.find(0) ? spacesBranch.group(1) : ""
ok("the spaces branch resolves no api.service.content type at all",
    !spacesBranchText.contains("ApiSpace"))
ok("the spaces branch takes the read-only executor",
    spacesBranchText.contains("Db.factory()") && spacesBranchText.contains("Db.withConnection(executorFactory)"))
ok("the spaces branch reads the space rows through the database",
    spacesBranchText.contains("Db.spaceRows(connection)"))
check("the fail-loud sentence is unchanged", SpaceCatalog.NOT_EMPTY,
    "That is a failed read, not an instance without spaces.")
check("every refusal of the spaces branch ends in it",
    spacesBranchText.count("SpaceCatalog.NOT_EMPTY"), 3)
check("every failed read of the stage refuses instead of answering",
    spacesBranchText.count("return refuse(500, \"spaces\""), 5)
ok("the picker binds the status rather than pasting it",
    endpointText.contains("query(connection, SpaceCatalog.SQL, [SpaceCatalog.STATUS_CURRENT]"))
ok("the endpoint runs no write statement", !endpointText.contains("executeUpdate("))
ok("the endpoint prepares statements only from its own constants",
    endpointText.count("prepareStatement(") == 1 && endpointText.contains("connection.prepareStatement(sql)"))
ok("the retired API SpaceService import states why it is kept",
    endpointText.contains("no longer resolved") && endpointText.contains("SpringProxy"))
ok("the picker announces a cut list in the browser",
    endpointText.contains("body.truncated===true"))

/* ---- 27. the read-path self-check ---------------------------------------- */

/* SelfCheck holds every decision and every sentence of the check; Db.probe holds
 * the four attempts that need an instance. That split is what makes this section
 * possible at all - none of the assertions below opens a connection, resolves a
 * component or loads a platform class, and none of them therefore needs one. */

/* --- the wording of a step ------------------------------------------------- */

check("a resolved step carries no detail", SelfCheck.step("x", true, "ignored").get("detail"), null)
check("a resolved step reads yes", SelfCheck.step("x", true, null).get("state"), SelfCheck.YES)
check("a refusal without a reason still says something",
    SelfCheck.step("x", false, null).get("detail"), "No reason was reported.")
check("a refusal without a reason still refuses", SelfCheck.step("x", false, "   ").get("state"), SelfCheck.NO)
check("a refusal keeps its reason", SelfCheck.step("x", false, " boom ").get("detail"), "boom")
check("an unattempted step is neither", SelfCheck.onRequest("x").get("state"), SelfCheck.NOT_ATTEMPTED)
check("an unattempted step says what it would have cost",
    SelfCheck.onRequest("x").get("detail"), SelfCheck.ON_REQUEST)
ok("a blocked step names its cause", SelfCheck.blocked("x", "y").get("detail").toString().contains("\"y\""))
check("a blocked step is not a failure", SelfCheck.blocked("x", "y").get("state"), SelfCheck.NOT_ATTEMPTED)

/* --- the catalogue verdict -------------------------------------------------- */

Map<String, Object> goodShape = new LinkedHashMap<String, Object>()
goodShape.put("table", "spaces")
goodShape.put("missing", new ArrayList<String>())
goodShape.put("failure", null)
check("a verified catalogue resolves", SelfCheck.catalogue(goodShape).get("state"), SelfCheck.YES)
check("a catalogue read that returned nothing refuses",
    SelfCheck.catalogue(null).get("state"), SelfCheck.NO)
ok("that refusal says nothing was verified",
    String.valueOf(SelfCheck.catalogue(null).get("detail")).contains("no column was verified"))
Map<String, Object> badShape = new LinkedHashMap<String, Object>()
badShape.put("table", "spaces")
badShape.put("missing", ["spacestatus"])
badShape.put("failure", null)
check("a catalogue missing a column refuses", SelfCheck.catalogue(badShape).get("state"), SelfCheck.NO)
ok("and names the column", String.valueOf(SelfCheck.catalogue(badShape).get("detail")).contains("spacestatus"))

/* --- the read path that could not be examined at all ----------------------- */

List<Map<String, Object>> unreachable = SelfCheck.unreachable("NoClassDefFoundError - Db")
check("a read path that cannot be examined reports four refusals", unreachable.size(), 4)
check("every one of them refuses", SelfCheck.failures(unreachable).size(), 4)
ok("and carries the reason it was given",
    String.valueOf(unreachable.get(0).get("detail")).contains("NoClassDefFoundError"))
ok("a reason that was not given does not become an empty sentence",
    String.valueOf(SelfCheck.unreachable(null).get(0).get("detail")).contains("no reason was reported"))
check("the four blocks are named in the order the read path needs them",
    unreachable.collect { it.get("step") },
    [SelfCheck.STEP_FACTORY, SelfCheck.STEP_CALLBACK, SelfCheck.STEP_EXECUTOR, SelfCheck.STEP_CATALOGUE])

/* --- the one line a standard report prints -------------------------------- */

List<Map<String, Object>> clean = [SelfCheck.step("a", true, null), SelfCheck.onRequest("b")]
check("a report whose checks resolved stays silent", SelfCheck.summary(clean), null)
check("and paints no box", SelfCheck.shown(clean), false)
check("an unattempted step is not counted as a failure", SelfCheck.failures(clean).size(), 0)
List<Map<String, Object>> broken = [SelfCheck.step("a", false, "gone"), SelfCheck.onRequest("b")]
ok("a refusal produces exactly one line", SelfCheck.summary(broken).split("\n").length == 1)
ok("the line names the block", SelfCheck.summary(broken).contains("a - gone"))
ok("the line says the report above is unaffected", SelfCheck.summary(broken).contains(SelfCheck.SCOPE))
ok("the line says where the full answer is", SelfCheck.summary(broken).endsWith(SelfCheck.HINT))
ok("a step nobody ran never produces a line", !SelfCheck.summary(broken).contains(SelfCheck.ON_REQUEST))
check("a check with no steps at all is silent rather than green", SelfCheck.summary([]), null)

/* --- the section, on request ----------------------------------------------- */

String cleanBox = SelfCheck.html(clean)
ok("a section that found nothing wrong is not painted as a warning",
    cleanBox.contains(Cfp.DIAG_INFO) && !cleanBox.contains(Cfp.DIAG_WARN))
ok("it says so in words", cleanBox.contains("Every step that was attempted resolved."))
check("every step gets a row", cleanBox.count("<tr><td>"), 2)
ok("a step without a detail prints the placeholder, not an empty cell",
    cleanBox.contains("<td>" + Cfp.NA + "</td>"))
String brokenBox = SelfCheck.html(broken)
ok("a section carrying a refusal is painted as a warning", brokenBox.contains(Cfp.DIAG_WARN))
ok("it names the state of every block", brokenBox.contains("<td>" + SelfCheck.NO + "</td>"))
ok("it promises no stack trace and keeps that promise",
    brokenBox.contains("No stack trace") && !brokenBox.contains("\tat "))
String hostile = SelfCheck.html([SelfCheck.step("<b>step</b>", false, "<script>alert(1)</script>")])
ok("a step name from an exception cannot inject markup", !hostile.contains("<b>step</b>"))
ok("a detail from an exception cannot inject markup", !hostile.contains("<script>"))
ok("it is escaped rather than dropped", hostile.contains("&lt;script&gt;"))
check("a check with no steps at all still renders a table",
    SelfCheck.html(new ArrayList<Map<String, Object>>()).count("<tbody></tbody>"), 1)

/* ---- 28. the source contract of the self-check ---------------------------- */

/* Db.probe sits outside the cut this suite compiles, because Db names JDBC types
 * and only a running Confluence resolves the rest. What can still be held here is
 * the rule the probe consists of, read off the source: it reports faults, so it
 * may not raise one, and it may not cost a connection unless it was asked to. */
int probeStart = endpointText.indexOf("static List<Map<String, Object>> probe(boolean deep)")
/* probe is the last method of Db, so the class brace in column one closes the cut. */
String probeText = probeStart < 0 ? ""
    : endpointText.substring(probeStart, endpointText.indexOf("\n}\n", probeStart))
ok("the probe exists in the shipped source", !probeText.isEmpty())
check("every attempt that can raise is guarded on its own", probeText.count("catch (Throwable error)"), 3)
check("the two deep steps are skipped unless they were asked for", probeText.count("if (!deep) {"), 2)
ok("a step whose ground did not resolve is not attempted",
    probeText.contains("SelfCheck.blocked(SelfCheck.STEP_EXECUTOR, SelfCheck.STEP_FACTORY)") &&
    probeText.contains("SelfCheck.blocked(SelfCheck.STEP_CATALOGUE,"))
ok("the probe prints no stack trace, only the exception and its message",
    !probeText.contains("printStackTrace") && !probeText.contains("getStackTrace") &&
    probeText.contains("why(error)"))
ok("the self-check runs on request only",
    endpointText.contains('Cfp.booleanParam(queryParams, "diag", false)') &&
    endpointText.contains("Db.probe(diagRequested)"))
ok("a standard report prints at most one line about it",
    endpointText.contains("String readPathLine = diagRequested ? null : SelfCheck.summary(readPath)"))
ok("a self-check that cannot run does not take the report with it",
    endpointText.contains("readPath = SelfCheck.unreachable(PageExport.errorDetail(error))"))

/* The read path itself is the one that ran on the customer instance. 4.9 rebuilt it
 * against a cause that was disproven afterwards and was withdrawn; these assertions
 * hold the shape that was measured working, so a second rebuild cannot arrive
 * unnoticed. */
ok("the read path is still the typed one that was verified on an instance",
    endpointText.contains("static Map<String, Object> shape(Connection connection, String table") &&
    endpointText.contains("static Map<String, Object> spaceRows(Connection connection)"))
ok("the SAL callback is still a hand-built JDK proxy",
    endpointText.contains("Proxy.newProxyInstance(") && endpointText.contains("new InvocationHandler()"))
check("the proxy is built in exactly one place", endpointText.count("Proxy.newProxyInstance("), 1)

/* ---- 29. the source carries no raw byte above ASCII ----------------------- */

/* ScriptRunner compiles this file with the default charset of the JVM it runs in,
 * which is a property of the server and not of this repository. A raw multi-byte
 * glyph in a string therefore decodes to whatever that charset makes of it -
 * measured: the same literal reads as the character under UTF-8 and as U+FFFD
 * under US-ASCII. The escape removes the dependency; this assertion keeps it
 * removed. The CI gate says the same thing about every tracked Groovy file; this
 * one is here so a run of the suite alone already fails on it. */
int aboveAscii = 0
for (int index = 0; index < endpointText.length(); index++) {
    if (endpointText.charAt(index) > (char) 0x7F) {
        aboveAscii++
    }
}
check("no raw character above ASCII survives in the source", aboveAscii, 0)
ok("the glyphs that were raw are still there, as escapes",
    endpointText.contains("\\u2014") && endpointText.contains("\\u00B7"))
check("the report names the version that produced it", Cfp.VERSION, "4.11")

/* ---- 30. OP-1066 the second macro name source ----------------------------- */

/* The content index is queried once per macro name known before the scan, so the
 * set of names decides what can be found at all. These cases hold the part of that
 * which needs no instance: which catalogue names are taken, and how an app whose
 * names could not be established is allowed to read. */

check("catalog contributes a name the descriptor walk missed",
    Cfp.catalogOnlyNames(["alpha", "beta"], ["alpha"]), ["beta"])
check("catalog contributes nothing when the descriptors already had it",
    Cfp.catalogOnlyNames(["alpha"], ["alpha"]), [])
check("a blank catalog name is not a question and is dropped",
    Cfp.catalogOnlyNames(["alpha", "", "   ", null, "beta"], ["alpha"]), ["beta"])
check("a duplicate inside the catalog is taken once",
    Cfp.catalogOnlyNames(["beta", "beta"], []), ["beta"])
check("an absent catalog is an empty contribution, not a crash",
    Cfp.catalogOnlyNames(null, ["alpha"]), [])
check("an app with no enumerated macro takes every catalog name",
    Cfp.catalogOnlyNames(["one", "two"], null), ["one", "two"])

/* The measured shape this exists for: one enabled module classified as a macro
 * host, no macro enumerated from it. Whether that reads as a zero depends on
 * whether the catalogue answered, and on nothing else. */
AppFootprint host = new AppFootprint()
host.modules.add(module("MacroModuleDescriptor", true))
host.finish()
ok("a macro host with no enumerated macro and no catalog is narrowed",
    host.macroEnumerationNarrowed())

AppFootprint hostAnswered = new AppFootprint()
hostAnswered.modules.add(module("MacroModuleDescriptor", true))
hostAnswered.macroCatalogConsulted = Boolean.TRUE
hostAnswered.finish()
ok("a macro host is not narrowed once the catalog answered",
    !hostAnswered.macroEnumerationNarrowed())

AppFootprint hostWithMacro = new AppFootprint()
hostWithMacro.modules.add(module("MacroModuleDescriptor", true))
MacroFootprint hostMacro = new MacroFootprint()
hostMacro.macroName = "gamma"
hostMacro.moduleEnabled = Boolean.TRUE
hostWithMacro.macros.add(hostMacro)
hostWithMacro.finish()
ok("an app that enumerated a macro is never narrowed",
    !hostWithMacro.macroEnumerationNarrowed())

AppFootprint noHost = new AppFootprint()
noHost.modules.add(module("WebItemModuleDescriptor", true))
noHost.finish()
ok("an app without a macro host is not narrowed",
    !noHost.macroEnumerationNarrowed())

/* A macro whose name came from the catalogue carries no module of its own, so the
 * cross-check has to count it or it reports a gap it has just explained. */
AppFootprint catalogFed = new AppFootprint()
catalogFed.modules.add(module("MacroModuleDescriptor", true))
MacroFootprint fromCatalog = new MacroFootprint()
fromCatalog.macroName = "delta"
fromCatalog.nameSource = Cfp.FROM_CATALOG
catalogFed.macros.add(fromCatalog)
catalogFed.macroCatalogConsulted = Boolean.TRUE
catalogFed.catalogMacroCount = 1
catalogFed.finish()
check("a catalog macro explains its host module", catalogFed.diagnostics.size(), 0)
check("a catalog macro has no enabled module of its own", catalogFed.enabledMacroCount, 0)
check("provenance defaults to the descriptor", new MacroFootprint().nameSource, Cfp.FROM_DESCRIPTOR)

/* The verdict. This is the case the whole change exists for: before it, such an
 * app fell through to the closing branch and read as carrying no footprint, with
 * a complete archived scan and every figure at zero. */
ImpactAssessment narrowedImpact = ImpactAnalyzer.assessConfluence(
    host, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("a narrowed app is held at review even with a complete archived scan",
    narrowedImpact.level, "REVIEW_REQUIRED")
ok("a narrowed app is marked partial", narrowedImpact.partial)
ok("the reason says no macro name was searched for",
    narrowedImpact.reasons.get(0).contains("no name was searched for"))
ok("the reason refuses the word zero for the figures",
    narrowedImpact.reasons.get(0).contains("are not measured and are not a zero"))

ImpactAssessment answeredImpact = ImpactAnalyzer.assessConfluence(
    hostAnswered, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("once the catalog answered, a real zero is allowed to be a zero",
    answeredImpact.level, "NO_DETECTABLE_FOOTPRINT")

/* An app with no macro host at all keeps the verdict it had before this change:
 * the gate must not turn every quiet app into a review. */
ImpactAssessment noHostImpact = ImpactAnalyzer.assessConfluence(
    noHost, true, true, Long.valueOf(100L), Long.valueOf(10L), false)
check("an app without a macro host still reaches a complete zero",
    noHostImpact.level, "NO_DETECTABLE_FOOTPRINT")

/* Source contract. The catalogue resolution names its type by string and reports a
 * failure as a failure; both are the reason this class exists and neither is
 * reachable from the offline cut, so they are read off the source. */
String catalogSource = endpointText
ok("the catalogue is resolved by name, not by a static type reference",
    catalogSource.contains('static final String MANAGER = "com.atlassian.confluence.macro.browser.MacroMetadataManager"'))
ok("the catalogue is never named as an import",
    !catalogSource.contains("import com.atlassian.confluence.macro.browser.MacroMetadataManager"))
ok("an unreachable catalogue is reported, not returned as empty",
    catalogSource.contains("could not be obtained") && catalogSource.contains("No macro name was taken from it."))
ok("the catalogue is asked once per run, not once per app",
    catalogSource.contains("Map<String, Object> macroCatalog = MacroCatalog.load()"))
ok("only names the catalogue itself attributes to the app are taken",
    catalogSource.contains("macroCatalogByPlugin.get(app.pluginKey)"))
ok("the coverage line names both sources",
    catalogSource.contains("Names come from two sources"))

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
