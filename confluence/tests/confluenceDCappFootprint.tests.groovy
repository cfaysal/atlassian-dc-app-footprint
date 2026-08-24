
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
check("the current-space finder is paginated", endpointText.count(".withStatus(ApiSpaceStatus.CURRENT)"), 1)
check("space pagination checks for more results", endpointText.count("spacePage.hasMore()"), 1)
ok("GlobalSettingsManager is imported",
    endpointText.contains("import com.atlassian.confluence.setup.settings.GlobalSettingsManager"))
check("both settings reads resolve GlobalSettingsManager",
    endpointText.count("ComponentLocator.getComponent(GlobalSettingsManager.class)"), 2)

/* ---- result --------------------------------------------------------------- */

println "PASSED: " + passed
println "FAILED: " + failed
failures.each { println "  FAIL " + it }
if (failed > 0) {
    System.exit(1)
}
println "ALL TESTS PASSED"
