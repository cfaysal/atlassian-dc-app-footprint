/* =============================================================================
 * Confluence Data Center - App Footprint Analysis
 * ScriptRunner Custom REST Endpoint. Read-only, admin-gated.
 *
 * Version 4.3
 *
 * Purpose
 *   Measures the detectable configuration/content footprint of installed apps
 *   in Confluence Data Center. It inventories extension modules and measures
 *   macro usage in CURRENT and ARCHIVED spaces separately. Native User Macros
 *   are reported as Confluence configuration, not as Marketplace app content.
 *
 * Security
 *   Endpoint is restricted to confluence-administrators.
 *
 * Platform
 *   Confluence 10 / ScriptRunner 10+ (jakarta.ws.rs).
 *
 * Parameters (all optional)
 *   format=html|json|csv        default html
 *   level=app|macro|module      default app (CSV only)
 *   includeSystem=true|false    default false
 *   includeDisabled=true|false  default true
 *   includeArchived=true|false  default true
 *   includeModules=true|false   default false (HTML/JSON detail)
 *   scanUsage=true|false        default true
 *   scanAliases=true|false      default false
 *   scanBudgetMs=<long>         default 120000, 0 = unlimited
 *   appKey=<plugin-key>         optional single-app filter
 *   numbers=de|en               default de
 *
 * Reporting discipline
 *   - A failed read is never rendered as a measured zero.
 *   - A skipped/budgeted usage scan is marked n/m (not measured).
 *   - CURRENT and ARCHIVED macro usage are never mixed.
 *   - The report is read-only and performs no outbound network call.
 * =============================================================================
 */

import com.atlassian.confluence.macro.browser.MacroMetadataSource
import com.atlassian.confluence.macro.browser.beans.MacroMetadata
import com.atlassian.confluence.renderer.UserMacroConfig
import com.atlassian.confluence.renderer.UserMacroLibrary
import com.atlassian.confluence.search.v2.Index
import com.atlassian.confluence.search.v2.SearchFieldMappings
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.query.MacroUsageQuery
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.confluence.spaces.SpaceStatus
import com.atlassian.confluence.util.i18n.I18NBean
import com.atlassian.confluence.util.i18n.I18NBeanFactory

import com.atlassian.plugin.ModuleDescriptor
import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.plugin.PluginInformation
import com.atlassian.plugin.metadata.PluginMetadataManager
import com.atlassian.sal.api.component.ComponentLocator

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.function.Consumer

@BaseScript CustomEndpointDelegate delegate


/* =============================================================================
 * Constants / utility
 * ========================================================================== */

class Cfp {

    static final String NA = "—"

    static final String MEASURED = "measured"
    static final String DISABLED = "disabled"
    static final String BUDGET = "budget"
    static final String ERROR = "error"
    static final String PARTIAL = "partial"

    static String html(Object value) {
        if (value == null) {
            return ""
        }
        return value.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    static String number(Number value, Locale locale) {
        if (value == null) {
            return NA
        }
        Locale useLocale = locale == null ? Locale.ENGLISH : locale
        return String.format(useLocale, "%,d", value.longValue())
    }

    static String csv(Object value) {
        if (value == null) {
            return "\"\""
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\""
    }

    static String stringParam(MultivaluedMap queryParams, String name, String defaultValue) {
        Object raw = queryParams == null ? null : queryParams.getFirst(name)
        if (raw == null) {
            return defaultValue
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? defaultValue : value
    }

    static boolean booleanParam(MultivaluedMap queryParams, String name, boolean defaultValue) {
        String value = stringParam(queryParams, name, null)
        if (value == null) {
            return defaultValue
        }
        value = value.toLowerCase(Locale.ROOT)
        if (value in ["true", "1", "yes", "on"]) {
            return true
        }
        if (value in ["false", "0", "no", "off"]) {
            return false
        }
        return defaultValue
    }

    static long longParam(MultivaluedMap queryParams, String name, long defaultValue) {
        String value = stringParam(queryParams, name, null)
        if (value == null) {
            return defaultValue
        }
        try {
            long parsed = Long.parseLong(value)
            return parsed < 0L ? defaultValue : parsed
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    static String firstValue(Map<String, String[]> document, String fieldName) {
        String[] values = document == null ? null : document.get(fieldName)
        if (values == null || values.length == 0) {
            return null
        }
        return values[0]
    }

    static void note(List<String> sink, String context, Throwable error) {
        if (sink == null) {
            return
        }
        String detail = "unknown error"
        if (error != null) {
            detail = error.getClass().getSimpleName()
            String message = error.getMessage()
            if (message != null && !message.trim().isEmpty()) {
                detail = detail + ": " + message.trim()
            }
        }
        if (detail.length() > 220) {
            detail = detail.substring(0, 220) + "..."
        }
        sink.add(context + " -> " + detail)
    }

    static String resolvePluginName(Plugin plugin, I18NBean i18n) {
        String descriptorName = plugin.getName()
        String i18nKey = plugin.getI18nNameKey()
        if (i18nKey != null && !i18nKey.trim().isEmpty() && i18n != null) {
            try {
                String translated = i18n.getText(i18nKey)
                if (translated != null && !translated.trim().isEmpty() && translated != i18nKey) {
                    return translated
                }
            } catch (Exception ignored) {
                // descriptor fallback below
            }
        }
        if (descriptorName != null && !descriptorName.trim().isEmpty()) {
            return descriptorName
        }
        return plugin.getKey()
    }

    static String resolveMacroDisplayName(ModuleDescriptor<?> descriptor, MacroMetadata metadata) {
        String descriptorName = descriptor.getName()
        if (descriptorName != null && !descriptorName.trim().isEmpty()) {
            return descriptorName
        }
        if (metadata != null) {
            String macroName = metadata.getMacroName()
            if (macroName != null && !macroName.trim().isEmpty()) {
                return macroName
            }
        }
        return descriptor.getKey()
    }

    /* Ordered, first match wins. */
    static final List<List<Object>> CATEGORY_RULES = [
        ["Space Blueprints", ["spaceblueprint"]],
        ["Blueprints", ["blueprint"]],
        ["Content Templates", ["contenttemplate"]],
        ["Templates", ["template"]],
        ["Macros", ["macro"]],
        ["Custom Content", ["customcontent", "contenttype"]],
        ["UI", ["webitem", "websection", "webpanel", "webfragment", "webresource", "navigation", "keyboardshortcut"]],
        ["REST / API", ["rest", "resource"]],
        ["HTTP / Servlet", ["servlet", "filter"]],
        ["Search / Index", ["search", "extractor", "index", "query"]],
        ["Editor", ["editor"]],
        ["Events / Listeners", ["listener", "event", "webhook"]],
        ["Jobs / Services", ["job", "scheduler", "service"]],
        ["Permissions / Security", ["permission", "security"]],
        ["Theme / Layout", ["theme", "layout", "decorator"]]
    ]

    static String extensionCategory(String descriptorName) {
        if (descriptorName == null) {
            return "Other"
        }
        String value = descriptorName.toLowerCase(Locale.ROOT)
        for (List<Object> rule : CATEGORY_RULES) {
            List<String> markers = (List<String>) rule[1]
            for (String marker : markers) {
                if (value.contains(marker)) {
                    return (String) rule[0]
                }
            }
        }
        return "Other"
    }

    static String contentTypeText(Map<String, Integer> values) {
        if (values == null || values.isEmpty()) {
            return NA
        }
        StringBuilder out = new StringBuilder()
        boolean first = true
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (!first) {
                out.append(", ")
            }
            out.append(entry.getKey()).append(": ").append(entry.getValue())
            first = false
        }
        return out.toString()
    }

    static String link(Map<String, Object> base, Map<?, ?> overrides) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(base)
        if (overrides != null) {
            for (Map.Entry<?, ?> entry : overrides.entrySet()) {
                Object rawKey = entry.getKey()
                if (rawKey != null) {
                    merged.put(rawKey.toString(), entry.getValue())
                }
            }
        }
        StringBuilder out = new StringBuilder("?")
        boolean first = true
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (entry.getValue() == null) {
                continue
            }
            if (!first) {
                out.append("&")
            }
            out.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
            out.append("=")
            out.append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"))
            first = false
        }
        return out.toString()
    }
}


/* =============================================================================
 * Impact model
 * ========================================================================== */

class ImpactPolicy {
    static final long CRITICAL_CONTENT = 10000L
    static final long CRITICAL_ASSOCIATIONS = 20000L
    static final int CRITICAL_SPACES = 100

    static final long HIGH_CONTENT = 1000L
    static final long HIGH_ASSOCIATIONS = 5000L
    static final int HIGH_SPACES = 25

    static final long MEDIUM_CONTENT = 100L
    static final long MEDIUM_ASSOCIATIONS = 500L
    static final int MEDIUM_SPACES = 5
}

class ImpactAssessment {
    String level
    String label
    int rank
    List<String> reasons = new ArrayList<String>()

    Map<String, Object> asMap() {
        return [
            level: level,
            label: label,
            rank: rank,
            reasons: reasons
        ] as LinkedHashMap
    }
}


/* =============================================================================
 * DTOs
 * ========================================================================== */

class ExtensionModuleInfo {
    String key
    String completeKey
    String name
    String category
    String descriptorName
    String descriptorClass
    String moduleClass
    Boolean enabled

    Map<String, Object> asMap() {
        return [
            key: key,
            completeKey: completeKey,
            name: name,
            category: category,
            descriptorName: descriptorName,
            descriptorClass: descriptorClass,
            moduleClass: moduleClass,
            enabled: enabled
        ] as LinkedHashMap
    }
}

class MacroFootprint {
    String source
    String macroName
    String displayName
    String descriptorName
    Boolean moduleEnabled
    boolean hidden
    String description
    String bodyType
    int parameterCount

    Set<String> aliases = new TreeSet<String>()
    Set<String> categories = new TreeSet<String>()

    Set<String> currentContentIds = new HashSet<String>()
    Set<String> currentSpaceKeys = new TreeSet<String>()
    Map<String, String> currentContentTypeById = new HashMap<String, String>()

    Set<String> archivedContentIds = new HashSet<String>()
    Set<String> archivedSpaceKeys = new TreeSet<String>()
    Map<String, String> archivedContentTypeById = new HashMap<String, String>()

    Set<String> otherContentIds = new HashSet<String>()

    String usageState = Cfp.DISABLED
    Set<String> scannedNames = new TreeSet<String>()
    List<String> diagnostics = new ArrayList<String>()

    int getCurrentContentCount() { return currentContentIds.size() }
    int getArchivedContentCount() { return archivedContentIds.size() }
    int getOtherContentCount() { return otherContentIds.size() }
    int getCurrentSpaceCount() { return currentSpaceKeys.size() }
    int getArchivedSpaceCount() { return archivedSpaceKeys.size() }

    int getTotalContentCount() {
        Set<String> ids = new HashSet<String>()
        ids.addAll(currentContentIds)
        ids.addAll(archivedContentIds)
        ids.addAll(otherContentIds)
        return ids.size()
    }

    boolean isMeasured() {
        return usageState == Cfp.MEASURED || usageState == Cfp.PARTIAL
    }

    boolean isCurrentlyUsed() {
        return isMeasured() && !currentContentIds.isEmpty()
    }

    boolean isArchivedUsed() {
        return isMeasured() && !archivedContentIds.isEmpty()
    }

    Map<String, Integer> getCurrentContentTypeCounts() {
        return buildTypeCounts(currentContentTypeById)
    }

    Map<String, Integer> getArchivedContentTypeCounts() {
        return buildTypeCounts(archivedContentTypeById)
    }

    private static Map<String, Integer> buildTypeCounts(Map<String, String> values) {
        Map<String, Integer> counts = new HashMap<String, Integer>()
        for (String raw : values.values()) {
            String type = raw == null || raw.trim().isEmpty() ? "unknown" : raw
            Integer seen = counts.get(type)
            counts.put(type, seen == null ? 1 : seen + 1)
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet())
        entries.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b ->
            int byCount = Integer.compare(b.getValue(), a.getValue())
            if (byCount != 0) {
                return byCount
            }
            return a.getKey().compareToIgnoreCase(b.getKey())
        }
        Map<String, Integer> result = new LinkedHashMap<String, Integer>()
        for (Map.Entry<String, Integer> entry : entries) {
            result.put(entry.getKey(), entry.getValue())
        }
        return result
    }

    Map<String, Object> asMap() {
        return [
            source: source,
            macroName: macroName,
            displayName: displayName,
            descriptorName: descriptorName,
            moduleEnabled: moduleEnabled,
            hidden: hidden,
            description: description,
            bodyType: bodyType,
            parameterCount: parameterCount,
            aliases: new ArrayList<String>(aliases),
            categories: new ArrayList<String>(categories),
            usageState: usageState,
            current: [
                used: isCurrentlyUsed(),
                contentCount: getCurrentContentCount(),
                spaceCount: getCurrentSpaceCount(),
                spaceKeys: new ArrayList<String>(currentSpaceKeys),
                contentTypes: getCurrentContentTypeCounts()
            ] as LinkedHashMap,
            archived: [
                used: isArchivedUsed(),
                contentCount: getArchivedContentCount(),
                spaceCount: getArchivedSpaceCount(),
                spaceKeys: new ArrayList<String>(archivedSpaceKeys),
                contentTypes: getArchivedContentTypeCounts()
            ] as LinkedHashMap,
            otherContentCount: getOtherContentCount(),
            totalContentCount: getTotalContentCount(),
            scannedNames: new ArrayList<String>(scannedNames),
            diagnostics: diagnostics
        ] as LinkedHashMap
    }
}

class AppFootprint {
    String displayName
    String descriptorName
    String i18nNameKey
    String pluginKey
    String vendor
    String vendorUrl
    String version
    boolean systemProvided
    boolean enabled
    String state

