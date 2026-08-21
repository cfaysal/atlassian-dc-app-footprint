/* =============================================================================
 * Jira Data Center - App Footprint Analysis
 * ScriptRunner Custom REST Endpoint. Read-only, admin-gated.
 *
 * Version 3.1
 *
 * Purpose
 *   Measures how much detectable configuration and data footprint every
 *   installed app leaves behind in this Jira instance. Built as an audit
 *   instrument for customer instances: it never writes, never calls out to the
 *   network, and produces a self-contained artifact (HTML / JSON / CSV).
 *
 * Platform
 *   javax / jakarta neutral. The JAX-RS Response class is resolved at runtime and
 *   the closure parameter is untyped, so no jakarta.* or javax.* import is present.
 *   The namespace a ScriptRunner script needs follows the SCRIPTRUNNER version,
 *   not the Jira version: ScriptRunner 10.x and above use jakarta.ws.rs.*, 8.x to
 *   9.x use javax.ws.rs.*. This file runs on either line without being edited.
 *
 * Parameters (all optional)
 *   format=html|json|csv      default html
 *   includeSystem=true|false  default false  system-provided apps
 *   includeDisabled=true|false default true  installed but disabled apps
 *   includeDrafts=true|false  default false  draft workflows in the scan
 *   includeModules=true|false default false  full module list per app
 *   includeReach=true|false   default true   trace references through workflow
 *                                            schemes and screen schemes to the
 *                                            projects that actually use them
 *   issueCounts=true|false    default true   count issues per app custom field
 *                                            and per reached project
 *   issueBudgetMs=<long>      default 120000 time budget for the expensive
 *                                            phases: issue counting AND the
 *                                            screen scheme index behind
 *                                            includeReach. 0 = unlimited.
 *                                            Anything beyond the budget is
 *                                            reported as NOT MEASURED, never
 *                                            as zero.
 *   numbers=de|en             default de     thousands separator style
 *
 * Reporting discipline
 *   A failed read is never rendered as a measured zero. Every suppressed error
 *   is recorded per field and per app and is surfaced in the report.
 * ========================================================================== */

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.ApplicationProperties
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.screen.FieldScreen
import com.atlassian.jira.issue.fields.screen.FieldScreenManager
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager
import com.atlassian.jira.issue.fields.screen.FieldScreenTab
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager
import com.atlassian.jira.issue.issuetype.IssueType
import com.atlassian.jira.project.Project
import com.atlassian.jira.scheme.Scheme
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.util.BuildUtilsInfo
import com.atlassian.jira.util.I18nHelper
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowSchemeManager

import com.atlassian.plugin.ModuleDescriptor
import com.atlassian.plugin.Plugin
import com.atlassian.plugin.PluginAccessor
import com.atlassian.plugin.PluginInformation
import com.atlassian.plugin.metadata.PluginMetadataManager

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import org.ofbiz.core.entity.GenericValue

import groovy.json.JsonOutput
import groovy.transform.BaseScript

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@BaseScript CustomEndpointDelegate delegate

/* =============================================================================
 * Utility - deliberately free of any Jira type so it stays unit-testable
 * ========================================================================== */

class Fp {

    static final String NA = "—"

    /* A needle can only occur inside a single token, so shorter tokens are
     * dropped. Needles below this length fall back to a raw scan. */
    static final int MIN_TOKEN = 4

    static final String MEASURED = "measured"
    static final String DISABLED = "disabled"
    static final String BUDGET = "budget"
    static final String ERROR = "error"
    static final String SKIPPED = "skipped"
    static final String NOT_EVALUATED = "notEvaluated"

    /*
     * Descriptor markers whose modules expose an HTTP surface. Counted separately
     * from the category heuristic because an audit asks a different question here:
     * not "what kind of app is this" but "what does it hang into the web layer".
     */
    static final List<String> REST_MARKERS = ["rest"]
    static final List<String> SERVLET_MARKERS = ["servlet", "downloadable"]

    /* ---- escaping and formatting --------------------------------------- */

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

    static String number(Number value, Locale loc) {
        if (value == null) {
            return NA
        }
        return String.format(loc == null ? Locale.ENGLISH : loc, "%,d", value.longValue())
    }

    static String csv(Object value) {
        if (value == null) {
            return "\"\""
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\""
    }

    /* ---- query parameters ---------------------------------------------- */

    static String stringParam(Object queryParams, String name, String defaultValue) {
        Object raw = queryParams == null ? null : queryParams.getFirst(name)
        if (raw == null) {
            return defaultValue
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? defaultValue : value
    }

    static boolean booleanParam(Object queryParams, String name, boolean defaultValue) {
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

    static long longParam(Object queryParams, String name, long defaultValue) {
        String value = stringParam(queryParams, name, null)
        if (value == null) {
            return defaultValue
        }
        try {
            long parsed = Long.parseLong(value)
            return parsed < 0 ? defaultValue : parsed
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    static String link(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>(base)
        if (overrides != null) {
            merged.putAll(overrides)
        }
        StringBuilder out = new StringBuilder("?")
        boolean first = true
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (entry.value == null) {
                continue
            }
            if (!first) {
                out.append("&")
            }
            out.append(URLEncoder.encode(entry.key, "UTF-8"))
            out.append("=")
            out.append(URLEncoder.encode(entry.value.toString(), "UTF-8"))
            first = false
        }
        return out.toString()
    }

    /* ---- diagnostics ---------------------------------------------------- */

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
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "..."
        }
        sink.add(context + " -> " + detail)
    }

    /* ---- substring counting --------------------------------------------- */

    /** Non-overlapping, left to right. Reference implementation. */
    static int occurrences(String text, String search) {
        if (text == null || search == null || search.isEmpty()) {
            return 0
        }
        int count = 0
        int offset = 0
        while (true) {
            int position = text.indexOf(search, offset)
            if (position < 0) {
                break
            }
            count++
            offset = position + search.length()
        }
        return count
    }

    static boolean tokenChar(char c) {
        return Character.isLetterOrDigit(c) ||
            c == ('.' as char) || c == ('_' as char) ||
            c == ('-' as char) || c == ('$' as char)
    }

    /** True when every character of the needle is a token character. */
    static boolean tokenSafe(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false
        }
        for (int i = 0; i < needle.length(); i++) {
            if (!tokenChar(needle.charAt(i))) {
                return false
            }
        }
        return true
    }

    /**
     * Distinct maximal token runs of the text with their multiplicity.
     * A token-safe needle can never span a token boundary, so counting inside
     * the tokens is exactly equivalent to scanning the whole text.
     */
    static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> counts = new HashMap<String, Integer>()
        if (text == null || text.isEmpty()) {
            return counts
        }
        int length = text.length()
        int start = -1
        for (int i = 0; i <= length; i++) {
            boolean inToken = i < length && tokenChar(text.charAt(i))
            if (inToken) {
                if (start < 0) {
                    start = i
                }
            } else if (start >= 0) {
                if (i - start >= MIN_TOKEN) {
                    String token = text.substring(start, i)
                    Integer seen = counts.get(token)
                    counts.put(token, seen == null ? 1 : seen + 1)
                }
                start = -1
            }
        }
        return counts
    }

    /** Distinct tokens joined by a non-token character, used as a fast reject. */
    static String blob(Map<String, Integer> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return ""
        }
        return "\n" + String.join("\n", tokens.keySet()) + "\n"
    }

    /**
     * Cheap reject. False means the needle provably does not occur anywhere in
     * the text the blob was built from. True means it might, so the exact count
     * still has to run. A needle that cannot use the token index always says
     * true, so correctness never depends on this.
     */
    static boolean mayOccur(String blob, String needle) {
        if (needle == null || needle.isEmpty()) {
            return false
        }
        if (needle.length() < MIN_TOKEN || !tokenSafe(needle)) {
            return true
        }
        return blob != null && blob.indexOf(needle) >= 0
    }

    /** Union of the distinct tokens of many token maps. */
    static Map<String, Integer> mergeTokens(List<Map<String, Integer>> maps) {
        Map<String, Integer> merged = new HashMap<String, Integer>()
        for (Map<String, Integer> source : maps) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                Integer seen = merged.get(entry.key)
                merged.put(entry.key, seen == null ? entry.value : seen + entry.value)
            }
        }
        return merged
    }

    /**
     * Exactly equal to occurrences(text, needle), but pays the cost of the
     * distinct token set per needle instead of the cost of the full text.
     */
    static int countIn(Map<String, Integer> tokens, String blob, String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0
        }
        if (needle.length() < MIN_TOKEN || !tokenSafe(needle)) {
            return occurrences(text, needle)
        }
        if (blob == null || blob.indexOf(needle) < 0) {
            return 0
        }
        int total = 0
        for (Map.Entry<String, Integer> entry : tokens.entrySet()) {
            String token = entry.key
            if (token.length() >= needle.length() && token.contains(needle)) {
                total += occurrences(token, needle) * entry.value
            }
        }
        return total
    }

    /* ---- module categorisation ------------------------------------------ */

    /*
     * Ordered rules, first match wins. The order is part of the contract and is
     * printed in the report footer. UI deliberately precedes REST so that
     * WebResourceModuleDescriptor - the most common module type in any plugin -
     * is not swallowed by the "resource" rule.
     */
    static final List<List<Object>> CATEGORY_RULES = [
        ["Custom Fields", ["customfield", "custom_field", "custom field", "searcher"]],
        ["Workflow", ["workflow", "validator", "condition", "postfunction", "post-function"]],
        ["JQL / Search", ["jql", "clause", "operand"]],
        ["UI", ["webitem", "websection", "webpanel", "webfragment", "webresource",
                "tabpanel", "issuetab", "issuepanel", "keyboardshortcut", "webdriver"]],
        ["REST / API", ["rest", "resource"]],
        ["HTTP / Servlet", ["servlet", "filter"]],
        ["Events / Listeners", ["listener", "event", "webhook"]],
        ["Jobs / Services", ["job", "scheduler", "service"]],
        ["Reports / Dashboards", ["gadget", "dashboard", "report"]],
        ["Permissions / Security", ["permission", "security"]],
        ["Project", ["project"]],
        ["Issue", ["issue"]]
    ]

    static String extensionCategory(String descriptorName) {
        if (descriptorName == null) {
            return "Other"
        }
        String value = descriptorName.toLowerCase(Locale.ROOT)
        for (List<Object> rule : CATEGORY_RULES) {
            for (String marker : (List<String>) rule[1]) {
                if (value.contains(marker)) {
                    return (String) rule[0]
                }
            }
        }
        return "Other"
    }

    static String resolvePluginName(Object plugin, Object i18n) {
        String descriptorName = plugin.getName()
        String i18nKey = plugin.getI18nNameKey()
        if (i18nKey != null && !i18nKey.trim().isEmpty() && i18n != null) {
            try {
                String translated = i18n.getText(i18nKey)
                if (translated != null && !translated.trim().isEmpty() && translated != i18nKey) {
                    return translated
                }
            } catch (Exception ignored) {
                /* fall through to the descriptor name */
            }
        }
        if (descriptorName != null && !descriptorName.trim().isEmpty()) {
            return descriptorName
        }
        return plugin.getKey()
    }
}