    List<ExtensionModuleInfo> modules = new ArrayList<ExtensionModuleInfo>()
    List<MacroFootprint> macros = new ArrayList<MacroFootprint>()
    List<String> diagnostics = new ArrayList<String>()

    int enabledModuleCount
    int enabledMacroCount
    Map<String, Integer> categoryCounts = new TreeMap<String, Integer>()
    Map<String, Integer> moduleTypeCounts = new LinkedHashMap<String, Integer>()
    int currentUsedMacroCount
    int archivedUsedMacroCount
    int currentUniqueContentCount
    int archivedUniqueContentCount
    int otherUniqueContentCount
    long currentAssociations
    long archivedAssociations
    long otherAssociations
    int currentSpaceCount
    int archivedSpaceCount
    boolean currentUsagePartial
    boolean archivedUsagePartial
    int diagnosticCount

    void finish() {
        enabledModuleCount = 0
        enabledMacroCount = 0
        categoryCounts.clear()
        Map<String, Integer> byType = new HashMap<String, Integer>()

        for (ExtensionModuleInfo module : modules) {
            if (module.enabled == Boolean.TRUE) {
                enabledModuleCount++
                Integer cat = categoryCounts.get(module.category)
                categoryCounts.put(module.category, cat == null ? 1 : cat + 1)
                Integer type = byType.get(module.descriptorName)
                byType.put(module.descriptorName, type == null ? 1 : type + 1)
            }
        }

        List<Map.Entry<String, Integer>> typeEntries = new ArrayList<Map.Entry<String, Integer>>(byType.entrySet())
        typeEntries.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b ->
            int byCount = Integer.compare(b.getValue(), a.getValue())
            if (byCount != 0) {
                return byCount
            }
            return a.getKey().compareToIgnoreCase(b.getKey())
        }
        moduleTypeCounts = new LinkedHashMap<String, Integer>()
        for (Map.Entry<String, Integer> entry : typeEntries) {
            moduleTypeCounts.put(entry.getKey(), entry.getValue())
        }

        Set<String> currentIds = new HashSet<String>()
        Set<String> archivedIds = new HashSet<String>()
        Set<String> otherIds = new HashSet<String>()
        Set<String> currentSpaces = new HashSet<String>()
        Set<String> archivedSpaces = new HashSet<String>()

        currentUsedMacroCount = 0
        archivedUsedMacroCount = 0
        currentAssociations = 0L
        archivedAssociations = 0L
        otherAssociations = 0L
        currentUsagePartial = false
        archivedUsagePartial = false
        diagnosticCount = diagnostics.size()

        for (MacroFootprint macro : macros) {
            if (macro.moduleEnabled == Boolean.TRUE) {
                enabledMacroCount++
            }
            if (macro.isCurrentlyUsed()) {
                currentUsedMacroCount++
            }
            if (macro.isArchivedUsed()) {
                archivedUsedMacroCount++
            }
            if (macro.usageState != Cfp.MEASURED) {
                currentUsagePartial = true
                archivedUsagePartial = true
            }
            currentAssociations += macro.getCurrentContentCount()
            archivedAssociations += macro.getArchivedContentCount()
            otherAssociations += macro.getOtherContentCount()
            currentIds.addAll(macro.currentContentIds)
            archivedIds.addAll(macro.archivedContentIds)
            otherIds.addAll(macro.otherContentIds)
            currentSpaces.addAll(macro.currentSpaceKeys)
            archivedSpaces.addAll(macro.archivedSpaceKeys)
            diagnosticCount += macro.diagnostics.size()
        }

        currentUniqueContentCount = currentIds.size()
        archivedUniqueContentCount = archivedIds.size()
        otherUniqueContentCount = otherIds.size()
        currentSpaceCount = currentSpaces.size()
        archivedSpaceCount = archivedSpaces.size()

        /* Cross-check the descriptor class name heuristic against the macro enumeration.
         * Both sides are restricted to ENABLED modules: categoryCounts above skips
         * modules that are not enabled, so the enumeration side is enabledMacroCount and
         * not macros.size() - comparing against the full list would fire on every app
         * that ships a disabled macro module. diagnosticCount is already assigned above,
         * so it is incremented here to keep the JSON/CSV/HTML counters consistent. */
        int classifiedMacroModules = categoryCount("Macros")
        if (classifiedMacroModules != enabledMacroCount) {
            diagnostics.add("macro cross-check: " + classifiedMacroModules + " enabled module(s) classified as \"Macros\", " +
                enabledMacroCount + " macro(s) enumerated - the enumeration or the class name classification may be incomplete")
            diagnosticCount++
        }
    }

    boolean hasCurrentFootprint() {
        return currentUsedMacroCount > 0
    }

    boolean hasArchivedFootprint() {
        return archivedUsedMacroCount > 0
    }

    int categoryCount(String category) {
        Integer value = categoryCounts.get(category)
        return value == null ? 0 : value.intValue()
    }

    int getProvidedBlueprintCount() {
        return categoryCount("Blueprints") + categoryCount("Space Blueprints")
    }

    int getProvidedTemplateCount() {
        return categoryCount("Content Templates") + categoryCount("Templates")
    }

    int getCustomContentModuleCount() {
        return categoryCount("Custom Content")
    }

    boolean hasInventoryOnlyPersistenceSignals() {
        return getProvidedBlueprintCount() > 0 || getProvidedTemplateCount() > 0 || getCustomContentModuleCount() > 0
    }

    List<ExtensionModuleInfo> getPersistenceModules() {
        List<ExtensionModuleInfo> result = new ArrayList<ExtensionModuleInfo>()
        for (ExtensionModuleInfo module : modules) {
            if (module.enabled != Boolean.TRUE) {
                continue
            }
            if (module.category in ["Blueprints", "Space Blueprints", "Content Templates", "Templates", "Custom Content"]) {
                result.add(module)
            }
        }
        return result
    }

    Map<String, Object> asMap(boolean includeModules, ImpactAssessment impact) {
        List<Map<String, Object>> macroMaps = new ArrayList<Map<String, Object>>()
        for (MacroFootprint macro : macros) {
            macroMaps.add(macro.asMap())
        }

        List<Map<String, Object>> persistenceMaps = new ArrayList<Map<String, Object>>()
        for (ExtensionModuleInfo module : getPersistenceModules()) {
            persistenceMaps.add(module.asMap())
        }

        Map<String, Object> result = [
            displayName: displayName,
            descriptorName: descriptorName,
            i18nNameKey: i18nNameKey,
            pluginKey: pluginKey,
            vendor: vendor,
            vendorUrl: vendorUrl,
            version: version,
            systemProvided: systemProvided,
            enabled: enabled,
            state: state,
            impact: impact.asMap(),
            capabilities: [
                moduleCount: modules.size(),
                enabledModuleCount: enabledModuleCount,
                providedMacros: macros.size(),
                enabledMacros: enabledMacroCount,
                providedBlueprints: getProvidedBlueprintCount(),
                providedTemplates: getProvidedTemplateCount(),
                customContentModules: getCustomContentModuleCount(),
                categories: categoryCounts,
                moduleTypes: moduleTypeCounts
            ] as LinkedHashMap,
            currentFootprint: [
                detected: hasCurrentFootprint(),
                partial: currentUsagePartial,
                usedMacros: currentUsedMacroCount,
                uniqueContent: currentUniqueContentCount,
                macroContentAssociations: currentAssociations,
                affectedSpaces: currentSpaceCount
            ] as LinkedHashMap,
            archivedFootprint: [
                detected: hasArchivedFootprint(),
                partial: archivedUsagePartial,
                usedMacros: archivedUsedMacroCount,
                uniqueContent: archivedUniqueContentCount,
                macroContentAssociations: archivedAssociations,
                affectedSpaces: archivedSpaceCount
            ] as LinkedHashMap,
            otherUniqueContent: otherUniqueContentCount,
            otherMacroContentAssociations: otherAssociations,
            macros: macroMaps,
            persistenceCapabilities: persistenceMaps,
            diagnostics: diagnostics
        ] as LinkedHashMap

        if (includeModules) {
            List<Map<String, Object>> moduleMaps = new ArrayList<Map<String, Object>>()
            for (ExtensionModuleInfo module : modules) {
                moduleMaps.add(module.asMap())
            }
            result.put("modules", moduleMaps)
        }
        return result
    }
}


/* =============================================================================
 * Analysis helpers
 * ========================================================================== */

class Analyzer {