/* =============================================================================
 * DTOs
 * ========================================================================== */

class AppModuleInfo {

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

class ScreenPlacementInfo {

    Long screenId
    String screenName
    Long tabId
    String tabName

    Map<String, Object> asMap() {
        return [
            screenId: screenId,
            screenName: screenName,
            tabId: tabId,
            tabName: tabName
        ] as LinkedHashMap
    }
}

class CustomFieldFootprint {

    String id
    Long numericId
    String name
    String typeKey

    /* null plus a state, never a silent zero */
    Long issuesWithValue
    String issuesWithValueState = Fp.DISABLED

    Integer contextCount
    Boolean allProjects
    Boolean allIssueTypes

    List<String> projectKeys = new ArrayList<String>()
    List<String> issueTypes = new ArrayList<String>()
    List<ScreenPlacementInfo> screenPlacements = new ArrayList<ScreenPlacementInfo>()
    List<String> diagnostics = new ArrayList<String>()

    boolean screensMeasured = true

    /* Projects that actually reach this field through a screen scheme */
    List<String> reachProjectKeys = new ArrayList<String>()
    String reachState = Fp.NOT_EVALUATED

    int getUniqueScreenCount() {
        Set<Long> ids = new HashSet<Long>()
        for (ScreenPlacementInfo placement : screenPlacements) {
            if (placement.screenId != null) {
                ids.add(placement.screenId)
            }
        }
        return ids.size()
    }

    Map<String, Object> asMap() {
        List<Map<String, Object>> screens = new ArrayList<Map<String, Object>>()
        for (ScreenPlacementInfo placement : screenPlacements) {
            screens.add(placement.asMap())
        }
        return [
            id: id,
            numericId: numericId,
            name: name,
            typeKey: typeKey,
            issuesWithValue: issuesWithValue,
            issuesWithValueState: issuesWithValueState,
            contextCount: contextCount,
            allProjects: allProjects,
            projectKeys: projectKeys,
            allIssueTypes: allIssueTypes,
            issueTypes: issueTypes,
            screenPlacementCount: screenPlacements.size(),
            uniqueScreenCount: getUniqueScreenCount(),
            screensMeasured: screensMeasured,
            reachProjectKeys: reachProjectKeys,
            reachState: reachState,
            screens: screens,
            diagnostics: diagnostics
        ] as LinkedHashMap
    }
}

class WorkflowSnapshot {

    String name
    Boolean active
    String xml = ""

    /* the JiraWorkflow itself, kept untyped so this class stays Jira-free */
    Object workflow
    Map<String, Integer> tokens = new HashMap<String, Integer>()
    String blob = ""
    List<String> diagnostics = new ArrayList<String>()

    boolean isScannable() {
        return xml != null && !xml.isEmpty()
    }
}

class WorkflowReference {

    String name
    Boolean active

    int keyReferences
    Integer classReferences
    int references

    String detection
    List<String> matchingModuleClasses = new ArrayList<String>()

    /* Blast radius: which projects run through this workflow, and how many issues */
    List<String> projectKeys = new ArrayList<String>()
    Long issueCount
    String reachState = Fp.NOT_EVALUATED

    Map<String, Object> asMap() {
        return [
            name: name,
            active: active,
            references: references,
            keyReferences: keyReferences,
            classReferences: classReferences,
            detection: detection,
            matchingModuleClasses: matchingModuleClasses,
            projectKeys: projectKeys,
            issueCount: issueCount,
            reachState: reachState
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

    List<AppModuleInfo> modules = new ArrayList<AppModuleInfo>()
    List<CustomFieldFootprint> customFields = new ArrayList<CustomFieldFootprint>()
    List<WorkflowReference> workflowReferences = new ArrayList<WorkflowReference>()
    List<String> diagnostics = new ArrayList<String>()

    /* aggregates, computed exactly once by finish() */
    int enabledModuleCount
    Map<String, Integer> categoryCounts = new TreeMap<String, Integer>()
    Map<String, Integer> moduleTypeCounts = new LinkedHashMap<String, Integer>()
    long issueFieldAssociations
    boolean issueFieldAssociationsPartial
    int screenPlacements
    int uniqueScreens
    int workflowCount
    int activeWorkflowCount
    int workflowReferenceCount
    int footprintSignals
    boolean detected
    int diagnosticCount

    /* HTTP surface, counted from the descriptors */
    int restModules
    int servletModules

    /* Blast radius across both paths, deduplicated by project */
    List<String> impactedProjectKeys = new ArrayList<String>()
    Long impactedIssues
    String impactState = Fp.NOT_EVALUATED
    boolean impactPartial

    void finish() {
        finish(null)
    }

    void finish(Map<String, Long> issuesByProject) {
        enabledModuleCount = 0
        restModules = 0
        servletModules = 0
        Map<String, Integer> byType = new HashMap<String, Integer>()
        for (AppModuleInfo module : modules) {
            if (module.enabled != Boolean.TRUE) {
                continue
            }
            enabledModuleCount++
            String marker = module.descriptorName == null ?
                "" : module.descriptorName.toLowerCase(Locale.ROOT)
            for (String rest : Fp.REST_MARKERS) {
                if (marker.contains(rest)) {
                    restModules++
                    break
                }
            }
            for (String servlet : Fp.SERVLET_MARKERS) {
                if (marker.contains(servlet)) {
                    servletModules++
                    break
                }
            }
            Integer current = categoryCounts.get(module.category)
            categoryCounts.put(module.category, current == null ? 1 : current + 1)
            Integer typeCount = byType.get(module.descriptorName)
            byType.put(module.descriptorName, typeCount == null ? 1 : typeCount + 1)
        }

        List<Map.Entry<String, Integer>> entries =
            new ArrayList<Map.Entry<String, Integer>>(byType.entrySet())
        entries.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b ->
            int byCount = Integer.compare(b.value, a.value)
            if (byCount != 0) {
                return byCount
            }
            String left = a.key == null ? "" : a.key
            String right = b.key == null ? "" : b.key
            return left.compareToIgnoreCase(right)
        }
        moduleTypeCounts = new LinkedHashMap<String, Integer>()
        for (Map.Entry<String, Integer> entry : entries) {
            moduleTypeCounts.put(entry.key, entry.value)
        }

        issueFieldAssociations = 0L
        issueFieldAssociationsPartial = false
        screenPlacements = 0
        Set<Long> screenIds = new HashSet<Long>()
        diagnosticCount = diagnostics.size()
        for (CustomFieldFootprint field : customFields) {
            if (field.issuesWithValueState == Fp.MEASURED && field.issuesWithValue != null) {
                issueFieldAssociations += field.issuesWithValue.longValue()
            } else {
                issueFieldAssociationsPartial = true
            }
            screenPlacements += field.screenPlacements.size()
            for (ScreenPlacementInfo placement : field.screenPlacements) {
                if (placement.screenId != null) {
                    screenIds.add(placement.screenId)
                }
            }
            diagnosticCount += field.diagnostics.size()
        }
        uniqueScreens = screenIds.size()

        workflowCount = workflowReferences.size()
        activeWorkflowCount = 0
        workflowReferenceCount = 0
        for (WorkflowReference reference : workflowReferences) {
            if (reference.active == Boolean.TRUE) {
                activeWorkflowCount++
            }
            workflowReferenceCount += reference.references
        }

        detected = !customFields.isEmpty() || !workflowReferences.isEmpty()
        footprintSignals = customFields.size() + workflowReferences.size() + screenPlacements

        /*
         * Blast radius. Both paths - workflows and screens - end at projects, so the
         * union is taken before any issue is counted. Counting per workflow and per
         * field and adding up would count the same project several times.
         */
        Set<String> impacted = new TreeSet<String>()
        boolean anyMeasured = false
        impactPartial = false

        for (WorkflowReference reference : workflowReferences) {
            if (reference.reachState == Fp.MEASURED) {
                anyMeasured = true
                impacted.addAll(reference.projectKeys)
            } else if (reference.reachState != Fp.NOT_EVALUATED) {
                impactPartial = true
            }
        }
        for (CustomFieldFootprint field : customFields) {
            if (field.reachState == Fp.MEASURED) {
                anyMeasured = true
                impacted.addAll(field.reachProjectKeys)
            } else if (field.reachState != Fp.NOT_EVALUATED) {
                impactPartial = true
            }
        }

        impactedProjectKeys = new ArrayList<String>(impacted)
        impactedIssues = null

        if (!anyMeasured && !impactPartial) {
            impactState = Fp.NOT_EVALUATED
        } else {
            impactState = Fp.MEASURED
            if (issuesByProject != null) {
                long total = 0L
                for (String key : impacted) {
                    Long count = issuesByProject.get(key)
                    if (count == null) {
                        impactPartial = true
                    } else {
                        total += count.longValue()
                    }
                }
                impactedIssues = Long.valueOf(total)
            }
        }
    }

    Map<String, Object> asMap(boolean includeModules) {
        List<Map<String, Object>> fieldMaps = new ArrayList<Map<String, Object>>()
        for (CustomFieldFootprint field : customFields) {
            fieldMaps.add(field.asMap())
        }
        List<Map<String, Object>> workflowMaps = new ArrayList<Map<String, Object>>()
        for (WorkflowReference reference : workflowReferences) {
            workflowMaps.add(reference.asMap())
        }

        Map<String, Object> result = [
            displayName: displayName,
            descriptorName: descriptorName,
            i18nNameKey: i18nNameKey,
            pluginKey: pluginKey,
            version: version,
            vendor: vendor,
            vendorUrl: vendorUrl,
            systemProvided: systemProvided,
            enabled: enabled,
            state: state,
            capabilities: [
                moduleCount: modules.size(),
                enabledModuleCount: enabledModuleCount,
                restModules: restModules,
                servletModules: servletModules,
                categories: categoryCounts,
                moduleTypes: moduleTypeCounts
            ] as LinkedHashMap,
            impact: [
                state: impactState,
                partial: impactPartial,
                projectCount: impactedProjectKeys.size(),
                projectKeys: impactedProjectKeys,
                issues: impactedIssues
            ] as LinkedHashMap,
            footprint: [
                detected: detected,
                customFieldCount: customFields.size(),
                issueFieldAssociations: issueFieldAssociations,
                issueFieldAssociationsPartial: issueFieldAssociationsPartial,
                screenPlacements: screenPlacements,
                uniqueScreens: uniqueScreens,
                workflowCount: workflowCount,
                activeWorkflowCount: activeWorkflowCount,
                workflowReferences: workflowReferenceCount,
                customFields: fieldMaps,
                workflows: workflowMaps
            ] as LinkedHashMap,
            diagnostics: diagnostics
        ] as LinkedHashMap

        if (includeModules) {
            List<Map<String, Object>> moduleMaps = new ArrayList<Map<String, Object>>()
            for (AppModuleInfo module : modules) {
                moduleMaps.add(module.asMap())
            }
            result.put("modules", moduleMaps)
        }
        return result
    }
}

/* =============================================================================
 * REST Endpoint
 * ========================================================================== */

appFootprint(
    httpMethod: "GET",
    groups: ["jira-administrators"]
) { queryParams ->

    long started = System.currentTimeMillis()

    /* ---- JAX-RS Response, resolved at runtime (javax / jakarta neutral) --- */

    Class responseClass = null
    try {
        responseClass = Class.forName("jakarta.ws.rs.core.Response")
    } catch (ClassNotFoundException ignored) {
        responseClass = Class.forName("javax.ws.rs.core.Response")
    }

    /* ---- Parameters ------------------------------------------------------ */

    String format = Fp.stringParam(queryParams, "format", "html").toLowerCase(Locale.ROOT)
    boolean includeSystem = Fp.booleanParam(queryParams, "includeSystem", false)
    boolean includeDisabled = Fp.booleanParam(queryParams, "includeDisabled", true)
    boolean includeDrafts = Fp.booleanParam(queryParams, "includeDrafts", false)
    boolean includeModules = Fp.booleanParam(queryParams, "includeModules", false)
    boolean issueCounts = Fp.booleanParam(queryParams, "issueCounts", true)
    boolean includeReach = Fp.booleanParam(queryParams, "includeReach", true)
    long issueBudgetMs = Fp.longParam(queryParams, "issueBudgetMs", 120000L)
    String numbers = Fp.stringParam(queryParams, "numbers", "de").toLowerCase(Locale.ROOT)

    Locale numberLocale = numbers == "en" ? Locale.ENGLISH : Locale.GERMANY
    long issueDeadline = issueBudgetMs > 0 ? started + issueBudgetMs : Long.MAX_VALUE

    Map<String, Object> activeParams = [
        format: format == "html" ? null : format,
        includeSystem: includeSystem ? "true" : null,
        includeDisabled: includeDisabled ? null : "false",
        includeDrafts: includeDrafts ? "true" : null,
        includeModules: includeModules ? "true" : null,
        includeReach: includeReach ? null : "false",
        issueCounts: issueCounts ? null : "false",
        issueBudgetMs: issueBudgetMs == 120000L ? null : String.valueOf(issueBudgetMs),
        numbers: numbers == "de" ? null : numbers
    ] as LinkedHashMap

    /* ---- Components ------------------------------------------------------ */

    PluginAccessor pluginAccessor = ComponentAccessor.getPluginAccessor()
    PluginMetadataManager pluginMetadataManager = ComponentAccessor.getComponent(PluginMetadataManager)
    CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
    FieldScreenManager fieldScreenManager = ComponentAccessor.getComponent(FieldScreenManager)
    FieldScreenSchemeManager fieldScreenSchemeManager = ComponentAccessor.getComponent(FieldScreenSchemeManager)
    IssueTypeScreenSchemeManager issueTypeScreenSchemeManager = ComponentAccessor.getComponent(IssueTypeScreenSchemeManager)
    IssueManager issueManager = ComponentAccessor.getIssueManager()
    WorkflowManager workflowManager = ComponentAccessor.getComponent(WorkflowManager)
    WorkflowSchemeManager workflowSchemeManager = ComponentAccessor.getComponent(WorkflowSchemeManager)
    JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext()
    I18nHelper i18n = authenticationContext.getI18nHelper()

    List<String> globalDiagnostics = new ArrayList<String>()

    /* ---- Instance identity ----------------------------------------------- */

    String instanceTitle = null
    String instanceBaseUrl = null
    String jiraVersion = null
    String jiraBuild = null

    try {
        ApplicationProperties applicationProperties = ComponentAccessor.getApplicationProperties()
        instanceBaseUrl = applicationProperties.getJiraBaseUrl()
        instanceTitle = applicationProperties.getString("jira.title")
    } catch (Exception error) {
        Fp.note(globalDiagnostics, "instance properties", error)
    }

    try {
        BuildUtilsInfo buildUtilsInfo = ComponentAccessor.getComponent(BuildUtilsInfo)
        jiraVersion = buildUtilsInfo.getVersion()
        jiraBuild = buildUtilsInfo.getCurrentBuildNumber()
    } catch (Exception error) {
        Fp.note(globalDiagnostics, "build information", error)
    }

    /* ---- Custom fields: resolve every type key exactly once -------------- */

    List<CustomField> allCustomFields = new ArrayList<CustomField>()
    try {
        allCustomFields.addAll(customFieldManager.getCustomFieldObjects())
    } catch (Exception error) {
        Fp.note(globalDiagnostics, "custom field inventory", error)
    }

    Map<String, List<Integer>> fieldsByTypeKey = new HashMap<String, List<Integer>>()
    int unresolvedTypeFields = 0

    for (int index = 0; index < allCustomFields.size(); index++) {
        String typeKey = null
        try {
            def customFieldType = allCustomFields.get(index).getCustomFieldType()
            typeKey = customFieldType == null ? null : customFieldType.getKey()
        } catch (Exception ignored) {
            typeKey = null
        }
        if (typeKey == null) {
            unresolvedTypeFields++
            continue
        }
        List<Integer> bucket = fieldsByTypeKey.get(typeKey)
        if (bucket == null) {
            bucket = new ArrayList<Integer>()
            fieldsByTypeKey.put(typeKey, bucket)
        }
        bucket.add(Integer.valueOf(index))
    }

    List<String> knownTypeKeys = new ArrayList<String>(fieldsByTypeKey.keySet())

    /* ---- Workflow snapshots ---------------------------------------------- */

    Collection<JiraWorkflow> workflows = new ArrayList<JiraWorkflow>()
    try {
        workflows = includeDrafts ?
            workflowManager.getWorkflowsIncludingDrafts() :
            workflowManager.getWorkflows()
    } catch (Exception error) {
        Fp.note(globalDiagnostics, "workflow inventory", error)
    }

    List<WorkflowSnapshot> workflowSnapshots = new ArrayList<WorkflowSnapshot>()
    long workflowXmlBytes = 0L
    long workflowDistinctTokens = 0L

    for (JiraWorkflow workflow : workflows) {
        WorkflowSnapshot snapshot = new WorkflowSnapshot()
        snapshot.name = workflow.getName()
        snapshot.workflow = workflow

        try {
            snapshot.active = Boolean.valueOf(workflowManager.isActive(workflow))
        } catch (Exception error) {
            snapshot.active = null
            Fp.note(snapshot.diagnostics, "active state", error)
        }

        try {
            snapshot.xml = workflow.getDescriptor().asXML()
        } catch (Exception error) {
            snapshot.xml = ""
            Fp.note(snapshot.diagnostics, "descriptor xml", error)
        }

        if (snapshot.isScannable()) {
            snapshot.tokens = Fp.tokenize(snapshot.xml)
            snapshot.blob = Fp.blob(snapshot.tokens)
            workflowXmlBytes += snapshot.xml.length()
            workflowDistinctTokens += snapshot.tokens.size()
        }
        if (!snapshot.diagnostics.isEmpty()) {
            for (String entry : snapshot.diagnostics) {
                globalDiagnostics.add("workflow '" + snapshot.name + "' " + entry)
            }
        }
        workflowSnapshots.add(snapshot)
    }

    List<WorkflowSnapshot> scannableWorkflows = new ArrayList<WorkflowSnapshot>()
    List<Map<String, Integer>> allTokenMaps = new ArrayList<Map<String, Integer>>()
    for (WorkflowSnapshot snapshot : workflowSnapshots) {
        if (snapshot.isScannable()) {
            scannableWorkflows.add(snapshot)
            allTokenMaps.add(snapshot.tokens)
        }
    }

    /*
     * Three-stage lookup. Stage one is the union of every distinct token of
     * every workflow: a needle that is not in there cannot occur in any
     * descriptor, which retires the overwhelming majority of candidates for the
     * price of one scan over a small string. Stage two is the same reject per
     * workflow, stage three is the exact count inside the matching tokens.
     */
    Map<String, Integer> globalTokens = Fp.mergeTokens(allTokenMaps)
    String globalBlob = Fp.blob(globalTokens)

    /* -----------------------------------------------------------------------
     * Reach: from a reference to the projects that actually run through it
     *
     * A workflow that references an app says little on its own. What matters is
     * how many projects use that workflow, and how many issues live in them.
     * The same holds for a custom field: a screen nobody's project exposes is
     * not a footprint. Both chains end at projects, so the union is taken there.
     * --------------------------------------------------------------------- */

    Map<Long, Set<String>> projectsByScreen = new HashMap<Long, Set<String>>()
    Map<String, Long> issuesByProject = new HashMap<String, Long>()
    Map<String, Map<String, Object>> workflowReach = new HashMap<String, Map<String, Object>>()
    boolean screenReachAvailable = includeReach
    boolean screenReachTruncated = false
    int screenSchemesWalked = 0
    int screenSchemesTotal = 0

    if (includeReach) {
        try {
            Collection<FieldScreenScheme> allScreenSchemes = fieldScreenSchemeManager.getFieldScreenSchemes()
            screenSchemesTotal = allScreenSchemes.size()

            for (FieldScreenScheme screenScheme : allScreenSchemes) {

                /*
                 * This walk is unbounded by nature - every screen scheme, every item.
                 * On a large instance that is the same timeout risk the issue counting
                 * has, so it answers to the same budget. A truncated index degrades to
                 * "not measured" for every field, it never silently reports fewer
                 * projects than exist.
                 */
                if (System.currentTimeMillis() > issueDeadline) {
                    screenReachTruncated = true
                    break
                }
                screenSchemesWalked++

                Set<String> projects = new TreeSet<String>()
                /* getIssueTypeScreenSchemes(FieldScreenScheme) is declared raw, hence the cast */
                for (Object rawIssueTypeScheme : issueTypeScreenSchemeManager.getIssueTypeScreenSchemes(screenScheme)) {
                    IssueTypeScreenScheme issueTypeScheme = (IssueTypeScreenScheme) rawIssueTypeScheme
                    for (GenericValue projectValue : issueTypeScreenSchemeManager.getProjects(issueTypeScheme)) {
                        String projectKey = projectValue.getString("key")
                        if (projectKey != null) {
                            projects.add(projectKey)
                        }
                    }
                }
                if (projects.isEmpty()) {
                    continue
                }

                for (FieldScreenSchemeItem item : fieldScreenSchemeManager.getFieldScreenSchemeItems(screenScheme)) {
                    Long screenId = item.getFieldScreenId()
                    if (screenId == null) {
                        FieldScreen itemScreen = item.getFieldScreen()
                        screenId = itemScreen == null ? null : itemScreen.getId()
                    }
                    if (screenId == null) {
                        continue
                    }
                    Set<String> bucket = projectsByScreen.get(screenId)
                    if (bucket == null) {
                        bucket = new TreeSet<String>()
                        projectsByScreen.put(screenId, bucket)
                    }
                    bucket.addAll(projects)
                }
            }
        } catch (Exception error) {
            screenReachAvailable = false
            Fp.note(globalDiagnostics, "screen scheme reach", error)
        }

        if (screenReachTruncated) {
            globalDiagnostics.add("screen scheme reach -> time budget exhausted after " +
                screenSchemesWalked + " of " + screenSchemesTotal +
                " screen schemes, project reach via screens is reported as not measured")
        }
    }

    /* Key to id, resolved once: the screen path knows only keys. */
    Map<String, Long> projectIdByKey = new HashMap<String, Long>()
    if (includeReach) {
        try {
            for (Project project : ComponentAccessor.getProjectManager().getProjectObjects()) {
                if (project != null && project.getKey() != null) {
                    projectIdByKey.put(project.getKey(), project.getId())
                }
            }
        } catch (Exception error) {
            Fp.note(globalDiagnostics, "project inventory", error)
        }
    }

    /* One count per project, shared across every app that reaches it. */
    def issuesForProject = { String projectKey, Long projectId ->
        if (projectKey == null) {
            return null
        }
        if (projectId == null) {
            projectId = projectIdByKey.get(projectKey)
        }
        if (projectId == null) {
            return null
        }
        if (issuesByProject.containsKey(projectKey)) {
            return issuesByProject.get(projectKey)
        }
        if (!issueCounts || System.currentTimeMillis() > issueDeadline) {
            return null
        }
        Long counted = null
        try {
            counted = Long.valueOf(issueManager.getIssueCountForProject(projectId))
        } catch (Exception error) {
            Fp.note(globalDiagnostics, "issue count for project " + projectKey, error)
        }
        issuesByProject.put(projectKey, counted)
        return counted
    }

    def reachForWorkflow = { WorkflowSnapshot snapshot ->
        Map<String, Object> cached = workflowReach.get(snapshot.name)
        if (cached != null) {
            return cached
        }
        Map<String, Object> result = [
            state: Fp.NOT_EVALUATED,
            projects: new ArrayList<String>(),
            issues: null
        ] as LinkedHashMap

        if (includeReach && snapshot.workflow != null) {
            try {
                Set<String> projects = new TreeSet<String>()
                Map<String, Long> ids = new LinkedHashMap<String, Long>()

                JiraWorkflow referenced = (JiraWorkflow) snapshot.workflow
                for (GenericValue schemeValue : workflowSchemeManager.getSchemesForWorkflow(referenced)) {
                    Scheme scheme = workflowSchemeManager.getSchemeObject(schemeValue.getLong("id"))
                    if (scheme == null) {
                        continue
                    }
                    for (Project project : workflowSchemeManager.getProjects(scheme)) {
                        if (project == null || project.getKey() == null) {
                            continue
                        }
                        projects.add(project.getKey())
                        ids.put(project.getKey(), project.getId())
                    }
                }

                long issues = 0L
                boolean anyCounted = false
                for (String projectKey : projects) {
                    Long count = issuesForProject(projectKey, ids.get(projectKey))
                    if (count != null) {
                        issues += count.longValue()
                        anyCounted = true
                    }
                }

                result.put("state", Fp.MEASURED)
                result.put("projects", new ArrayList<String>(projects))
                result.put("issues", anyCounted ? Long.valueOf(issues) : null)

            } catch (Exception error) {
                result.put("state", Fp.ERROR)
                Fp.note(globalDiagnostics, "workflow reach " + snapshot.name, error)
            }
        }

        workflowReach.put(snapshot.name, result)
        return result
    }

    /* ---- Plugins ---------------------------------------------------------- */

    Collection<Plugin> candidatePlugins
    try {
        candidatePlugins = includeDisabled ?
            pluginAccessor.getPlugins() :
            pluginAccessor.getEnabledPlugins()
    } catch (Exception error) {
        Fp.note(globalDiagnostics, "plugin inventory", error)
        candidatePlugins = new ArrayList<Plugin>()
    }

    List<AppFootprint> apps = new ArrayList<AppFootprint>()
    int issueCountsSkippedByBudget = 0

    for (Plugin plugin : candidatePlugins) {

        AppFootprint app = new AppFootprint()
        app.pluginKey = plugin.getKey()
        app.descriptorName = plugin.getName()
        app.i18nNameKey = plugin.getI18nNameKey()
        app.displayName = Fp.resolvePluginName(plugin, i18n)

        try {
            app.systemProvided = pluginMetadataManager.isSystemProvided(plugin)
        } catch (Exception error) {
            app.systemProvided = false
            Fp.note(app.diagnostics, "system-provided flag", error)
        }

        if (app.systemProvided && !includeSystem) {
            continue
        }

        try {
            def pluginState = plugin.getPluginState()
            app.state = pluginState == null ? null : pluginState.toString()
        } catch (Exception error) {
            app.state = null
            Fp.note(app.diagnostics, "plugin state", error)
        }

        try {
            app.enabled = pluginAccessor.isPluginEnabled(app.pluginKey)
        } catch (Exception error) {
            app.enabled = app.state == "ENABLED"
            Fp.note(app.diagnostics, "enabled flag", error)
        }

        try {
            PluginInformation pluginInformation = plugin.getPluginInformation()
            if (pluginInformation != null) {
                app.version = pluginInformation.getVersion()
                app.vendor = pluginInformation.getVendorName()
                app.vendorUrl = pluginInformation.getVendorUrl()
            }
        } catch (Exception error) {
            Fp.note(app.diagnostics, "plugin information", error)
        }

        /* ---- Modules ------------------------------------------------------ */

        Set<String> moduleCompleteKeys = new HashSet<String>()
        Set<String> moduleClasses = new HashSet<String>()

        Collection<ModuleDescriptor<?>> descriptors = new ArrayList<ModuleDescriptor<?>>()
        try {
            descriptors = plugin.getModuleDescriptors()
        } catch (Throwable error) {
            Fp.note(app.diagnostics, "module descriptors", error)
        }

        for (ModuleDescriptor<?> descriptor : descriptors) {
            AppModuleInfo module = new AppModuleInfo()
            module.key = descriptor.getKey()
            module.completeKey = descriptor.getCompleteKey()
            module.name = descriptor.getName()
            module.descriptorName = descriptor.getClass().getSimpleName()
            module.descriptorClass = descriptor.getClass().getName()
            module.category = Fp.extensionCategory(module.descriptorName)

            try {
                module.enabled = Boolean.valueOf(pluginAccessor.isPluginModuleEnabled(module.completeKey))
            } catch (Exception error) {
                module.enabled = null
                Fp.note(app.diagnostics, "module enabled flag " + module.completeKey, error)
            }

            try {
                Class<?> moduleClazz = descriptor.getModuleClass()
                if (moduleClazz != null) {
                    module.moduleClass = moduleClazz.getName()
                    if (!module.moduleClass.startsWith("java.") && !module.moduleClass.startsWith("groovy.")) {
                        moduleClasses.add(module.moduleClass)
                    }
                }
            } catch (Throwable ignored) {
                module.moduleClass = null
            }

            if (module.completeKey != null) {
                moduleCompleteKeys.add(module.completeKey)
            }
            app.modules.add(module)
        }

        /* ---- App-owned custom fields -------------------------------------- */

        String keyPrefix = app.pluginKey + ":"
        Set<Integer> matchedIndexes = new TreeSet<Integer>()

        for (String typeKey : knownTypeKeys) {
            if (typeKey.startsWith(keyPrefix) || moduleCompleteKeys.contains(typeKey)) {
                matchedIndexes.addAll(fieldsByTypeKey.get(typeKey))
            }
        }

        for (Integer fieldIndex : matchedIndexes) {
            CustomField customField = allCustomFields.get(fieldIndex.intValue())
            CustomFieldFootprint field = new CustomFieldFootprint()

            field.id = customField.getId()
            try {
                field.numericId = customField.getIdAsLong()
            } catch (Exception error) {
                Fp.note(field.diagnostics, "numeric id", error)
            }
            field.name = customField.getName()
            try {
                field.typeKey = customField.getCustomFieldType().getKey()
            } catch (Exception error) {
                Fp.note(field.diagnostics, "type key", error)
            }

            /* Issues carrying a non-empty value for this field */
            if (!issueCounts) {
                field.issuesWithValueState = Fp.DISABLED
            } else if (System.currentTimeMillis() > issueDeadline) {
                field.issuesWithValueState = Fp.BUDGET
                issueCountsSkippedByBudget++
            } else {
                try {
                    field.issuesWithValue = customField.getIssuesWithValue()
                    field.issuesWithValueState = field.issuesWithValue == null ? Fp.ERROR : Fp.MEASURED
                    if (field.issuesWithValue == null) {
                        Fp.note(field.diagnostics, "issue count", new IllegalStateException("API returned null"))
                    }
                } catch (Exception error) {
                    field.issuesWithValueState = Fp.ERROR
                    Fp.note(field.diagnostics, "issue count", error)
                }
            }

            try {
                field.contextCount = Integer.valueOf(customField.getConfigurationSchemes().size())
            } catch (Exception error) {
                field.contextCount = null
                Fp.note(field.diagnostics, "context count", error)
            }

            try {
                field.allProjects = Boolean.valueOf(customField.isAllProjects())
            } catch (Exception error) {
                field.allProjects = null
                Fp.note(field.diagnostics, "project scope flag", error)
            }

            if (field.allProjects == Boolean.FALSE) {
                try {
                    for (Project project : customField.getAssociatedProjectObjects()) {
                        if (project != null) {
                            field.projectKeys.add(project.getKey())
                        }
                    }
                    Collections.sort(field.projectKeys)
                } catch (Exception error) {
                    Fp.note(field.diagnostics, "associated projects", error)
                }
            }

            try {
                field.allIssueTypes = Boolean.valueOf(customField.isAllIssueTypes())
            } catch (Exception error) {
                field.allIssueTypes = null
                Fp.note(field.diagnostics, "issue type scope flag", error)
            }

            if (field.allIssueTypes == Boolean.FALSE) {
                try {
                    for (IssueType issueType : customField.getAssociatedIssueTypes()) {
                        if (issueType != null) {
                            field.issueTypes.add(issueType.getName())
                        }
                    }
                    Collections.sort(field.issueTypes)
                } catch (Exception error) {
                    Fp.note(field.diagnostics, "associated issue types", error)
                }
            }

            try {
                Collection<FieldScreenTab> tabs = fieldScreenManager.getFieldScreenTabs(customField.getId())
                for (FieldScreenTab tab : tabs) {
                    ScreenPlacementInfo placement = new ScreenPlacementInfo()
                    placement.tabId = tab.getId()
                    placement.tabName = tab.getName()
                    FieldScreen screen = tab.getFieldScreen()
                    if (screen != null) {
                        placement.screenId = screen.getId()
                        placement.screenName = screen.getName()
                    }
                    field.screenPlacements.add(placement)
                }
            } catch (Exception error) {
                field.screensMeasured = false
                Fp.note(field.diagnostics, "screen placements", error)
            }

            if (includeReach) {
                if (screenReachTruncated) {
                    field.reachState = Fp.BUDGET
                } else if (!screenReachAvailable || !field.screensMeasured) {
                    field.reachState = Fp.ERROR
                } else {
                    Set<String> reachProjects = new TreeSet<String>()
                    for (ScreenPlacementInfo placement : field.screenPlacements) {
                        if (placement.screenId == null) {
                            continue
                        }
                        Set<String> bucket = projectsByScreen.get(placement.screenId)
                        if (bucket != null) {
                            reachProjects.addAll(bucket)
                        }
                    }
                    field.reachProjectKeys.addAll(reachProjects)
                    field.reachState = Fp.MEASURED
                    for (String reachKey : reachProjects) {
                        issuesForProject(reachKey, null)
                    }
                }
            }

            app.customFields.add(field)
        }

        /* ---- Workflow references ------------------------------------------ */

        boolean keyPossible = Fp.mayOccur(globalBlob, app.pluginKey)
        List<String> possibleClasses = new ArrayList<String>()
        for (String moduleClass : moduleClasses) {
            if (Fp.mayOccur(globalBlob, moduleClass)) {
                possibleClasses.add(moduleClass)
            }
        }

        Collection<WorkflowSnapshot> workflowsToScan =
            (keyPossible || !possibleClasses.isEmpty()) ? scannableWorkflows : Collections.emptyList()

        for (WorkflowSnapshot snapshot : workflowsToScan) {

            int keyReferences = keyPossible ?
                Fp.countIn(snapshot.tokens, snapshot.blob, snapshot.xml, app.pluginKey) : 0
            Integer classReferences = null
            List<String> matchingClasses = new ArrayList<String>()

            if (keyReferences == 0) {
                int classTotal = 0
                for (String moduleClass : possibleClasses) {
                    int matches = Fp.countIn(snapshot.tokens, snapshot.blob, snapshot.xml, moduleClass)
                    if (matches > 0) {
                        matchingClasses.add(moduleClass)
                        classTotal += matches
                    }
                }
                classReferences = Integer.valueOf(classTotal)
            }

            int references = keyReferences > 0 ? keyReferences : (classReferences == null ? 0 : classReferences.intValue())
            if (references == 0) {
                continue
            }

            Collections.sort(matchingClasses)

            WorkflowReference reference = new WorkflowReference()
            reference.name = snapshot.name
            reference.active = snapshot.active
            reference.keyReferences = keyReferences
            reference.classReferences = classReferences
            reference.references = references
            reference.detection = keyReferences > 0 ? "Plugin key" : "Module class"
            reference.matchingModuleClasses.addAll(matchingClasses)

            Map<String, Object> reach = reachForWorkflow(snapshot)
            reference.reachState = (String) reach.get("state")
            reference.projectKeys.addAll((List<String>) reach.get("projects"))
            reference.issueCount = (Long) reach.get("issues")

            app.workflowReferences.add(reference)
        }

        app.finish(issuesByProject)
        apps.add(app)
    }

    /* ---- Sort and summarise ------------------------------------------------ */

    apps.sort { AppFootprint a, AppFootprint b ->
        int bySignals = Integer.compare(b.footprintSignals, a.footprintSignals)
        if (bySignals != 0) {
            return bySignals
        }
        String left = a.displayName == null ? "" : a.displayName
        String right = b.displayName == null ? "" : b.displayName
        int byName = left.compareToIgnoreCase(right)
        if (byName != 0) {
            return byName
        }
        String leftKey = a.pluginKey == null ? "" : a.pluginKey
        String rightKey = b.pluginKey == null ? "" : b.pluginKey
        return leftKey.compareTo(rightKey)
    }

    int appsWithFootprint = 0
    int disabledApps = 0
    int totalCustomFields = 0
    long totalIssueFieldAssociations = 0L
    boolean issueTotalsPartial = false
    int totalScreenPlacements = 0
    int totalWorkflowReferences = 0
    int totalDiagnostics = globalDiagnostics.size()
    Set<String> allImpactedProjects = new TreeSet<String>()
    List<AppFootprint> decommissionCandidates = new ArrayList<AppFootprint>()

    for (AppFootprint app : apps) {
        if (app.detected) {
            appsWithFootprint++
        } else if (!app.systemProvided) {
            decommissionCandidates.add(app)
        }
        allImpactedProjects.addAll(app.impactedProjectKeys)
        if (!app.enabled) {
            disabledApps++
        }
        totalCustomFields += app.customFields.size()
        totalIssueFieldAssociations += app.issueFieldAssociations
        if (app.issueFieldAssociationsPartial) {
            issueTotalsPartial = true
        }
        totalScreenPlacements += app.screenPlacements
        totalWorkflowReferences += app.workflowReferenceCount
        totalDiagnostics += app.diagnosticCount
    }

    /*
     * Issues across every touched project, counted once per project. Summing the
     * per-app numbers would count a project that several apps reach many times.
     */
    long impactedIssuesTotal = 0L
    boolean impactedIssuesPartial = false
    for (String projectKey : allImpactedProjects) {
        Long count = issuesByProject.get(projectKey)
        if (count == null) {
            impactedIssuesPartial = true
        } else {
            impactedIssuesTotal += count.longValue()
        }
    }

    String generatedAt = ZonedDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))