    static ImpactAssessment assessImpact(AppFootprint app, boolean usageScanned) {
        ImpactAssessment result = new ImpactAssessment()

        if (!usageScanned) {
            result.level = "NOT_SCANNED"
            result.label = "Usage not scanned"
            result.rank = 1
            result.reasons.add("Macro usage scanning was disabled.")
            return result
        }

        long currentContent = app.currentUniqueContentCount
        long currentAssociations = app.currentAssociations
        int currentSpaces = app.currentSpaceCount

        if (
            currentContent >= ImpactPolicy.CRITICAL_CONTENT ||
            currentAssociations >= ImpactPolicy.CRITICAL_ASSOCIATIONS ||
            currentSpaces >= ImpactPolicy.CRITICAL_SPACES
        ) {
            result.level = "CRITICAL"
            result.label = "Critical"
            result.rank = 7

            if (currentContent >= ImpactPolicy.CRITICAL_CONTENT) {
                result.reasons.add(String.valueOf(currentContent) + " current content objects depend on app macros.")
            }
            if (currentAssociations >= ImpactPolicy.CRITICAL_ASSOCIATIONS) {
                result.reasons.add(String.valueOf(currentAssociations) + " current macro-content associations.")
            }
            if (currentSpaces >= ImpactPolicy.CRITICAL_SPACES) {
                result.reasons.add("Current footprint spans " + String.valueOf(currentSpaces) + " spaces.")
            }
            return result
        }

        if (
            currentContent >= ImpactPolicy.HIGH_CONTENT ||
            currentAssociations >= ImpactPolicy.HIGH_ASSOCIATIONS ||
            currentSpaces >= ImpactPolicy.HIGH_SPACES
        ) {
            result.level = "HIGH"
            result.label = "High"
            result.rank = 6
            result.reasons.add(
                "Significant current macro footprint: " + String.valueOf(currentContent) +
                " content objects, " + String.valueOf(currentAssociations) +
                " associations, " + String.valueOf(currentSpaces) + " spaces."
            )
            return result
        }

        if (
            currentContent >= ImpactPolicy.MEDIUM_CONTENT ||
            currentAssociations >= ImpactPolicy.MEDIUM_ASSOCIATIONS ||
            currentSpaces >= ImpactPolicy.MEDIUM_SPACES
        ) {
            result.level = "MEDIUM"
            result.label = "Medium"
            result.rank = 5
            result.reasons.add(
                "Measurable current macro footprint: " + String.valueOf(currentContent) +
                " content objects, " + String.valueOf(currentAssociations) +
                " associations, " + String.valueOf(currentSpaces) + " spaces."
            )
            return result
        }

        if (app.hasCurrentFootprint()) {
            result.level = "LOW"
            result.label = "Low"
            result.rank = 4
            result.reasons.add("Current macro footprint exists but remains below configured assessment thresholds.")
            return result
        }

        if (app.hasArchivedFootprint()) {
            result.level = "LEGACY_ONLY"
            result.label = "Legacy only"
            result.rank = 3
            result.reasons.add("No current macro footprint detected, but archived content still depends on the app.")
            return result
        }

        if (app.hasInventoryOnlyPersistenceSignals()) {
            result.level = "REVIEW_REQUIRED"
            result.label = "Review required"
            result.rank = 2
            result.reasons.add("The app provides blueprint, template or custom-content capabilities whose persisted usage cannot be determined generically.")
            return result
        }

        result.level = "NO_DETECTABLE_FOOTPRINT"
        result.label = "No detectable footprint"
        result.rank = 0
        result.reasons.add("No generic macro/configuration footprint was detected. Runtime, UI, REST or proprietary app usage is still possible.")
        return result
    }

    static boolean scanMacroName(
        SearchManager searchManager,
        MacroFootprint macro,
        String searchName,
        Set<String> currentSpaceKeys,
        Set<String> archivedSpaceKeys,
        boolean includeArchived
    ) {
        if (searchName == null || searchName.trim().isEmpty()) {
            return true
        }

        final String contentIdField = SearchFieldMappings.CONTENT_ID.getName()
        final String spaceKeyField = SearchFieldMappings.SPACE_KEY.getName()
        final String typeField = SearchFieldMappings.TYPE.getName()

        final Set<String> requestedFields = new LinkedHashSet<String>()
        requestedFields.add(contentIdField)
        requestedFields.add(spaceKeyField)
        requestedFields.add(typeField)

        final MacroFootprint target = macro
        final Set<String> currentSpaces = currentSpaceKeys
        final Set<String> archivedSpaces = archivedSpaceKeys
        final boolean collectArchived = includeArchived

        try {
            searchManager.scan(
                Collections.singletonList(Index.CONTENT),
                new MacroUsageQuery(searchName),
                requestedFields,
                new Consumer<Map<String, String[]>>() {
                    @Override
                    void accept(Map<String, String[]> document) {
                        String contentId = Cfp.firstValue(document, contentIdField)
                        if (contentId == null || contentId.trim().isEmpty()) {
                            return
                        }

                        String spaceKey = Cfp.firstValue(document, spaceKeyField)
                        String contentType = Cfp.firstValue(document, typeField)

                        if (spaceKey != null && currentSpaces.contains(spaceKey)) {
                            target.currentContentIds.add(contentId)
                            target.currentSpaceKeys.add(spaceKey)
                            if (contentType != null && !contentType.trim().isEmpty()) {
                                target.currentContentTypeById.put(contentId, contentType)
                            }
                            return
                        }

                        if (spaceKey != null && archivedSpaces.contains(spaceKey)) {
                            if (collectArchived) {
                                target.archivedContentIds.add(contentId)
                                target.archivedSpaceKeys.add(spaceKey)
                                if (contentType != null && !contentType.trim().isEmpty()) {
                                    target.archivedContentTypeById.put(contentId, contentType)
                                }
                            }
                            return
                        }

                        target.otherContentIds.add(contentId)
                    }
                }
            )
            target.scannedNames.add(searchName)
            return true
        } catch (Exception error) {
            Cfp.note(target.diagnostics, "macro usage scan '" + searchName + "'", error)
            return false
        }
    }
}


/* =============================================================================
 * Endpoint
 * ========================================================================== */