    Map<String, Object> instanceInfo = [
        title: instanceTitle,
        baseUrl: instanceBaseUrl,
        jiraVersion: jiraVersion,
        jiraBuild: jiraBuild
    ] as LinkedHashMap

    Map<String, Object> optionsInfo = [
        includeSystem: includeSystem,
        includeDisabled: includeDisabled,
        includeDrafts: includeDrafts,
        includeModules: includeModules,
        includeReach: includeReach,
        issueCounts: issueCounts,
        issueBudgetMs: issueBudgetMs,
        numbers: numbers
    ] as LinkedHashMap

    /* =========================================================================
     * JSON
     * ======================================================================= */

    if (format == "json") {

        List<Map<String, Object>> appMaps = new ArrayList<Map<String, Object>>()
        for (AppFootprint app : apps) {
            appMaps.add(app.asMap(includeModules))
        }

        Map<String, Object> response = [
            report: [
                name: "Jira App Footprint Analysis",
                version: "3.1",
                generatedAt: generatedAt
            ] as LinkedHashMap,
            instance: instanceInfo,
            options: optionsInfo,
            summary: [
                apps: apps.size(),
                disabledApps: disabledApps,
                appsWithDetectedFootprint: appsWithFootprint,
                appCustomFields: totalCustomFields,
                issueFieldAssociations: totalIssueFieldAssociations,
                issueFieldAssociationsPartial: issueTotalsPartial,
                issueCountsSkippedByBudget: issueCountsSkippedByBudget,
                screenPlacements: totalScreenPlacements,
                workflowReferences: totalWorkflowReferences,
                impactedProjects: allImpactedProjects.size(),
                impactedProjectKeys: new ArrayList<String>(allImpactedProjects),
                impactedIssues: impactedIssuesPartial && impactedIssuesTotal == 0L ? null : impactedIssuesTotal,
                impactedIssuesPartial: impactedIssuesPartial,
                decommissionCandidates: decommissionCandidates.size(),
                workflowsScanned: scannableWorkflows.size(),
                workflowsTotal: workflowSnapshots.size(),
                customFieldsTotal: allCustomFields.size(),
                customFieldsWithUnresolvedType: unresolvedTypeFields,
                diagnostics: totalDiagnostics
            ] as LinkedHashMap,
            scan: [
                workflowXmlBytes: workflowXmlBytes,
                workflowDistinctTokens: workflowDistinctTokens,
                globalDistinctTokens: globalTokens.size(),
                screenSchemesWalked: screenSchemesWalked,
                screenSchemesTotal: screenSchemesTotal,
                screenReachTruncated: screenReachTruncated
            ] as LinkedHashMap,
            diagnostics: globalDiagnostics,
            apps: appMaps
        ] as LinkedHashMap

        response.put("executionMs", System.currentTimeMillis() - started)

        return responseClass
            .ok(JsonOutput.prettyPrint(JsonOutput.toJson(response)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    /* =========================================================================
     * CSV
     * ======================================================================= */

    if (format == "csv") {

        StringBuilder csv = new StringBuilder()
        csv.append("pluginKey,displayName,descriptorName,vendor,version,enabled,state,systemProvided,")
        csv.append("modules,enabledModules,customFields,issueFieldAssociations,issueCountsComplete,")
        csv.append("screenPlacements,uniqueScreens,workflows,activeWorkflows,workflowReferences,")
        csv.append("impactedProjects,impactedIssues,impactComplete,restModules,servletModules,")
        csv.append("detectedFootprint,diagnostics\n")

        for (AppFootprint app : apps) {
            csv.append(Fp.csv(app.pluginKey)).append(",")
            csv.append(Fp.csv(app.displayName)).append(",")
            csv.append(Fp.csv(app.descriptorName)).append(",")
            csv.append(Fp.csv(app.vendor)).append(",")
            csv.append(Fp.csv(app.version)).append(",")
            csv.append(app.enabled).append(",")
            csv.append(Fp.csv(app.state)).append(",")
            csv.append(app.systemProvided).append(",")
            csv.append(app.modules.size()).append(",")
            csv.append(app.enabledModuleCount).append(",")
            csv.append(app.customFields.size()).append(",")
            csv.append(app.issueFieldAssociations).append(",")
            csv.append(!app.issueFieldAssociationsPartial).append(",")
            csv.append(app.screenPlacements).append(",")
            csv.append(app.uniqueScreens).append(",")
            csv.append(app.workflowCount).append(",")
            csv.append(app.activeWorkflowCount).append(",")
            csv.append(app.workflowReferenceCount).append(",")
            csv.append(app.impactedProjectKeys.size()).append(",")
            csv.append(app.impactedIssues == null ? "" : app.impactedIssues).append(",")
            csv.append(app.impactState == Fp.MEASURED && !app.impactPartial).append(",")
            csv.append(app.restModules).append(",")
            csv.append(app.servletModules).append(",")
            csv.append(app.detected).append(",")
            csv.append(app.diagnosticCount).append("\n")
        }

        return responseClass
            .ok(csv.toString())
            .type("text/csv; charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=\"jira-app-footprint.csv\"")
            .build()
    }

    /* =========================================================================
     * HTML
     * ======================================================================= */

    def esc = { Object value -> Fp.html(value) }
    def num = { Number value -> Fp.number(value, numberLocale) }
    def lnk = { Map overrides -> Fp.html(Fp.link(activeParams, overrides)) }

    def issueCell = { CustomFieldFootprint field ->
        if (field.issuesWithValueState == Fp.MEASURED) {
            return Fp.html(Fp.number(field.issuesWithValue, numberLocale))
        }
        if (field.issuesWithValueState == Fp.DISABLED) {
            return "<span class=\"muted\" title=\"issueCounts=false\">off</span>"
        }
        if (field.issuesWithValueState == Fp.BUDGET) {
            return "<span class=\"warn\" title=\"Time budget exhausted before this field was counted\">n/m</span>"
        }
        return "<span class=\"bad\" title=\"Read failed, see diagnostics\">err</span>"
    }

    def impactCell = { AppFootprint entry ->
        if (entry.impactState == Fp.NOT_EVALUATED) {
            return "<span class=\"muted\" title=\"Project reach not evaluated\">n/e</span>"
        }
        String value = Fp.html(Fp.number(entry.impactedProjectKeys.size(), numberLocale))
        if (entry.impactPartial) {
            value = value + "<span class=\"warn\" title=\"At least one path could not be measured, this is a lower bound\">&#42;</span>"
        }
        return value
    }

    /* Project list for a table cell, capped so one field cannot flood the page */
    def projectsCell = { List<String> keys, String state ->
        if (state == Fp.NOT_EVALUATED) {
            return "<span class=\"muted\" title=\"Project reach not evaluated\">n/e</span>"
        }
        if (state == Fp.BUDGET) {
            return "<span class=\"warn\" title=\"Time budget exhausted before the screen scheme index was complete\">n/m</span>"
        }
        if (state != Fp.MEASURED) {
            return "<span class=\"bad\" title=\"Read failed, see diagnostics\">err</span>"
        }
        if (keys.isEmpty()) {
            return "<span class=\"muted\">none</span>"
        }
        String shown = String.join(", ", keys.size() > 12 ? keys.subList(0, 12) : keys)
        if (keys.size() > 12) {
            shown = shown + " (+" + Fp.number(keys.size() - 12, numberLocale) + ")"
        }
        return "<span title=\"" + Fp.html(String.join(", ", keys)) + "\">" + Fp.html(shown) + "</span>"
    }

    StringBuilder html = new StringBuilder(1 << 20)

    html.append("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Jira App Footprint Analysis</title>
<style>
:root {
    --page-bg: #f7f8f9;
    --surface: #ffffff;
    --surface-subtle: #f7f8f9;
    --text: #172b4d;
    --text-subtle: #626f86;
    --border: #dcdfe4;
    --border-subtle: #ebecf0;
    --blue: #0c66e4;
    --blue-soft: #e9f2ff;
    --green: #216e4e;
    --green-soft: #eefbf5;
    --green-border: #baf3db;
    --yellow: #7f5f01;
    --yellow-soft: #fff7d6;
    --yellow-border: #f5cd47;
    --red: #ae2e24;
    --red-soft: #ffeceb;
    --red-border: #ffd5d2;
    --purple: #5e4db2;
    --purple-soft: #f3f0ff;
    --purple-border: #b8acf6;
    --shadow: 0 1px 2px rgba(9, 30, 66, .08), 0 1px 3px rgba(9, 30, 66, .06);
}
* { box-sizing: border-box; }
body {
    margin: 0;
    background: var(--page-bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 14px;
    line-height: 1.45;
}
.page { max-width: 1580px; margin: 0 auto; padding: 28px 32px 40px; }
.mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 12px; }
.muted { color: var(--text-subtle); }
.good { color: var(--green); font-weight: 600; }
.warn { color: var(--yellow); font-weight: 600; }
.bad { color: var(--red); font-weight: 600; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.hidden { display: none !important; }

/* header */
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 20px; }
.page-title { margin: 0 0 4px; font-size: 22px; font-weight: 600; letter-spacing: -.01em; }
.page-subtitle { color: var(--text-subtle); font-size: 13px; }
.instance {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 10px 14px; margin: 12px 0 20px; box-shadow: var(--shadow);
    display: flex; flex-wrap: wrap; gap: 6px 28px;
}
.instance div { font-size: 13px; }
.instance strong { font-weight: 600; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.button {
    display: inline-block; padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border);
    background: var(--surface); color: var(--text); text-decoration: none; font-size: 13px; font-weight: 500;
}
.button:hover { background: var(--surface-subtle); }
.button.on { background: var(--blue-soft); border-color: var(--blue); color: var(--blue); }

/* summary */
.summary-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 12px; margin-bottom: 16px; }
.summary-card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 14px 16px; box-shadow: var(--shadow); }
.summary-value { font-size: 24px; font-weight: 600; letter-spacing: -.02em; }
.summary-label { color: var(--text-subtle); font-size: 12px; margin-top: 2px; }
.notice, .diag {
    border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; font-size: 13px;
    background: var(--blue-soft); border: 1px solid var(--purple-border);
}
.diag { background: var(--yellow-soft); border-color: var(--yellow-border); }
.diag ul { margin: 8px 0 0; padding-left: 20px; }
.diag li { margin: 2px 0; }

/* toolbar */
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.search { flex: 1; min-width: 260px; padding: 8px 12px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); }
.checkbox-label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-subtle); }

/* app cards */
.app-card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 16px; box-shadow: var(--shadow); overflow: hidden; }
.app-card.is-disabled { border-left: 4px solid var(--yellow); }
.app-header { padding: 16px 20px; border-bottom: 1px solid var(--border-subtle); }
.app-header-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; }
.app-name { font-size: 17px; font-weight: 600; }
.app-meta { color: var(--text-subtle); font-size: 12px; margin-top: 2px; }
.badges { display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.badge { display: inline-block; padding: 3px 8px; border-radius: 3px; font-size: 11px; font-weight: 700; letter-spacing: .03em; border: 1px solid transparent; }
.badge-footprint { background: var(--green-soft); color: var(--green); border-color: var(--green-border); }
.badge-capability { background: var(--surface-subtle); color: var(--text-subtle); border-color: var(--border); }
.badge-system { background: var(--purple-soft); color: var(--purple); border-color: var(--purple-border); }
.badge-disabled { background: var(--yellow-soft); color: var(--yellow); border-color: var(--yellow-border); }
.badge-diag { background: var(--red-soft); color: var(--red); border-color: var(--red-border); }

/* metrics */
.metrics { display: grid; grid-template-columns: repeat(8, 1fr); border-bottom: 1px solid var(--border-subtle); }
.metric.impact { background: var(--blue-soft); }
.metric { padding: 12px 16px; border-right: 1px solid var(--border-subtle); }
.metric:last-child { border-right: none; }
.metric-value { font-size: 19px; font-weight: 600; }
.metric-label { color: var(--text-subtle); font-size: 11px; margin-top: 2px; }

/* sections */
.section { padding: 16px 20px; border-bottom: 1px solid var(--border-subtle); }
.section:last-child { border-bottom: none; }
.section-title { font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; color: var(--text-subtle); margin-bottom: 10px; }
.category-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 12px; }
.category { background: var(--surface-subtle); border: 1px solid var(--border-subtle); border-radius: 4px; padding: 8px 10px; }
.category-name { font-size: 12px; font-weight: 600; }
.category-count { color: var(--text-subtle); font-size: 11px; }
.empty { color: var(--text-subtle); font-size: 13px; font-style: italic; padding: 6px 0; }