appFootprint(
    httpMethod: "GET",
    groups: ["confluence-administrators"]
) { MultivaluedMap queryParams, String body ->

    long started = System.currentTimeMillis()

    String format = Cfp.stringParam(queryParams, "format", "html").toLowerCase(Locale.ROOT)
    String csvLevel = Cfp.stringParam(queryParams, "level", "app").toLowerCase(Locale.ROOT)
    String appKeyFilter = Cfp.stringParam(queryParams, "appKey", "")
    boolean includeSystem = Cfp.booleanParam(queryParams, "includeSystem", false)
    boolean includeDisabled = Cfp.booleanParam(queryParams, "includeDisabled", true)
    boolean includeArchived = Cfp.booleanParam(queryParams, "includeArchived", true)
    boolean includeModules = Cfp.booleanParam(queryParams, "includeModules", false)
    boolean scanUsage = Cfp.booleanParam(queryParams, "scanUsage", true)
    boolean scanAliases = Cfp.booleanParam(queryParams, "scanAliases", false)
    long scanBudgetMs = Cfp.longParam(queryParams, "scanBudgetMs", 120000L)
    String numbers = Cfp.stringParam(queryParams, "numbers", "de").toLowerCase(Locale.ROOT)

    Locale numberLocale = numbers == "en" ? Locale.ENGLISH : Locale.GERMANY
    long scanDeadline = scanBudgetMs > 0L ? started + scanBudgetMs : Long.MAX_VALUE

    Map<String, Object> activeParams = [
        format: format == "html" ? null : format,
        level: csvLevel == "app" ? null : csvLevel,
        appKey: appKeyFilter.isEmpty() ? null : appKeyFilter,
        includeSystem: includeSystem ? "true" : null,
        includeDisabled: includeDisabled ? null : "false",
        includeArchived: includeArchived ? null : "false",
        includeModules: includeModules ? "true" : null,
        scanUsage: scanUsage ? null : "false",
        scanAliases: scanAliases ? "true" : null,
        scanBudgetMs: scanBudgetMs == 120000L ? null : String.valueOf(scanBudgetMs),
        numbers: numbers == "de" ? null : numbers
    ] as LinkedHashMap

    PluginAccessor pluginAccessor = ComponentLocator.getComponent(PluginAccessor.class)
    PluginMetadataManager pluginMetadataManager = ComponentLocator.getComponent(PluginMetadataManager.class)
    SearchManager searchManager = ComponentLocator.getComponent(SearchManager.class)
    SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager.class)
    UserMacroLibrary userMacroLibrary = ComponentLocator.getComponent(UserMacroLibrary.class)
    I18NBeanFactory i18nBeanFactory = ComponentLocator.getComponent(I18NBeanFactory.class)
    I18NBean i18n = i18nBeanFactory == null ? null : i18nBeanFactory.getI18NBean()

    List<String> globalDiagnostics = new ArrayList<String>()

    Set<String> currentSpaceKeys = new HashSet<String>()
    Set<String> archivedSpaceKeys = new HashSet<String>()

    try {
        currentSpaceKeys.addAll(spaceManager.getAllSpaceKeys(SpaceStatus.CURRENT))
    } catch (Exception error) {
        Cfp.note(globalDiagnostics, "current space inventory", error)
    }

    try {
        archivedSpaceKeys.addAll(spaceManager.getAllSpaceKeys(SpaceStatus.ARCHIVED))
    } catch (Exception error) {
        Cfp.note(globalDiagnostics, "archived space inventory", error)
    }

    Collection<Plugin> candidatePlugins = new ArrayList<Plugin>()
    try {
        candidatePlugins = includeDisabled ? pluginAccessor.getPlugins() : pluginAccessor.getEnabledPlugins()
    } catch (Exception error) {
        Cfp.note(globalDiagnostics, "plugin inventory", error)
    }

    List<AppFootprint> apps = new ArrayList<AppFootprint>()
    int macrosSkippedByBudget = 0

    for (Plugin plugin : candidatePlugins) {

        String pluginKey = plugin.getKey()
        if (!appKeyFilter.isEmpty() && pluginKey != appKeyFilter) {
            continue
        }

        AppFootprint app = new AppFootprint()
        app.pluginKey = pluginKey
        app.descriptorName = plugin.getName()
        app.i18nNameKey = plugin.getI18nNameKey()
        app.displayName = Cfp.resolvePluginName(plugin, i18n)

        try {
            app.systemProvided = pluginMetadataManager.isSystemProvided(plugin)
        } catch (Exception error) {
            app.systemProvided = false
            Cfp.note(app.diagnostics, "system-provided flag", error)
        }

        if (app.systemProvided && !includeSystem) {
            continue
        }

        try {
            app.enabled = pluginAccessor.isPluginEnabled(pluginKey)
        } catch (Exception error) {
            app.enabled = false
            Cfp.note(app.diagnostics, "enabled flag", error)
        }

        try {
            Object pluginState = plugin.getPluginState()
            app.state = pluginState == null ? null : pluginState.toString()
        } catch (Exception error) {
            app.state = null
            Cfp.note(app.diagnostics, "plugin state", error)
        }

        try {
            PluginInformation info = plugin.getPluginInformation()
            if (info != null) {
                app.vendor = info.getVendorName()
                app.vendorUrl = info.getVendorUrl()
                app.version = info.getVersion()
            }
        } catch (Exception error) {
            Cfp.note(app.diagnostics, "plugin information", error)
        }

        Collection<ModuleDescriptor<?>> descriptors = new ArrayList<ModuleDescriptor<?>>()
        try {
            descriptors = plugin.getModuleDescriptors()
        } catch (Throwable error) {
            Cfp.note(app.diagnostics, "module descriptors", error)
        }

        Map<String, MacroFootprint> macrosByName = new LinkedHashMap<String, MacroFootprint>()

        for (ModuleDescriptor<?> descriptor : descriptors) {
            ExtensionModuleInfo module = new ExtensionModuleInfo()
            module.key = descriptor.getKey()
            module.completeKey = descriptor.getCompleteKey()
            module.name = descriptor.getName()
            module.descriptorName = descriptor.getClass().getSimpleName()
            module.descriptorClass = descriptor.getClass().getName()
            module.category = Cfp.extensionCategory(module.descriptorName)

            try {
                module.enabled = Boolean.valueOf(pluginAccessor.isPluginModuleEnabled(module.completeKey))
            } catch (Exception error) {
                module.enabled = null
                Cfp.note(app.diagnostics, "module enabled flag " + String.valueOf(module.completeKey), error)
            }

            try {
                Class<?> moduleClass = descriptor.getModuleClass()
                if (moduleClass != null) {
                    module.moduleClass = moduleClass.getName()
                }
            } catch (Throwable ignored) {
                module.moduleClass = null
            }

            app.modules.add(module)

            if (!(descriptor instanceof MacroMetadataSource)) {
                continue
            }

            MacroMetadata metadata = null
            try {
                metadata = ((MacroMetadataSource) descriptor).getMacroMetadata()
            } catch (Exception error) {
                Cfp.note(app.diagnostics, "macro metadata " + String.valueOf(module.completeKey), error)
            }

            String macroName = metadata == null ? null : metadata.getMacroName()
            if (macroName == null || macroName.trim().isEmpty()) {
                macroName = descriptor.getKey()
            }
            if (macroName == null || macroName.trim().isEmpty()) {
                continue
            }

            MacroFootprint macro = macrosByName.get(macroName)
            if (macro == null) {
                macro = new MacroFootprint()
                macro.source = "APP"
                macro.macroName = macroName
                macro.displayName = Cfp.resolveMacroDisplayName(descriptor, metadata)
                macro.descriptorName = module.descriptorName
                macro.moduleEnabled = module.enabled

                if (metadata != null) {
                    try {
                        macro.hidden = metadata.isHidden()
                    } catch (Exception error) {
                        Cfp.note(macro.diagnostics, "hidden flag", error)
                    }
                    try {
                        Set<String> aliases = metadata.getAliases()
                        if (aliases != null) {
                            macro.aliases.addAll(aliases)
                        }
                    } catch (Exception error) {
                        Cfp.note(macro.diagnostics, "aliases", error)
                    }
                    try {
                        Set<String> categories = metadata.getCategories()
                        if (categories != null) {
                            macro.categories.addAll(categories)
                        }
                    } catch (Exception error) {
                        Cfp.note(macro.diagnostics, "categories", error)
                    }
                }

                macrosByName.put(macroName, macro)
            } else if (macro.moduleEnabled == null && module.enabled != null) {
                macro.moduleEnabled = module.enabled
            }
        }

        app.macros.addAll(macrosByName.values())

        if (!scanUsage) {
            for (MacroFootprint macro : app.macros) {
                macro.usageState = Cfp.DISABLED
            }
        } else {
            for (MacroFootprint macro : app.macros) {
                if (System.currentTimeMillis() > scanDeadline) {
                    macro.usageState = Cfp.BUDGET
                    macrosSkippedByBudget++
                    continue
                }

                boolean canonicalOk = Analyzer.scanMacroName(
                    searchManager,
                    macro,
                    macro.macroName,
                    currentSpaceKeys,
                    archivedSpaceKeys,
                    includeArchived
                )

                if (!canonicalOk) {
                    macro.usageState = Cfp.ERROR
                    continue
                }

                boolean aliasError = false
                if (scanAliases) {
                    for (String alias : macro.aliases) {
                        if (alias == null || alias.trim().isEmpty() || alias == macro.macroName) {
                            continue
                        }
                        if (System.currentTimeMillis() > scanDeadline) {
                            aliasError = true
                            macrosSkippedByBudget++
                            Cfp.note(macro.diagnostics, "alias usage scan", new IllegalStateException("scan budget exhausted"))
                            break
                        }
                        boolean aliasOk = Analyzer.scanMacroName(
                            searchManager,
                            macro,
                            alias,
                            currentSpaceKeys,
                            archivedSpaceKeys,
                            includeArchived
                        )
                        if (!aliasOk) {
                            aliasError = true
                        }
                    }
                }

                macro.usageState = aliasError ? Cfp.PARTIAL : Cfp.MEASURED
            }
        }

        app.macros.sort { MacroFootprint a, MacroFootprint b ->
            int byCurrent = Integer.compare(b.getCurrentContentCount(), a.getCurrentContentCount())
            if (byCurrent != 0) {
                return byCurrent
            }
            int byArchived = Integer.compare(b.getArchivedContentCount(), a.getArchivedContentCount())
            if (byArchived != 0) {
                return byArchived
            }
            return (a.macroName ?: "").compareToIgnoreCase(b.macroName ?: "")
        }

        app.finish()
        apps.add(app)
    }

    Map<String, ImpactAssessment> impacts = new HashMap<String, ImpactAssessment>()
    for (AppFootprint app : apps) {
        impacts.put(app.pluginKey, Analyzer.assessImpact(app, scanUsage))
    }

    apps.sort { AppFootprint a, AppFootprint b ->
        ImpactAssessment ia = impacts.get(a.pluginKey)
        ImpactAssessment ib = impacts.get(b.pluginKey)
        int byImpact = Integer.compare(ib.rank, ia.rank)
        if (byImpact != 0) {
            return byImpact
        }
        int byUsage = Long.compare(b.currentAssociations, a.currentAssociations)
        if (byUsage != 0) {
            return byUsage
        }
        String left = a.displayName == null ? "" : a.displayName
        String right = b.displayName == null ? "" : b.displayName
        return left.compareToIgnoreCase(right)
    }

    /* ---- Native User Macros ------------------------------------------------ */

    List<MacroFootprint> userMacros = new ArrayList<MacroFootprint>()

    if (userMacroLibrary != null) {
        Map<String, UserMacroConfig> configs = null
        try {
            configs = userMacroLibrary.getMacros()
        } catch (Exception error) {
            Cfp.note(globalDiagnostics, "user macro inventory", error)
        }

        if (configs != null) {
            for (Map.Entry<String, UserMacroConfig> entry : configs.entrySet()) {
                UserMacroConfig config = entry.getValue()
                if (config == null) {
                    continue
                }

                MacroFootprint macro = new MacroFootprint()
                macro.source = "NATIVE_USER_MACRO"
                macro.descriptorName = "UserMacroConfig"
                macro.moduleEnabled = Boolean.TRUE

                String macroName = config.getName()
                if (macroName == null || macroName.trim().isEmpty()) {
                    macroName = entry.getKey()
                }
                macro.macroName = macroName

                String title = config.getTitle()
                macro.displayName = title != null && !title.trim().isEmpty() ? title : macroName

                String description = config.getDescription()
                macro.description = description

                String bodyType = config.getBodyType()
                macro.bodyType = bodyType

                macro.hidden = config.isHidden()

                List<?> parameters = config.getParameters()
                macro.parameterCount = parameters == null ? 0 : parameters.size()

                Set<String> categories = config.getCategories()
                if (categories != null) {
                    macro.categories.addAll(categories)
                }

                if (!scanUsage) {
                    macro.usageState = Cfp.DISABLED
                } else if (System.currentTimeMillis() > scanDeadline) {
                    macro.usageState = Cfp.BUDGET
                    macrosSkippedByBudget++
                } else {
                    boolean ok = Analyzer.scanMacroName(
                        searchManager,
                        macro,
                        macro.macroName,
                        currentSpaceKeys,
                        archivedSpaceKeys,
                        includeArchived
                    )
                    macro.usageState = ok ? Cfp.MEASURED : Cfp.ERROR
                }

                userMacros.add(macro)
            }
        }
    }

    userMacros.sort { MacroFootprint a, MacroFootprint b ->
        int byCurrent = Integer.compare(b.getCurrentContentCount(), a.getCurrentContentCount())
        if (byCurrent != 0) {
            return byCurrent
        }
        int byArchived = Integer.compare(b.getArchivedContentCount(), a.getArchivedContentCount())
        if (byArchived != 0) {
            return byArchived
        }
        return (a.macroName ?: "").compareToIgnoreCase(b.macroName ?: "")
    }

    /* UserMacroModuleDescriptor implements MacroMetadataSource, so an instance-defined
     * user macro can in principle also surface as an app module and end up on both
     * sides. Whether that happens is unknown and only observable on a live instance.
     * Report the overlap, never resolve it: the point is to make a possible double
     * count visible, not to hide it behind a merge. */
    Set<String> appMacroNames = new HashSet<String>()
    for (AppFootprint app : apps) {
        for (MacroFootprint macro : app.macros) {
            if (macro.source == "APP" && macro.macroName != null) {
                appMacroNames.add(macro.macroName)
            }
        }
    }

    List<String> collidingMacroNames = new ArrayList<String>()
    for (MacroFootprint macro : userMacros) {
        if (macro.macroName != null && appMacroNames.contains(macro.macroName) && !collidingMacroNames.contains(macro.macroName)) {
            collidingMacroNames.add(macro.macroName)
        }
    }

    if (!collidingMacroNames.isEmpty()) {
        int shown = Math.min(5, collidingMacroNames.size())
        List<String> shownNames = new ArrayList<String>(collidingMacroNames.subList(0, shown))
        String nameList = String.join(", ", shownNames)
        if (collidingMacroNames.size() > shown) {
            nameList = nameList + ", ... (+" + String.valueOf(collidingMacroNames.size() - shown) + " more)"
        }
        globalDiagnostics.add("macro name collision: " + collidingMacroNames.size() + " native user macro name(s) also occur as app macros (" +
            nameList + "). Each of those macros may be counted once under its app and once under native user macros; this report does not merge them.")
    }

    /* ---- Summary ----------------------------------------------------------- */

    int disabledApps = 0
    int appsWithCurrentFootprint = 0
    int appsWithArchivedFootprint = 0
    int criticalApps = 0
    int highApps = 0
    int mediumApps = 0
    int lowApps = 0
    int legacyOnlyApps = 0
    int reviewApps = 0
    int noFootprintApps = 0
    int totalProvidedMacros = 0
    int totalEnabledMacros = 0
    int totalCurrentUsedMacros = 0
    int totalArchivedUsedMacros = 0
    int totalBlueprints = 0
    int totalTemplates = 0
    int totalCustomContentModules = 0
    long totalCurrentAssociations = 0L
    long totalArchivedAssociations = 0L
    boolean currentTotalsPartial = false
    boolean archivedTotalsPartial = false
    int totalDiagnostics = globalDiagnostics.size()

    Set<String> globalCurrentContentIds = new HashSet<String>()
    Set<String> globalArchivedContentIds = new HashSet<String>()
    Set<String> globalCurrentSpaces = new HashSet<String>()
    Set<String> globalArchivedSpaces = new HashSet<String>()

    for (AppFootprint app : apps) {
        if (!app.enabled) {
            disabledApps++
        }
        if (app.hasCurrentFootprint()) {
            appsWithCurrentFootprint++
        }
        if (app.hasArchivedFootprint()) {
            appsWithArchivedFootprint++
        }

        ImpactAssessment impact = impacts.get(app.pluginKey)
        if (impact.level == "CRITICAL") criticalApps++
        else if (impact.level == "HIGH") highApps++
        else if (impact.level == "MEDIUM") mediumApps++
        else if (impact.level == "LOW") lowApps++
        else if (impact.level == "LEGACY_ONLY") legacyOnlyApps++
        else if (impact.level == "REVIEW_REQUIRED") reviewApps++
        else if (impact.level == "NO_DETECTABLE_FOOTPRINT") noFootprintApps++

        totalProvidedMacros += app.macros.size()
        totalEnabledMacros += app.enabledMacroCount
        totalCurrentUsedMacros += app.currentUsedMacroCount
        totalArchivedUsedMacros += app.archivedUsedMacroCount
        totalBlueprints += app.getProvidedBlueprintCount()
        totalTemplates += app.getProvidedTemplateCount()
        totalCustomContentModules += app.getCustomContentModuleCount()
        totalCurrentAssociations += app.currentAssociations
        totalArchivedAssociations += app.archivedAssociations
        currentTotalsPartial = currentTotalsPartial || app.currentUsagePartial
        archivedTotalsPartial = archivedTotalsPartial || app.archivedUsagePartial
        totalDiagnostics += app.diagnosticCount

        for (MacroFootprint macro : app.macros) {
            globalCurrentContentIds.addAll(macro.currentContentIds)
            globalArchivedContentIds.addAll(macro.archivedContentIds)
            globalCurrentSpaces.addAll(macro.currentSpaceKeys)
            globalArchivedSpaces.addAll(macro.archivedSpaceKeys)
        }
    }

    int currentUsedUserMacros = 0
    int archivedUsedUserMacros = 0
    long currentUserMacroAssociations = 0L
    long archivedUserMacroAssociations = 0L
    boolean userMacroTotalsPartial = false

    for (MacroFootprint macro : userMacros) {
        if (macro.isCurrentlyUsed()) currentUsedUserMacros++
        if (macro.isArchivedUsed()) archivedUsedUserMacros++
        currentUserMacroAssociations += macro.getCurrentContentCount()
        archivedUserMacroAssociations += macro.getArchivedContentCount()
        if (macro.usageState != Cfp.MEASURED) {
            userMacroTotalsPartial = true
        }
        totalDiagnostics += macro.diagnostics.size()
    }

    String generatedAt = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))

    Map<String, Object> optionsInfo = [
        includeSystem: includeSystem,
        includeDisabled: includeDisabled,
        includeArchived: includeArchived,
        includeModules: includeModules,
        scanUsage: scanUsage,
        scanAliases: scanAliases,
        scanBudgetMs: scanBudgetMs,
        appKey: appKeyFilter,
        numbers: numbers
    ] as LinkedHashMap

    /* =========================================================================
     * JSON
     * ======================================================================= */

    if (format == "json") {
        List<Map<String, Object>> appMaps = new ArrayList<Map<String, Object>>()
        for (AppFootprint app : apps) {
            appMaps.add(app.asMap(includeModules, impacts.get(app.pluginKey)))
        }

        List<Map<String, Object>> userMacroMaps = new ArrayList<Map<String, Object>>()
        for (MacroFootprint macro : userMacros) {
            userMacroMaps.add(macro.asMap())
        }

        Map<String, Object> response = [
            report: [
                name: "Confluence App Footprint Analysis",
                version: "4.3",
                generatedAt: generatedAt
            ] as LinkedHashMap,
            options: optionsInfo,
            spaceStatus: [
                currentSpaces: currentSpaceKeys.size(),
                archivedSpaces: archivedSpaceKeys.size()
            ] as LinkedHashMap,
            summary: [
                apps: apps.size(),
                disabledApps: disabledApps,
                appsWithCurrentFootprint: appsWithCurrentFootprint,
                appsWithArchivedFootprint: appsWithArchivedFootprint,
                impact: [
                    critical: criticalApps,
                    high: highApps,
                    medium: mediumApps,
                    low: lowApps,
                    legacyOnly: legacyOnlyApps,
                    reviewRequired: reviewApps,
                    noDetectableFootprint: noFootprintApps
                ] as LinkedHashMap,
                capabilities: [
                    providedMacros: totalProvidedMacros,
                    enabledMacros: totalEnabledMacros,
                    blueprints: totalBlueprints,
                    templates: totalTemplates,
                    customContentModules: totalCustomContentModules
                ] as LinkedHashMap,
                current: [
                    usedAppMacros: totalCurrentUsedMacros,
                    uniqueContent: globalCurrentContentIds.size(),
                    macroContentAssociations: totalCurrentAssociations,
                    partial: currentTotalsPartial,
                    affectedSpaces: globalCurrentSpaces.size()
                ] as LinkedHashMap,
                archived: [
                    usedAppMacros: totalArchivedUsedMacros,
                    uniqueContent: globalArchivedContentIds.size(),
                    macroContentAssociations: totalArchivedAssociations,
                    partial: archivedTotalsPartial,
                    affectedSpaces: globalArchivedSpaces.size()
                ] as LinkedHashMap,
                nativeUserMacros: [
                    defined: userMacros.size(),
                    currentUsed: currentUsedUserMacros,
                    archivedUsed: archivedUsedUserMacros,
                    currentAssociations: currentUserMacroAssociations,
                    archivedAssociations: archivedUserMacroAssociations,
                    partial: userMacroTotalsPartial
                ] as LinkedHashMap,
                macrosSkippedByBudget: macrosSkippedByBudget,
                diagnostics: totalDiagnostics
            ] as LinkedHashMap,
            diagnostics: globalDiagnostics,
            apps: appMaps,
            nativeUserMacros: userMacroMaps
        ] as LinkedHashMap

        response.put("executionMs", System.currentTimeMillis() - started)

        return Response
            .ok(JsonOutput.prettyPrint(JsonOutput.toJson(response)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    /* =========================================================================
     * CSV
     * ======================================================================= */

    if (format == "csv") {
        StringBuilder csv = new StringBuilder()

        if (csvLevel == "macro") {
            csv.append("source,app,vendor,pluginKey,macro,displayName,moduleEnabled,usageState,currentContent,currentSpaces,archivedContent,archivedSpaces,otherContent,totalContent,aliases,diagnostics\n")

            for (AppFootprint app : apps) {
                for (MacroFootprint macro : app.macros) {
                    csv.append(Cfp.csv("APP")).append(",")
                    csv.append(Cfp.csv(app.displayName)).append(",")
                    csv.append(Cfp.csv(app.vendor)).append(",")
                    csv.append(Cfp.csv(app.pluginKey)).append(",")
                    csv.append(Cfp.csv(macro.macroName)).append(",")
                    csv.append(Cfp.csv(macro.displayName)).append(",")
                    csv.append(Cfp.csv(macro.moduleEnabled)).append(",")
                    csv.append(Cfp.csv(macro.usageState)).append(",")
                    csv.append(macro.getCurrentContentCount()).append(",")
                    csv.append(macro.getCurrentSpaceCount()).append(",")
                    csv.append(macro.getArchivedContentCount()).append(",")
                    csv.append(macro.getArchivedSpaceCount()).append(",")
                    csv.append(macro.getOtherContentCount()).append(",")
                    csv.append(macro.getTotalContentCount()).append(",")
                    csv.append(Cfp.csv(String.join(";", macro.aliases))).append(",")
                    csv.append(Cfp.csv(String.join(" | ", macro.diagnostics))).append("\n")
                }
            }

            for (MacroFootprint macro : userMacros) {
                csv.append(Cfp.csv("NATIVE_USER_MACRO")).append(",")
                csv.append(Cfp.csv("Native Confluence User Macros")).append(",")
                csv.append(Cfp.csv("Local Confluence Configuration")).append(",")
                csv.append(Cfp.csv("")).append(",")
                csv.append(Cfp.csv(macro.macroName)).append(",")
                csv.append(Cfp.csv(macro.displayName)).append(",")
                csv.append(Cfp.csv(Boolean.TRUE)).append(",")
                csv.append(Cfp.csv(macro.usageState)).append(",")
                csv.append(macro.getCurrentContentCount()).append(",")
                csv.append(macro.getCurrentSpaceCount()).append(",")
                csv.append(macro.getArchivedContentCount()).append(",")
                csv.append(macro.getArchivedSpaceCount()).append(",")
                csv.append(macro.getOtherContentCount()).append(",")
                csv.append(macro.getTotalContentCount()).append(",")
                csv.append(Cfp.csv("")).append(",")
                csv.append(Cfp.csv(String.join(" | ", macro.diagnostics))).append("\n")
            }

            return Response
                .ok(csv.toString())
                .type("text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"confluence-macro-footprint.csv\"")
                .build()
        }

        if (csvLevel == "module") {
            csv.append("app,vendor,version,pluginKey,enabled,state,category,moduleName,moduleKey,completeKey,descriptor,descriptorClass,moduleClass,moduleEnabled\n")
            for (AppFootprint app : apps) {
                for (ExtensionModuleInfo module : app.modules) {
                    csv.append(Cfp.csv(app.displayName)).append(",")
                    csv.append(Cfp.csv(app.vendor)).append(",")
                    csv.append(Cfp.csv(app.version)).append(",")
                    csv.append(Cfp.csv(app.pluginKey)).append(",")
                    csv.append(app.enabled).append(",")
                    csv.append(Cfp.csv(app.state)).append(",")
                    csv.append(Cfp.csv(module.category)).append(",")
                    csv.append(Cfp.csv(module.name)).append(",")
                    csv.append(Cfp.csv(module.key)).append(",")
                    csv.append(Cfp.csv(module.completeKey)).append(",")
                    csv.append(Cfp.csv(module.descriptorName)).append(",")
                    csv.append(Cfp.csv(module.descriptorClass)).append(",")
                    csv.append(Cfp.csv(module.moduleClass)).append(",")
                    csv.append(Cfp.csv(module.enabled)).append("\n")
                }
            }

            return Response
                .ok(csv.toString())
                .type("text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"confluence-extension-modules.csv\"")
                .build()
        }

        csv.append("pluginKey,displayName,vendor,version,enabled,state,systemProvided,impact,enabledModules,providedMacros,enabledMacros,blueprints,templates,customContentModules,currentUsedMacros,currentUniqueContent,currentAssociations,currentSpaces,currentComplete,archivedUsedMacros,archivedUniqueContent,archivedAssociations,archivedSpaces,archivedComplete,diagnostics\n")

        for (AppFootprint app : apps) {
            ImpactAssessment impact = impacts.get(app.pluginKey)
            csv.append(Cfp.csv(app.pluginKey)).append(",")
            csv.append(Cfp.csv(app.displayName)).append(",")
            csv.append(Cfp.csv(app.vendor)).append(",")
            csv.append(Cfp.csv(app.version)).append(",")
            csv.append(app.enabled).append(",")
            csv.append(Cfp.csv(app.state)).append(",")
            csv.append(app.systemProvided).append(",")
            csv.append(Cfp.csv(impact.level)).append(",")
            csv.append(app.enabledModuleCount).append(",")
            csv.append(app.macros.size()).append(",")
            csv.append(app.enabledMacroCount).append(",")
            csv.append(app.getProvidedBlueprintCount()).append(",")
            csv.append(app.getProvidedTemplateCount()).append(",")
            csv.append(app.getCustomContentModuleCount()).append(",")
            csv.append(app.currentUsedMacroCount).append(",")
            csv.append(app.currentUniqueContentCount).append(",")
            csv.append(app.currentAssociations).append(",")
            csv.append(app.currentSpaceCount).append(",")
            csv.append(!app.currentUsagePartial).append(",")
            csv.append(app.archivedUsedMacroCount).append(",")
            csv.append(app.archivedUniqueContentCount).append(",")
            csv.append(app.archivedAssociations).append(",")
            csv.append(app.archivedSpaceCount).append(",")
            csv.append(!app.archivedUsagePartial).append(",")
            csv.append(app.diagnosticCount).append("\n")
        }

        return Response
            .ok(csv.toString())
            .type("text/csv; charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=\"confluence-app-footprint.csv\"")
            .build()
    }

    /* =========================================================================
     * HTML
     * ======================================================================= */

    def esc = { Object value -> Cfp.html(value) }
    def num = { Number value -> Cfp.number(value, numberLocale) }

    Map<String, Object> jsonOverrides = new LinkedHashMap<String, Object>()
    jsonOverrides.put("format", "json")
    String linkJson = Cfp.html(Cfp.link(activeParams, jsonOverrides))

    Map<String, Object> csvAppsOverrides = new LinkedHashMap<String, Object>()
    csvAppsOverrides.put("format", "csv")
    csvAppsOverrides.put("level", "app")
    String linkCsvApps = Cfp.html(Cfp.link(activeParams, csvAppsOverrides))

    Map<String, Object> csvMacrosOverrides = new LinkedHashMap<String, Object>()
    csvMacrosOverrides.put("format", "csv")
    csvMacrosOverrides.put("level", "macro")
    String linkCsvMacros = Cfp.html(Cfp.link(activeParams, csvMacrosOverrides))

    Map<String, Object> csvModulesOverrides = new LinkedHashMap<String, Object>()
    csvModulesOverrides.put("format", "csv")
    csvModulesOverrides.put("level", "module")
    String linkCsvModules = Cfp.html(Cfp.link(activeParams, csvModulesOverrides))

    Map<String, Object> archivedOverrides = new LinkedHashMap<String, Object>()
    archivedOverrides.put("includeArchived", includeArchived ? "false" : null)
    String linkArchived = Cfp.html(Cfp.link(activeParams, archivedOverrides))

    Map<String, Object> modulesOverrides = new LinkedHashMap<String, Object>()
    modulesOverrides.put("includeModules", includeModules ? null : "true")
    String linkModules = Cfp.html(Cfp.link(activeParams, modulesOverrides))

    Map<String, Object> systemOverrides = new LinkedHashMap<String, Object>()
    systemOverrides.put("includeSystem", includeSystem ? null : "true")
    String linkSystem = Cfp.html(Cfp.link(activeParams, systemOverrides))

    Map<String, Object> disabledOverrides = new LinkedHashMap<String, Object>()
    disabledOverrides.put("includeDisabled", includeDisabled ? "false" : null)
    String linkDisabled = Cfp.html(Cfp.link(activeParams, disabledOverrides))

    def usageCell = { MacroFootprint macro, boolean archived ->
        if (macro.usageState == Cfp.MEASURED || macro.usageState == Cfp.PARTIAL) {
            Number value = archived ? Integer.valueOf(macro.getArchivedContentCount()) : Integer.valueOf(macro.getCurrentContentCount())
            String suffix = macro.usageState == Cfp.PARTIAL ? '<span class="warn" title="Partial measurement">*</span>' : ''
            return esc(num(value)) + suffix
        }
        if (macro.usageState == Cfp.DISABLED) {
            return '<span class="muted" title="scanUsage=false">off</span>'
        }
        if (macro.usageState == Cfp.BUDGET) {
            return '<span class="warn" title="Scan budget exhausted before this macro was measured">n/m</span>'
        }
        return '<span class="bad" title="Usage read failed; see diagnostics">err</span>'
    }

    StringBuilder html = new StringBuilder(1 << 20)

    html.append("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Confluence App Footprint Analysis</title>
<style>
:root {
    --page-bg:#f7f8f9; --surface:#fff; --surface-subtle:#f7f8f9;
    --text:#172b4d; --muted:#626f86; --border:#dcdfe4; --border-subtle:#ebecf0;
    --blue:#0c66e4; --blue-bg:#e9f2ff;
    --green:#216e4e; --green-bg:#eefbf5; --green-border:#baf3db;
    --yellow:#7f5f01; --yellow-bg:#fff7d6; --yellow-border:#f5cd47;
    --orange:#974f0c; --orange-bg:#fff3eb; --orange-border:#fec195;
    --red:#ae2e24; --red-bg:#ffeceb; --red-border:#fd9891;
    --purple:#5e4db2; --purple-bg:#f3f0ff; --purple-border:#b8acf6;
    --shadow:0 1px 2px rgba(9,30,66,.08),0 1px 3px rgba(9,30,66,.06);
}
*{box-sizing:border-box}
body{margin:0;background:var(--page-bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;font-size:14px;line-height:1.45}
.page{max-width:1660px;margin:0 auto;padding:28px 32px 44px}
.page-header{display:flex;justify-content:space-between;align-items:flex-start;gap:24px;margin-bottom:20px}
.page-title{margin:0 0 4px;font-size:24px;font-weight:650}.subtitle{color:var(--muted);font-size:13px}
.actions{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end}.button{display:inline-flex;align-items:center;height:34px;padding:0 12px;border:1px solid var(--border);border-radius:5px;background:#fff;color:var(--text);text-decoration:none;font-size:13px;font-weight:600}.button.on{background:var(--blue-bg);border-color:var(--blue);color:var(--blue)}
.summary-grid{display:grid;grid-template-columns:repeat(8,minmax(125px,1fr));gap:10px;margin-bottom:16px}.summary-card{min-height:90px;padding:14px 15px;background:#fff;border:1px solid var(--border);border-radius:7px;box-shadow:var(--shadow)}.summary-value{font-size:24px;font-weight:650}.summary-label{margin-top:4px;color:var(--muted);font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.035em}
.notice,.diag{margin-bottom:16px;padding:12px 16px;border-radius:6px;background:var(--blue-bg);border:1px solid #b6d6ff;font-size:13px}.diag{background:var(--yellow-bg);border-color:var(--yellow-border)}.diag ul{margin:8px 0 0;padding-left:20px}
.legend{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:18px}.search{flex:1;min-width:280px;height:38px;padding:0 11px;border:1px solid var(--border);border-radius:5px;background:#fff;font-size:14px}select{height:38px;padding:0 9px;border:1px solid var(--border);border-radius:5px;background:#fff}.checkbox-label{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:13px}
.app-card,.user-macro-card{background:#fff;border:1px solid var(--border);border-radius:8px;margin-bottom:15px;box-shadow:var(--shadow);overflow:hidden}.app-card.is-disabled{border-left:4px solid var(--yellow)}.hidden{display:none!important}.app-header{padding:17px 20px;border-bottom:1px solid var(--border-subtle)}.app-header-row{display:flex;justify-content:space-between;align-items:flex-start;gap:24px}.app-name{font-size:18px;font-weight:650}.app-meta{margin-top:3px;color:var(--muted);font-size:12px}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;overflow-wrap:anywhere}
.badges{display:flex;flex:0 0 auto;flex-wrap:wrap;justify-content:flex-end;align-items:flex-start;gap:6px}.badge{display:inline-flex;align-items:center;height:24px;padding:0 9px;border-radius:999px;border:1px solid transparent;font-size:10px;font-weight:700;white-space:nowrap}.badge-current,.badge-low{color:var(--green);background:var(--green-bg);border-color:var(--green-border)}.badge-archived,.badge-high{color:var(--orange);background:var(--orange-bg);border-color:var(--orange-border)}.badge-critical,.badge-diag{color:var(--red);background:var(--red-bg);border-color:var(--red-border)}.badge-medium{color:var(--yellow);background:var(--yellow-bg);border-color:var(--yellow-border)}.badge-review,.badge-native,.badge-system{color:var(--purple);background:var(--purple-bg);border-color:var(--purple-border)}.badge-none,.badge-capability{color:var(--muted);background:#f1f2f4;border-color:var(--border)}.badge-disabled{color:var(--yellow);background:var(--yellow-bg);border-color:var(--yellow-border)}
.impact-reasons{margin:9px 0 0;padding-left:20px;color:var(--muted);font-size:12px}.metric-group-title{padding:8px 16px;background:var(--surface-subtle);border-top:1px solid var(--border-subtle);color:var(--muted);font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.metrics{display:grid;grid-template-columns:repeat(6,minmax(120px,1fr));border-bottom:1px solid var(--border-subtle)}.metric{min-height:76px;padding:12px 16px;border-right:1px solid var(--border-subtle)}.metric:last-child{border-right:0}.metric-value{font-size:19px;font-weight:650}.metric-label{margin-top:3px;color:var(--muted);font-size:11px}.archived-row{background:#fffaf7}.archived-value{color:var(--orange)}
.section{padding:16px 20px;border-bottom:1px solid var(--border-subtle)}.section:last-child{border-bottom:0}.section-title{margin-bottom:10px;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.category-grid{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:8px;margin-bottom:10px}.category{padding:9px 11px;background:var(--surface-subtle);border:1px solid var(--border-subtle);border-radius:5px}.category-name{font-size:12px;font-weight:650}.category-count{color:var(--muted);font-size:11px}.coverage{margin-top:10px;padding:10px 12px;background:#fafbfc;border:1px solid var(--border-subtle);border-radius:5px;color:var(--muted);font-size:12px}.main-section-title{margin:30px 0 12px;font-size:21px}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px 10px;text-align:left;border-bottom:1px solid var(--border-subtle);vertical-align:top}th{background:var(--surface-subtle);color:var(--muted);font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.035em;white-space:nowrap}tbody tr:hover{background:var(--surface-subtle)}.num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}.archived-num{color:var(--orange)}.good{color:var(--green);font-weight:600}.warn{color:var(--yellow);font-weight:600}.bad{color:var(--red);font-weight:600}.muted{color:var(--muted)}.empty{color:var(--muted);font-style:italic;padding:5px 0}.table-wrap{overflow-x:auto}
details{margin-top:9px}summary{cursor:pointer;color:var(--blue);font-size:12px;font-weight:600}.space-list{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.space-pill{padding:2px 7px;border:1px solid var(--border);border-radius:999px;background:var(--surface-subtle);font:11px ui-monospace,monospace}.space-pill-archived{color:var(--orange);background:var(--orange-bg);border-color:var(--orange-border)}.footer{margin-top:24px;padding:16px 20px;background:#fff;border:1px solid var(--border);border-radius:8px;color:var(--muted);font-size:12px}.footer ul{margin:8px 0;padding-left:18px}
@media(max-width:1350px){.summary-grid{grid-template-columns:repeat(4,1fr)}.metrics{grid-template-columns:repeat(3,1fr)}.category-grid{grid-template-columns:repeat(3,1fr)}}
@media(max-width:850px){.page{padding:18px}.page-header{flex-direction:column}.summary-grid,.metrics,.category-grid{grid-template-columns:repeat(2,1fr)}.toolbar{flex-direction:column;align-items:stretch}.search{min-width:0}.app-header-row{flex-direction:column}.badges{justify-content:flex-start}}
@media print{body{background:#fff}.actions,.toolbar{display:none}.app-card,.summary-card,.user-macro-card,.footer{box-shadow:none;break-inside:avoid}}
</style>
</head>
<body>
<div class="page">

<div class="page-header">
  <div>
    <h1 class="page-title">Confluence App Footprint Analysis</h1>
    <div class="subtitle">
      Generated ${esc(generatedAt)} &nbsp;&middot;&nbsp;
      Current spaces ${num(currentSpaceKeys.size())} &nbsp;&middot;&nbsp;
      Archived spaces ${num(archivedSpaceKeys.size())}
    </div>
  </div>
  <div class="actions">
    <a class="button" href="${linkJson}">JSON</a>
    <a class="button" href="${linkCsvApps}">CSV Apps</a>
    <a class="button" href="${linkCsvMacros}">CSV Macros</a>
    <a class="button" href="${linkCsvModules}">CSV Modules</a>
    <a class="button ${includeArchived ? 'on' : ''}" href="${linkArchived}">Archived</a>
    <a class="button ${includeModules ? 'on' : ''}" href="${linkModules}">Modules</a>
    <a class="button ${includeSystem ? 'on' : ''}" href="${linkSystem}">System apps</a>
    <a class="button ${includeDisabled ? 'on' : ''}" href="${linkDisabled}">Disabled apps</a>
  </div>
</div>

<div class="summary-grid">
  <div class="summary-card"><div class="summary-value">${num(apps.size())}</div><div class="summary-label">Apps in report${disabledApps > 0 ? ' (' + num(disabledApps) + ' disabled)' : ''}</div></div>
  <div class="summary-card"><div class="summary-value">${num(appsWithCurrentFootprint)}</div><div class="summary-label">Apps with current footprint</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalProvidedMacros)}</div><div class="summary-label">Provided app macros</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalCurrentUsedMacros)}</div><div class="summary-label">Used app macros · current</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalCurrentAssociations)}${currentTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="summary-label">Macro associations · current</div></div>
  <div class="summary-card"><div class="summary-value">${includeArchived ? num(totalArchivedAssociations) + (archivedTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : '') : Cfp.NA}</div><div class="summary-label">Macro associations · archived</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalBlueprints + totalTemplates)}</div><div class="summary-label">Blueprints / templates</div></div>
  <div class="summary-card"><div class="summary-value">${num(userMacros.size())}</div><div class="summary-label">Native user macros</div></div>
</div>

<div class="notice">
  <strong>Measurement model:</strong>
  Current macro usage only includes content in spaces with <span class="mono">SpaceStatus.CURRENT</span>.
  Archived dependencies are separated. Blueprint, template, custom-content, UI, REST, listener and job modules are inventory signals unless a dedicated resolver exists.
</div>
""")

    if (macrosSkippedByBudget > 0 || totalDiagnostics > 0) {
        html.append("""<div class="diag"><strong>Measurement notes</strong><ul>""")
        if (macrosSkippedByBudget > 0) {
            html.append("<li>" + esc(num(macrosSkippedByBudget)) + " macro scan(s) were not measured because the scan budget of " + esc(num(scanBudgetMs)) + " ms was exhausted. They show <span class=\"warn\">n/m</span>, not zero.</li>")
        }
        if (totalDiagnostics > 0) {
            html.append("<li>" + esc(num(totalDiagnostics)) + " suppressed read error(s) were recorded. Affected apps/macros carry diagnostics in their detail sections.</li>")
        }
        for (String entry : globalDiagnostics) {
            html.append("<li class=\"mono\">" + esc(entry) + "</li>")
        }
        html.append("</ul></div>")
    }

    html.append("""
<div class="legend">
  <span class="badge badge-critical">CRITICAL ${criticalApps}</span>
  <span class="badge badge-high">HIGH ${highApps}</span>
  <span class="badge badge-medium">MEDIUM ${mediumApps}</span>
  <span class="badge badge-low">LOW ${lowApps}</span>
  <span class="badge badge-archived">LEGACY ONLY ${legacyOnlyApps}</span>
  <span class="badge badge-review">REVIEW REQUIRED ${reviewApps}</span>
  <span class="badge badge-none">NO DETECTABLE FOOTPRINT ${noFootprintApps}</span>
</div>

<div class="toolbar">
  <input id="search" class="search" type="search" placeholder="Search app, vendor, plugin key, macro or capability..." oninput="filterReport()">
  <select id="impactFilter" onchange="filterReport()">
    <option value="">All impact levels</option>
    <option value="CRITICAL">Critical</option>
    <option value="HIGH">High</option>
    <option value="MEDIUM">Medium</option>
    <option value="LOW">Low</option>
    <option value="LEGACY_ONLY">Legacy only</option>
    <option value="REVIEW_REQUIRED">Review required</option>
    <option value="NO_DETECTABLE_FOOTPRINT">No detectable footprint</option>
  </select>
  <label class="checkbox-label"><input id="currentOnly" type="checkbox" onchange="filterReport()"> Current footprint only</label>
  <label class="checkbox-label"><input id="diagnosticsOnly" type="checkbox" onchange="filterReport()"> Diagnostics only</label>
</div>
""")

    for (AppFootprint app : apps) {
        ImpactAssessment impact = impacts.get(app.pluginKey)

        StringBuilder searchText = new StringBuilder()
        searchText.append(app.displayName ?: "").append(" ")
            .append(app.descriptorName ?: "").append(" ")
            .append(app.vendor ?: "").append(" ")
            .append(app.pluginKey ?: "").append(" ")
        for (String category : app.categoryCounts.keySet()) {
            searchText.append(category).append(" ")
        }
        for (MacroFootprint macro : app.macros) {
            searchText.append(macro.macroName ?: "").append(" ").append(macro.displayName ?: "").append(" ")
        }

        String impactClass = "badge-none"
        if (impact.level == "CRITICAL") impactClass = "badge-critical"
        else if (impact.level == "HIGH") impactClass = "badge-high"
        else if (impact.level == "MEDIUM") impactClass = "badge-medium"
        else if (impact.level == "LOW") impactClass = "badge-low"
        else if (impact.level == "LEGACY_ONLY") impactClass = "badge-archived"
        else if (impact.level == "REVIEW_REQUIRED") impactClass = "badge-review"

        html.append("""<div class="app-card${app.enabled ? '' : ' is-disabled'} report-item"
 data-search="${esc(searchText.toString().toLowerCase(Locale.ROOT))}"
 data-current-footprint="${app.hasCurrentFootprint()}"
 data-impact="${esc(impact.level)}"
 data-diagnostics="${app.diagnosticCount > 0}">

<div class="app-header">
  <div class="app-header-row">
    <div>
      <div class="app-name">${esc(app.displayName)}</div>
      <div class="app-meta">${esc(app.vendor ?: 'Unknown vendor')}${app.version != null ? ' &middot; v' + esc(app.version) : ''}</div>
      <div class="app-meta">Descriptor name: <strong>${esc(app.descriptorName ?: Cfp.NA)}</strong></div>
      <div class="app-meta mono">${esc(app.pluginKey)}</div>
    </div>
    <div class="badges">
      <span class="badge ${impactClass}">IMPACT: ${esc(impact.label.toUpperCase(Locale.ROOT))}</span>
""")

        if (app.hasCurrentFootprint()) {
            html.append('<span class="badge badge-current">CURRENT FOOTPRINT</span>')
        }
        if (includeArchived && app.hasArchivedFootprint()) {
            html.append('<span class="badge badge-archived">ARCHIVED LEGACY FOOTPRINT</span>')
        }
        if (!app.enabled) {
            html.append('<span class="badge badge-disabled">DISABLED' + (app.state != null ? ' · ' + esc(app.state) : '') + '</span>')
        }
        if (app.systemProvided) {
            html.append('<span class="badge badge-system">SYSTEM PROVIDED</span>')
        }
        if (app.diagnosticCount > 0) {
            html.append('<span class="badge badge-diag">DIAGNOSTICS ' + esc(num(app.diagnosticCount)) + '</span>')
        }

        html.append("""
    </div>
  </div>
  <ul class="impact-reasons">
""")
        for (String reason : impact.reasons) {
            html.append("<li>" + esc(reason) + "</li>")
        }
        html.append("""</ul>
</div>

<div class="metric-group-title">Current Footprint</div>
<div class="metrics">
  <div class="metric"><div class="metric-value">${num(app.enabledModuleCount)}</div><div class="metric-label">Enabled Extension Modules</div></div>
  <div class="metric"><div class="metric-value">${num(app.macros.size())}</div><div class="metric-label">Provided Macros (${num(app.enabledMacroCount)} enabled)</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentUsedMacroCount)}</div><div class="metric-label">Used Macros</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentUniqueContentCount)}${app.currentUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Unique Current Content</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentAssociations)}${app.currentUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Current Macro-Content Associations</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentSpaceCount)}</div><div class="metric-label">Current Spaces</div></div>
</div>
""")

        if (includeArchived) {
            html.append("""
<div class="metric-group-title">Archived / Legacy Footprint</div>
<div class="metrics archived-row">
  <div class="metric"><div class="metric-value archived-value">${num(app.archivedUsedMacroCount)}</div><div class="metric-label">Macros Used in Archived Spaces</div></div>
  <div class="metric"><div class="metric-value archived-value">${num(app.archivedUniqueContentCount)}${app.archivedUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Archived Content</div></div>
  <div class="metric"><div class="metric-value archived-value">${num(app.archivedAssociations)}${app.archivedUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Archived Macro-Content Associations</div></div>
  <div class="metric"><div class="metric-value archived-value">${num(app.archivedSpaceCount)}</div><div class="metric-label">Archived Spaces</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentUniqueContentCount + app.archivedUniqueContentCount + app.otherUniqueContentCount)}</div><div class="metric-label">Total Unique Content</div></div>
  <div class="metric"><div class="metric-value">${num(app.currentAssociations + app.archivedAssociations + app.otherAssociations)}</div><div class="metric-label">Total Macro-Content Associations</div></div>
</div>
""")
        }

        html.append("""
<div class="section">
  <div class="section-title">Confluence Extension Capabilities</div>
  <div class="category-grid">
""")

        if (app.categoryCounts.isEmpty()) {
            html.append('<div class="empty">No enabled extension modules detected.</div>')
        } else {
            for (Map.Entry<String, Integer> category : app.categoryCounts.entrySet()) {
                html.append('<div class="category"><div class="category-name">' + esc(category.getKey()) + '</div><div class="category-count">' + esc(num(category.getValue())) + ' modules</div></div>')
            }
        }

        html.append("""
  </div>
  <div class="coverage"><strong>Coverage:</strong> Macro footprint is measured from the Confluence content index. Blueprint/template/custom-content modules are inventoried but do not receive a generic usage count. UI, REST, servlet, job and listener modules are capability signals only.</div>
  <details><summary>Extension module types</summary><div class="table-wrap"><table><thead><tr><th>Descriptor</th><th class="num">Modules</th></tr></thead><tbody>
""")
        for (Map.Entry<String, Integer> type : app.moduleTypeCounts.entrySet()) {
            html.append('<tr><td class="mono">' + esc(type.getKey()) + '</td><td class="num">' + esc(num(type.getValue())) + '</td></tr>')
        }
        html.append('</tbody></table></div></details></div>')

        List<ExtensionModuleInfo> persistenceModules = app.getPersistenceModules()
        if (!persistenceModules.isEmpty()) {
            html.append("""<div class="section"><div class="section-title">Content Creation / Persistence Capabilities</div><div class="table-wrap"><table><thead><tr><th>Category</th><th>Name</th><th>Complete Key</th><th>Descriptor</th></tr></thead><tbody>""")
            for (ExtensionModuleInfo module : persistenceModules) {
                html.append('<tr><td>' + esc(module.category) + '</td><td>' + esc(module.name ?: Cfp.NA) + '</td><td class="mono">' + esc(module.completeKey) + '</td><td class="mono">' + esc(module.descriptorName) + '</td></tr>')
            }
            html.append('</tbody></table></div><div class="coverage">These capabilities may persist app-specific content/configuration. Their created-object count is not generically derivable from the plugin module descriptor and should be handled by a dedicated app resolver where needed.</div></div>')
        }

        html.append("""<div class="section"><div class="section-title">Macro Footprint</div>""")
        if (app.macros.isEmpty()) {
            html.append('<div class="empty">This app exposes no macro metadata sources.</div>')
        } else {
            html.append('<div class="table-wrap"><table><thead><tr><th>Macro</th><th>Macro Key</th><th>Status</th><th class="num">Current</th><th class="num">Current Spaces</th>')
            if (includeArchived) {
                html.append('<th class="num">Archived</th><th class="num">Archived Spaces</th>')
            }
            html.append('<th>Current Content Types</th><th class="num">Total</th></tr></thead><tbody>')

            for (MacroFootprint macro : app.macros) {
                String moduleStatus = macro.moduleEnabled == null ? '<span class="bad">unknown</span>' : (macro.moduleEnabled.booleanValue() ? 'Enabled' : '<span class="muted">Disabled</span>')
                html.append('<tr><td><strong>' + esc(macro.displayName) + '</strong>' + (macro.hidden ? '<div class="muted">Hidden from Macro Browser</div>' : '') + '</td>')
                html.append('<td class="mono">' + esc(macro.macroName) + '</td>')
                html.append('<td>' + moduleStatus + '</td>')
                html.append('<td class="num">' + usageCell(macro, false) + '</td>')
                html.append('<td class="num">' + (macro.isMeasured() ? esc(num(macro.getCurrentSpaceCount())) : Cfp.NA) + '</td>')
                if (includeArchived) {
                    html.append('<td class="num archived-num">' + usageCell(macro, true) + '</td>')
                    html.append('<td class="num archived-num">' + (macro.isMeasured() ? esc(num(macro.getArchivedSpaceCount())) : Cfp.NA) + '</td>')
                }
                html.append('<td>' + esc(Cfp.contentTypeText(macro.getCurrentContentTypeCounts())) + '</td>')
                html.append('<td class="num">' + (macro.isMeasured() ? esc(num(macro.getTotalContentCount())) : Cfp.NA) + '</td></tr>')

                if (!macro.currentSpaceKeys.isEmpty() || (includeArchived && !macro.archivedSpaceKeys.isEmpty()) || !macro.aliases.isEmpty() || !macro.diagnostics.isEmpty()) {
                    int colspan = includeArchived ? 9 : 7
                    html.append('<tr><td colspan="' + String.valueOf(colspan) + '"><details><summary>Macro details</summary>')
                    if (!macro.aliases.isEmpty()) {
                        html.append('<div style="margin-top:8px"><strong>Aliases:</strong> <span class="mono">' + esc(String.join(", ", macro.aliases)) + '</span></div>')
                    }
                    if (!macro.currentSpaceKeys.isEmpty()) {
                        html.append('<div class="muted" style="margin-top:10px">Current spaces</div><div class="space-list">')
                        for (String key : macro.currentSpaceKeys) {
                            html.append('<span class="space-pill">' + esc(key) + '</span>')
                        }
                        html.append('</div>')
                    }
                    if (includeArchived && !macro.archivedSpaceKeys.isEmpty()) {
                        html.append('<div class="muted" style="margin-top:10px">Archived spaces</div><div class="space-list">')
                        for (String key : macro.archivedSpaceKeys) {
                            html.append('<span class="space-pill space-pill-archived">' + esc(key) + '</span>')
                        }
                        html.append('</div>')
                    }
                    if (!macro.diagnostics.isEmpty()) {
                        html.append('<div class="muted" style="margin-top:10px">Diagnostics</div><ul class="mono">')
                        for (String note : macro.diagnostics) {
                            html.append('<li>' + esc(note) + '</li>')
                        }
                        html.append('</ul>')
                    }
                    html.append('</details></td></tr>')
                }
            }

            html.append('</tbody></table></div>')
        }
        html.append('</div>')

        if (app.diagnosticCount > 0) {
            html.append('<div class="section"><div class="section-title">Diagnostics</div><details><summary>' + esc(num(app.diagnosticCount)) + ' suppressed read error(s)</summary><ul>')
            for (String note : app.diagnostics) {
                html.append('<li class="mono">' + esc(note) + '</li>')
            }
            for (MacroFootprint macro : app.macros) {
                for (String note : macro.diagnostics) {
                    html.append('<li class="mono">' + esc(macro.macroName) + ': ' + esc(note) + '</li>')
                }
            }
            html.append('</ul></details></div>')
        }

        if (includeModules) {
            html.append('<div class="section"><details><summary>All plugin modules (' + esc(num(app.modules.size())) + ')</summary><div class="table-wrap"><table><thead><tr><th>Category</th><th>Descriptor</th><th>Name</th><th>Complete Key</th><th>Module Class</th><th>Status</th></tr></thead><tbody>')
            for (ExtensionModuleInfo module : app.modules) {
                String moduleStatus = module.enabled == null ? '<span class="bad">unknown</span>' : (module.enabled.booleanValue() ? 'Enabled' : 'Disabled')
                html.append('<tr><td>' + esc(module.category) + '</td><td class="mono">' + esc(module.descriptorName) + '</td><td>' + esc(module.name) + '</td><td class="mono">' + esc(module.completeKey) + '</td><td class="mono">' + esc(module.moduleClass) + '</td><td>' + moduleStatus + '</td></tr>')
            }
            html.append('</tbody></table></div></details></div>')
        }

        html.append('</div>')
    }

    /* ---- Native User Macros ------------------------------------------------ */

    html.append("""
<h2 class="main-section-title">Native Confluence User Macros</h2>
<div class="notice">These macros come from Confluence's native User Macro Library (<span class="mono">/admin/usermacros.action</span>). They are deliberately not attributed to a Marketplace app.</div>
<div class="user-macro-card">
  <div class="app-header"><div class="app-header-row"><div><div class="app-name">Native Confluence User Macros</div><div class="app-meta">Local Confluence configuration</div></div><div class="badges"><span class="badge badge-native">NATIVE CONFIGURATION</span></div></div></div>
  <div class="metrics">
    <div class="metric"><div class="metric-value">${num(userMacros.size())}</div><div class="metric-label">Defined User Macros</div></div>
    <div class="metric"><div class="metric-value">${num(currentUsedUserMacros)}</div><div class="metric-label">Used · Current</div></div>
    <div class="metric"><div class="metric-value">${num(currentUserMacroAssociations)}${userMacroTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Current Associations</div></div>
    <div class="metric"><div class="metric-value archived-value">${includeArchived ? num(archivedUsedUserMacros) : Cfp.NA}</div><div class="metric-label">Used · Archived</div></div>
    <div class="metric"><div class="metric-value archived-value">${includeArchived ? num(archivedUserMacroAssociations) + (userMacroTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : '') : Cfp.NA}</div><div class="metric-label">Archived Associations</div></div>
  </div>
  <div class="section"><div class="table-wrap"><table><thead><tr><th>Title</th><th>Macro Name</th><th>Body Type</th><th>Visibility</th><th class="num">Parameters</th><th>Status</th><th class="num">Current</th>${includeArchived ? '<th class="num">Archived</th>' : ''}<th class="num">Total</th></tr></thead><tbody>
""")

    if (userMacros.isEmpty()) {
        html.append('<tr><td colspan="9" class="empty">No native Confluence User Macros found.</td></tr>')
    } else {
        for (MacroFootprint macro : userMacros) {
            html.append('<tr><td><strong>' + esc(macro.displayName) + '</strong>' + (macro.description != null && !macro.description.trim().isEmpty() ? '<div class="muted">' + esc(macro.description) + '</div>' : '') + '</td>')
            html.append('<td class="mono">' + esc(macro.macroName) + '</td>')
            html.append('<td>' + esc(macro.bodyType ?: Cfp.NA) + '</td>')
            html.append('<td>' + (macro.hidden ? 'Hidden' : 'Visible') + '</td>')
            html.append('<td class="num">' + esc(num(macro.parameterCount)) + '</td>')
            html.append('<td>' + esc(macro.usageState) + '</td>')
            html.append('<td class="num">' + usageCell(macro, false) + '</td>')
            if (includeArchived) {
                html.append('<td class="num archived-num">' + usageCell(macro, true) + '</td>')
            }
            html.append('<td class="num">' + (macro.isMeasured() ? esc(num(macro.getTotalContentCount())) : Cfp.NA) + '</td></tr>')
        }
    }

    html.append("""
  </tbody></table></div></div>
</div>

<div class="footer">
  <strong>Interpretation notes</strong>
  <ul>
    <li>Macro usage is deduplicated by Confluence content ID for each macro name. Multiple copies of the same macro on one content object count once for that macro.</li>
    <li>A page containing two different macros from the same app contributes two macro-content associations but one unique content object for the app.</li>
    <li>Current usage contains only content assigned to <span class="mono">SpaceStatus.CURRENT</span>. Archived spaces are kept separate.</li>
    <li>Content not attributable to CURRENT or ARCHIVED spaces is retained as "other" in JSON/CSV detail and is never promoted into Current usage.</li>
    <li>Provided Macros are discovered through <span class="mono">MacroMetadataSource</span>, including modern XHTML macros. A disabled app/module can still have content references and therefore a measurable footprint.</li>
    <li>Native User Macros are read from <span class="mono">UserMacroLibrary</span>. Confluence may hide a user macro from that library when a plugin macro with the same name takes precedence.</li>
    <li>Blueprint, template and custom-content module counts are capability/inventory signals. Their actual generated/persisted object counts require dedicated resolvers.</li>
    <li>Impact is a local assessment heuristic configured in this script; it is not an Atlassian classification.</li>
    <li>"No detectable footprint" does not mean "unused": UI-only, REST-only, background-service and proprietary app data can exist without a generic footprint signal.</li>
    <li>This report is read-only, performs no writes and makes no outbound network call.</li>
  </ul>
  Report version 4.3 &nbsp;&middot;&nbsp; execution time ${num(System.currentTimeMillis() - started)} ms
</div>

</div>
<script>
function filterReport(){
  var q=document.getElementById('search').value.trim().toLowerCase();
  var impact=document.getElementById('impactFilter').value;
  var currentOnly=document.getElementById('currentOnly').checked;
  var diagnosticsOnly=document.getElementById('diagnosticsOnly').checked;
  document.querySelectorAll('.report-item').forEach(function(item){
    var text=(item.dataset.search||'').toLowerCase();
    var matchesSearch=q.length===0||text.includes(q);
    var matchesImpact=impact.length===0||item.dataset.impact===impact;
    var matchesCurrent=!currentOnly||item.dataset.currentFootprint==='true';
    var matchesDiagnostics=!diagnosticsOnly||item.dataset.diagnostics==='true';
    item.classList.toggle('hidden',!(matchesSearch&&matchesImpact&&matchesCurrent&&matchesDiagnostics));
  });
}
</script>
</body>
</html>
""")

    return Response
        .ok(html.toString())
        .type("text/html; charset=UTF-8")
        .build()
}