/* tables */
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 7px 10px; text-align: left; border-bottom: 1px solid var(--border-subtle); vertical-align: top; }
th { background: var(--surface-subtle); font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-subtle); font-weight: 700; white-space: nowrap; }
tbody tr:hover { background: var(--surface-subtle); }
details { margin-top: 10px; }
summary { cursor: pointer; font-size: 12px; font-weight: 600; color: var(--blue); padding: 4px 0; }
.table-wrap { overflow-x: auto; }
.footer { margin-top: 24px; padding: 16px 20px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; font-size: 12px; color: var(--text-subtle); }
.footer ul { margin: 8px 0; padding-left: 18px; }
.footer li { margin: 3px 0; }

@media (max-width: 1250px) {
    .summary-grid { grid-template-columns: repeat(3, 1fr); }
    .metrics { grid-template-columns: repeat(4, 1fr); }
    .category-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 800px) {
    .page { padding: 18px; }
    .page-header { flex-direction: column; }
    .toolbar { flex-direction: column; align-items: stretch; }
    .summary-grid, .metrics, .category-grid { grid-template-columns: repeat(2, 1fr); }
    .app-header-row { flex-direction: column; }
    .badges { justify-content: flex-start; }
}
@media print {
    body { background: #fff; }
    .toolbar, .actions { display: none; }
    .app-card, .summary-card, .instance, .footer { box-shadow: none; break-inside: avoid; }
    details { display: block; }
    details > summary { list-style: none; }
}
</style>
</head>
<body>
<div class="page">

<div class="page-header">
    <div>
        <h1 class="page-title">Jira App Footprint Analysis</h1>
        <div class="page-subtitle">
            Generated ${esc(generatedAt)} &nbsp;&middot;&nbsp;
            ${num(scannableWorkflows.size())} of ${num(workflowSnapshots.size())} workflows scanned &nbsp;&middot;&nbsp;
            ${num(allCustomFields.size())} custom fields in the instance
        </div>
    </div>
    <div class="actions">
        <a class="button" href="${lnk([format: 'json'])}">JSON</a>
        <a class="button" href="${lnk([format: 'csv'])}">CSV</a>
        <a class="button ${includeDrafts ? 'on' : ''}" href="${lnk([includeDrafts: includeDrafts ? null : 'true'])}">Drafts</a>
        <a class="button ${includeModules ? 'on' : ''}" href="${lnk([includeModules: includeModules ? null : 'true'])}">Modules</a>
        <a class="button ${includeSystem ? 'on' : ''}" href="${lnk([includeSystem: includeSystem ? null : 'true'])}">System apps</a>
        <a class="button ${includeDisabled ? 'on' : ''}" href="${lnk([includeDisabled: includeDisabled ? 'false' : null])}">Disabled apps</a>
        <a class="button ${issueCounts ? 'on' : ''}" href="${lnk([issueCounts: issueCounts ? 'false' : null])}">Issue counts</a>
        <a class="button ${includeReach ? 'on' : ''}" href="${lnk([includeReach: includeReach ? 'false' : null])}">Project reach</a>
    </div>
</div>

<div class="instance">
    <div><strong>Instance:</strong> ${esc(instanceTitle ?: Fp.NA)}</div>
    <div><strong>Base URL:</strong> <span class="mono">${esc(instanceBaseUrl ?: Fp.NA)}</span></div>
    <div><strong>Jira:</strong> ${esc(jiraVersion ?: Fp.NA)} (build ${esc(jiraBuild ?: Fp.NA)})</div>
    <div><strong>Options:</strong> <span class="mono">includeSystem=${includeSystem} includeDisabled=${includeDisabled} includeDrafts=${includeDrafts} includeModules=${includeModules} issueCounts=${issueCounts} issueBudgetMs=${issueBudgetMs}</span></div>
</div>

<div class="summary-grid">
    <div class="summary-card">
        <div class="summary-value">${num(apps.size())}</div>
        <div class="summary-label">Apps In Report${disabledApps > 0 ? " (" + num(disabledApps) + " disabled)" : ""}</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${num(appsWithFootprint)}</div>
        <div class="summary-label">Apps With Detectable Footprint</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${num(totalCustomFields)}</div>
        <div class="summary-label">App Custom Fields</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${num(totalIssueFieldAssociations)}${issueTotalsPartial ? '<span class="warn" title="Not every field was counted, the total is a lower bound">&#42;</span>' : ''}</div>
        <div class="summary-label">Issue-Field Associations</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${num(totalScreenPlacements)}</div>
        <div class="summary-label">Screen Placements</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${num(totalWorkflowReferences)}</div>
        <div class="summary-label">Workflow References</div>
    </div>
    <div class="summary-card">
        <div class="summary-value">${includeReach ? num(allImpactedProjects.size()) : '<span class="muted">off</span>'}</div>
        <div class="summary-label">Projects Touched By An App${impactedIssuesPartial ? '<span class="warn" title="Not every project was counted">&#42;</span>' : ""}</div>
    </div>
</div>

<div class="notice">
    <strong>Interpretation:</strong>
    This report measures detectable Jira configuration and data footprint.
    It does not measure clicks or runtime user activity.
    A value that could not be read is never shown as zero, it is marked separately.
</div>
""")

    if (unresolvedTypeFields > 0 || totalDiagnostics > 0 || issueCountsSkippedByBudget > 0 || screenReachTruncated) {

        html.append("""<div class="diag">
    <strong>Measurement notes</strong>
    <ul>
""")

        if (unresolvedTypeFields > 0) {
            html.append("""        <li>
            ${num(unresolvedTypeFields)} custom field(s) have a type that could not be resolved.
            Those are typically fields whose providing app is disabled or removed. They carry data but
            cannot be attributed to an app through the plugin API and are therefore not part of any app below.
        </li>
""")
        }
        if (issueCountsSkippedByBudget > 0) {
            html.append("""        <li>
            ${num(issueCountsSkippedByBudget)} field(s) were not counted because the issue-count budget of
            ${num(issueBudgetMs)} ms was exhausted. They show <span class="warn">n/m</span>, not zero.
            Raise it with <span class="mono">issueBudgetMs=0</span> for an unlimited run.
        </li>
""")
        }
        if (screenReachTruncated) {
            html.append("""        <li>
            The screen scheme index was cut off after ${num(screenSchemesWalked)} of
            ${num(screenSchemesTotal)} screen schemes because the budget of ${num(issueBudgetMs)} ms
            ran out. Every field therefore shows <span class="warn">n/m</span> for project reach
            instead of a project list that would be too short. Raise it with
            <span class="mono">issueBudgetMs=0</span>, or drop the whole chain with
            <span class="mono">includeReach=false</span>.
        </li>
""")
        }
        if (totalDiagnostics > 0) {
            html.append("""        <li>
            ${num(totalDiagnostics)} read(s) failed and were suppressed. Affected apps carry a
            <span class="badge badge-diag">DIAGNOSTICS</span> badge, the details are inside the app card.
        </li>
""")
        }
        for (String entry : globalDiagnostics) {
            html.append("        <li class=\"mono\">" + esc(entry) + "</li>\n")
        }

        html.append("""    </ul>
</div>
""")
    }

    html.append("""<div class="toolbar">
    <input id="appSearch" class="search" type="search"
           placeholder="Search app, vendor, plugin key or extension type..." oninput="filterApps()">
    <label class="checkbox-label">
        <input id="footprintOnly" type="checkbox" onchange="filterApps()">
        Detected footprint only
    </label>
    <label class="checkbox-label">
        <input id="diagnosticsOnly" type="checkbox" onchange="filterApps()">
        With diagnostics only
    </label>
</div>
""")

    if (!decommissionCandidates.isEmpty()) {

        html.append("""<div class="notice">
    <strong>Decommission candidates (${num(decommissionCandidates.size())})</strong>
    <div style="margin-top:6px">
        Enabled, not system-provided, and carrying no detectable configuration or data footprint.
        That is a starting point for a conversation, not a verdict: UI-only, REST-only or
        runtime-only functionality leaves no trace here, and this report does not measure usage.
    </div>
    <ul>
""")
        for (AppFootprint candidate : decommissionCandidates) {
            html.append("""        <li>
            ${esc(candidate.displayName)}
            <span class="mono muted">${esc(candidate.pluginKey)}</span>
            ${candidate.enabled ? "" : '<span class="badge badge-disabled">DISABLED</span>'}
            &middot; ${num(candidate.enabledModuleCount)} enabled modules
        </li>
""")
        }
        html.append("""    </ul>
</div>
""")
    }

    /* ---- App cards --------------------------------------------------------- */

    for (AppFootprint app : apps) {

        StringBuilder searchText = new StringBuilder()
        searchText.append(app.displayName ?: "").append(" ")
            .append(app.descriptorName ?: "").append(" ")
            .append(app.vendor ?: "").append(" ")
            .append(app.pluginKey ?: "").append(" ")
        for (String category : app.categoryCounts.keySet()) {
            searchText.append(category).append(" ")
        }

        html.append("""<div class="app-card${app.enabled ? '' : ' is-disabled'}"
     data-search="${esc(searchText.toString().toLowerCase(Locale.ROOT))}"
     data-footprint="${app.detected}"
     data-diagnostics="${app.diagnosticCount > 0}">

<div class="app-header">
    <div class="app-header-row">
        <div class="app-identity">
            <div class="app-name">${esc(app.displayName)}</div>
            <div class="app-meta">${esc(app.vendor ?: "Unknown vendor")}${app.version != null ? " &middot; v" + esc(app.version) : ""}</div>
            <div class="app-meta">Descriptor name: <strong>${esc(app.descriptorName ?: Fp.NA)}</strong></div>
            <div class="app-meta mono">${esc(app.pluginKey)}</div>
        </div>
        <div class="badges">
            <span class="badge ${app.detected ? 'badge-footprint' : 'badge-capability'}">${app.detected ? 'DETECTED FOOTPRINT' : 'CAPABILITIES ONLY'}</span>
""")

        if (!app.enabled) {
            html.append("""            <span class="badge badge-disabled">DISABLED${app.state != null ? " &middot; " + esc(app.state) : ""}</span>\n""")
        }
        if (app.systemProvided) {
            html.append("""            <span class="badge badge-system">SYSTEM PROVIDED</span>\n""")
        }
        if (app.diagnosticCount > 0) {
            html.append("""            <span class="badge badge-diag">DIAGNOSTICS ${num(app.diagnosticCount)}</span>\n""")
        }

        html.append("""        </div>
    </div>
</div>

<div class="metrics">
    <div class="metric">
        <div class="metric-value">${num(app.enabledModuleCount)}</div>
        <div class="metric-label">Enabled Extension Modules</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.customFields.size())}</div>
        <div class="metric-label">App Custom Fields</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.issueFieldAssociations)}${app.issueFieldAssociationsPartial ? '<span class="warn" title="Incomplete, lower bound">&#42;</span>' : ''}</div>
        <div class="metric-label">Issue-Field Associations</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.screenPlacements)}</div>
        <div class="metric-label">Field Screen Placements</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.uniqueScreens)}</div>
        <div class="metric-label">Unique Screens</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.workflowCount)}</div>
        <div class="metric-label">Referencing Workflows</div>
    </div>
    <div class="metric">
        <div class="metric-value">${num(app.workflowReferenceCount)}</div>
        <div class="metric-label">Workflow References</div>
    </div>
    <div class="metric impact">
        <div class="metric-value">${impactCell(app)}</div>
        <div class="metric-label">Projects Touched${app.impactedIssues != null ? " &middot; " + num(app.impactedIssues) + " issues" : ""}</div>
    </div>
</div>

<div class="section">
    <div class="section-title">Jira Extension Capabilities</div>
    <div class="category-grid">
""")

        if (app.categoryCounts.isEmpty()) {
            html.append("""        <div class="empty">No enabled extension modules detected.</div>\n""")
        } else {
            for (Map.Entry<String, Integer> category : app.categoryCounts.entrySet()) {
                html.append("""        <div class="category">
            <div class="category-name">${esc(category.key)}</div>
            <div class="category-count">${num(category.value)} modules</div>
        </div>
""")
            }
        }

        html.append("""    </div>
    <details>
        <summary>Extension module types</summary>
        <div class="table-wrap">
        <table>
            <thead><tr><th>Descriptor</th><th class="num">Modules</th></tr></thead>
            <tbody>
""")

        for (Map.Entry<String, Integer> type : app.moduleTypeCounts.entrySet()) {
            html.append("""                <tr><td class="mono">${esc(type.key)}</td><td class="num">${num(type.value)}</td></tr>\n""")
        }

        html.append("""            </tbody>
        </table>
        </div>
    </details>
</div>

<div class="section">
    <div class="section-title">Custom Field Footprint</div>
""")

        if (app.customFields.isEmpty()) {
            html.append("""    <div class="empty">No instantiated custom fields using a custom-field type provided by this app were detected.</div>\n""")
        } else {
            html.append("""    <div class="table-wrap">
    <table>
        <thead>
            <tr>
                <th>Field</th>
                <th>Custom Field Type</th>
                <th class="num">Issues With Value</th>
                <th class="num">Contexts</th>
                <th>Project Scope</th>
                <th>Issue Type Scope</th>
                <th class="num">Screens</th>
                <th>Projects Reached Via Screens</th>
            </tr>
        </thead>
        <tbody>
""")

            for (CustomFieldFootprint field : app.customFields) {

                String projectScope
                if (field.allProjects == null) {
                    projectScope = "Not readable"
                } else if (field.allProjects.booleanValue()) {
                    projectScope = "All projects"
                } else if (!field.projectKeys.isEmpty()) {
                    projectScope = String.join(", ", field.projectKeys)
                } else {
                    projectScope = "No explicit projects"
                }

                String issueTypeScope
                if (field.allIssueTypes == null) {
                    issueTypeScope = "Not readable"
                } else if (field.allIssueTypes.booleanValue()) {
                    issueTypeScope = "All issue types"
                } else if (!field.issueTypes.isEmpty()) {
                    issueTypeScope = String.join(", ", field.issueTypes)
                } else {
                    issueTypeScope = "No explicit issue types"
                }

                String contextCell = field.contextCount == null ?
                    "<span class=\"bad\" title=\"Read failed\">err</span>" :
                    esc(num(field.contextCount))

                String screenCell = field.screensMeasured ?
                    esc(num(field.getUniqueScreenCount())) :
                    "<span class=\"bad\" title=\"Read failed\">err</span>"

                html.append("""            <tr>
                <td><strong>${esc(field.name)}</strong><div class="mono muted">${esc(field.id)}</div></td>
                <td class="mono">${esc(field.typeKey)}</td>
                <td class="num">${issueCell(field)}</td>
                <td class="num">${contextCell}</td>
                <td>${esc(projectScope)}</td>
                <td>${esc(issueTypeScope)}</td>
                <td class="num">${screenCell}</td>
                <td>${projectsCell(field.reachProjectKeys, field.reachState)}</td>
            </tr>
""")

                if (!field.screenPlacements.isEmpty()) {
                    html.append("""            <tr>
                <td colspan="8">
                    <details>
                        <summary>Screen placements for ${esc(field.name)}</summary>
                        <div class="table-wrap">
                        <table>
                            <thead><tr><th>Screen</th><th>Tab</th></tr></thead>
                            <tbody>
""")
                    for (ScreenPlacementInfo placement : field.screenPlacements) {
                        html.append("""                                <tr>
                                    <td>${esc(placement.screenName ?: "Unknown screen")} <span class="mono muted">${placement.screenId != null ? "(" + esc(placement.screenId) + ")" : ""}</span></td>
                                    <td>${esc(placement.tabName ?: "Unnamed tab")}</td>
                                </tr>
""")
                    }
                    html.append("""                            </tbody>
                        </table>
                        </div>
                    </details>
                </td>
            </tr>
""")
                }
            }

            html.append("""        </tbody>
    </table>
    </div>
""")
        }

        html.append("""</div>

<div class="section">
    <div class="section-title">Workflow Footprint</div>
""")

        if (app.workflowReferences.isEmpty()) {
            html.append("""    <div class="empty">No references to this app were found in the persisted workflow descriptors.</div>\n""")
        } else {
            html.append("""    <div class="table-wrap">
    <table>
        <thead>
            <tr>
                <th>Workflow</th>
                <th>Status</th>
                <th class="num">References</th>
                <th class="num">Plugin Key Hits</th>
                <th class="num">Module Class Hits</th>
                <th>Detection</th>
                <th>Projects Using This Workflow</th>
                <th class="num">Issues In Them</th>
            </tr>
        </thead>
        <tbody>
""")

            for (WorkflowReference reference : app.workflowReferences) {

                String statusCell
                if (reference.active == null) {
                    statusCell = "<span class=\"bad\" title=\"Read failed\">unknown</span>"
                } else if (reference.active.booleanValue()) {
                    statusCell = "<span class=\"good\">Active</span>"
                } else {
                    statusCell = "<span class=\"muted\">Inactive / Draft</span>"
                }

                String classCell = reference.classReferences == null ?
                    "<span class=\"muted\" title=\"Not evaluated, the plugin key already matched\">n/e</span>" :
                    esc(num(reference.classReferences))

                html.append("""            <tr>
                <td><strong>${esc(reference.name)}</strong></td>
                <td>${statusCell}</td>
                <td class="num">${num(reference.references)}</td>
                <td class="num">${num(reference.keyReferences)}</td>
                <td class="num">${classCell}</td>
                <td>${esc(reference.detection)}</td>
                <td>${projectsCell(reference.projectKeys, reference.reachState)}</td>
                <td class="num">${reference.issueCount == null ? '<span class="muted">n/a</span>' : esc(num(reference.issueCount))}</td>
            </tr>
""")

                if (!reference.matchingModuleClasses.isEmpty()) {
                    html.append("""            <tr>
                <td colspan="8">
                    <details>
                        <summary>Matching module classes</summary>
                        <ul>
""")
                    for (String className : reference.matchingModuleClasses) {
                        html.append("""                            <li class="mono">${esc(className)}</li>\n""")
                    }
                    html.append("""                        </ul>
                    </details>
                </td>
            </tr>
""")
                }
            }

            html.append("""        </tbody>
    </table>
    </div>
""")
        }

        html.append("""</div>
""")

        /* ---- Diagnostics --------------------------------------------------- */

        if (app.diagnosticCount > 0) {
            html.append("""<div class="section">
    <div class="section-title">Diagnostics</div>
    <details>
        <summary>${num(app.diagnosticCount)} suppressed read error(s)</summary>
        <ul>
""")
            for (String entry : app.diagnostics) {
                html.append("""            <li class="mono">${esc(entry)}</li>\n""")
            }
            for (CustomFieldFootprint field : app.customFields) {
                for (String entry : field.diagnostics) {
                    html.append("""            <li class="mono">${esc(field.name)}: ${esc(entry)}</li>\n""")
                }
            }
            html.append("""        </ul>
    </details>
</div>
""")
        }

        /* ---- Full module list ---------------------------------------------- */

        if (includeModules) {
            html.append("""<div class="section">
    <details>
        <summary>All plugin modules (${num(app.modules.size())})</summary>
        <div class="table-wrap">
        <table>
            <thead>
                <tr>
                    <th>Category</th>
                    <th>Descriptor</th>
                    <th>Name</th>
                    <th>Complete Key</th>
                    <th>Module Class</th>
                    <th>Status</th>
                </tr>
            </thead>
            <tbody>
""")
            for (AppModuleInfo module : app.modules) {
                String moduleStatus
                if (module.enabled == null) {
                    moduleStatus = "<span class=\"bad\">unknown</span>"
                } else if (module.enabled.booleanValue()) {
                    moduleStatus = "Enabled"
                } else {
                    moduleStatus = "Disabled"
                }
                html.append("""                <tr>
                    <td>${esc(module.category)}</td>
                    <td class="mono">${esc(module.descriptorName)}</td>
                    <td>${esc(module.name)}</td>
                    <td class="mono">${esc(module.completeKey)}</td>
                    <td class="mono">${esc(module.moduleClass)}</td>
                    <td>${moduleStatus}</td>
                </tr>
""")
            }
            html.append("""            </tbody>
        </table>
        </div>
    </details>
</div>
""")
        }

        html.append("""</div>
""")
    }

    /* ---- Footer -------------------------------------------------------------- */

    long executionMs = System.currentTimeMillis() - started

    html.append("""<div class="footer">
    <strong>Interpretation notes</strong>
    <ul>
        <li>App name is resolved through the plugin i18n name where available. The technical
            descriptor name and plugin key are shown separately.</li>
        <li>Enabled Extension Modules are capabilities supplied by the app, not occurrences of usage.</li>
        <li>Issue-Field Associations are the sum of issues containing non-empty values across
            app-owned custom fields. They are not a unique issue count. A value marked
            <span class="warn">&#42;</span> is a lower bound because at least one field was not counted.</li>
        <li>A read that failed is shown as <span class="bad">err</span>, a value that was not
            measured as <span class="warn">n/m</span>, and a value that was deliberately not
            evaluated as <span class="muted">n/e</span>. None of them is a zero.</li>
        <li>Workflow References are dependency signals found in persisted workflow descriptors,
            not workflow executions. Detection runs in two stages: the plugin key is counted first,
            and only if it does not appear at all are the module class names counted. The two
            columns are therefore not comparable, and matching is plain substring matching over the
            descriptor XML, which includes comments and descriptions.</li>
        <li>Module categories come from an ordered substring heuristic over the descriptor class
            name. The order is: Custom Fields, Workflow, JQL / Search, UI, REST / API, HTTP / Servlet,
            Events / Listeners, Jobs / Services, Reports / Dashboards, Permissions / Security,
            Project, Issue, Other. First match wins, so a descriptor matching several groups is
            counted in the earlier one only.</li>
        <li>Custom fields are attributed to an app when the field type key starts with the plugin key
            or equals one of the plugin module complete keys. Fields whose type cannot be resolved
            at all - typically because the providing app is disabled or removed - cannot be
            attributed and are reported in the measurement notes instead.</li>
        <li><strong>Projects Touched</strong> is the blast radius: the union of the projects reached
            through both paths. A workflow reference leads through the workflow schemes that contain
            that workflow to their projects; a custom field leads through the screens it sits on, the
            field screen schemes that use those screens, and the issue type screen schemes that bind
            them to projects. The union is taken before any issue is counted, so a project reached by
            both paths is counted once. The issue figure counts every issue in those projects, not
            issues that actually use the app - it is an upper bound on exposure, not a usage number.</li>
        <li>Projects that inherit the default workflow scheme without an explicit association may not
            appear on the workflow path. Treat the project list as a lower bound and verify a
            surprising zero against the scheme configuration.</li>
        <li><strong>REST and Servlet modules</strong> count what the app hangs into the web layer,
            taken from the descriptor names. It is a surface indicator, not a vulnerability finding.</li>
        <li>UI-only, REST-only or runtime-only functionality can be heavily used without leaving a
            measurable Jira configuration footprint. This report measures no requests and no clicks,
            so an app listed as a decommission candidate may still be in daily use.</li>
        <li>App-specific objects such as Structures, ScriptRunner Behaviours, Jobs, Listeners,
            Tempo objects or eazyBI definitions require dedicated resolvers.</li>
        <li>This report is read-only. It performs no write of any kind and makes no outbound network call.</li>
    </ul>
    Report version 3.1 &nbsp;&middot;&nbsp; execution time ${num(executionMs)} ms &nbsp;&middot;&nbsp;
    workflow scan ${num(workflowXmlBytes)} XML characters, ${num(globalTokens.size())} distinct tokens across all descriptors
</div>

</div>

<script>
function filterApps() {
    var query = document.getElementById('appSearch').value.trim().toLowerCase();
    var footprintOnly = document.getElementById('footprintOnly').checked;
    var diagnosticsOnly = document.getElementById('diagnosticsOnly').checked;
    document.querySelectorAll('.app-card').forEach(function (card) {
        var text = card.dataset.search || '';
        var matchesSearch = query.length === 0 || text.includes(query);
        var matchesFootprint = !footprintOnly || card.dataset.footprint === 'true';
        var matchesDiagnostics = !diagnosticsOnly || card.dataset.diagnostics === 'true';
        card.classList.toggle('hidden', !(matchesSearch && matchesFootprint && matchesDiagnostics));
    });
}
</script>

</body>
</html>
""")

    return responseClass
        .ok(html.toString())
        .type("text/html; charset=UTF-8")
        .build()
}
