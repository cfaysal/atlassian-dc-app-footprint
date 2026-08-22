/* =============================================================================
 * Jira Data Center - App Footprint Analysis
 * ScriptRunner Custom REST Endpoint. Admin-gated. The measurement is read-only;
 * the only write is the opt-in Confluence page export described below.
 *
 * Version
 *   Declared once as Fp.VERSION below and printed by every output channel: the
 *   HTML report, the JSON and the generated Confluence page. The number lives in
 *   exactly one place, so this header cannot drift away from the code.
 *
 * Purpose
 *   Measures how much detectable configuration and data footprint every
 *   installed app leaves behind in this Jira instance. Built as an audit
 *   instrument for customer instances: the measurement itself never writes and
 *   produces a self-contained artifact (HTML / JSON / CSV).
 *
 * Confluence page export (POST on the same endpoint URL)
 *   The HTML report can write its executive summary into a Confluence page and
 *   updates that same page on every later run. Confluence is a foreign instance
 *   from inside a Jira JVM, so the application links, the space list, the page
 *   search, the existence check and the write all travel through a Jira
 *   application link. Those are the only outbound calls in this file and they are
 *   deliberate: there is no local Confluence type to talk to. A missing link is
 *   reported as a missing link, never as an empty space list.
 *   Rendering the report performs no lookup at all. The export is staged behind
 *   its button: the click lists the Confluence application links, choosing a
 *   target loads that target's spaces, choosing a space opens the parent page
 *   search, and only then can the page be generated. Every stage is one POST on
 *   this endpoint, discriminated by an "action" field in the body.
 *   The parent page field has no button. A title that was typed and never picked
 *   is adopted if such a page already exists and created by the generating run if
 *   it does not; the answer says which of the two happened. If it cannot be
 *   created the run aborts - the report is never filed at the top level of the
 *   space instead, where nobody would look for it.
 *   The Decision column of the generated page belongs to the administrator. It
 *   is read back from the existing page and carried over verbatim, and if that
 *   read fails for any reason nothing is written at all - see DecisionRead.
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

import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkRequestFactory
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.ApplicationType
import com.atlassian.applinks.api.CredentialsRequiredException

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

import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.sal.api.net.Request

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate

import org.ofbiz.core.entity.GenericValue

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.BaseScript

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@BaseScript CustomEndpointDelegate delegate

/* =============================================================================
 * Utility - deliberately free of any Jira type so it stays unit-testable
 * ========================================================================== */

class Fp {

    static final String NA = "—"

    /* The single place the report version lives. The file header points here and
     * every output channel prints this constant, so a report always names the
     * build that produced it. */
    static final String VERSION = "3.1"

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

    /* A diagnostics list holds two different kinds of entry. A read error is a
     * failure a caught exception produced, and note() above is its only source.
     * An observation is a statement this report makes on purpose: a budget that
     * ran out, a cross-check that disagreed. Both belong in the report, but
     * calling an observation a suppressed read error is a false alarm, so a
     * deliberate entry is recorded in the observation list as well and the two
     * are counted apart. */
    static void observe(List<String> sink, List<String> observations, String text) {
        if (sink == null || text == null) {
            return
        }
        sink.add(text)
        if (observations != null) {
            observations.add(text)
        }
    }

    /* ---- Measurement notes box --------------------------------------------- */

    /* The box carries two kinds of line and they do not mean the same thing.
     * Something that limits or degrades the measurement is a warning. An
     * observation is a statement this report makes on purpose, and its own text
     * says that nothing failed and nothing was suppressed.
     *
     * The colour follows the content. A box whose text says nothing failed must
     * not be painted as though something did: that is the report contradicting
     * itself, and an administrator who learns that yellow can mean nothing stops
     * reading the yellow boxes that mean something.
     *
     * Warning wins when both kinds are present. A real limitation is not made less
     * true by an observation standing next to it.
     *
     * The heading stays "Measurement notes" either way. The box is never split in
     * two and never hidden: the observations are worth reading, they are simply
     * not faults. Both variants keep the base class, so the box geometry and the
     * list rules apply to either colour.
     *
     * The conditions below are the ones that already gate the individual list
     * items, so the colour cannot drift away from the lines it describes. */
    static final String DIAG_INFO = "diag diag-info"
    static final String DIAG_WARN = "diag diag-warn"

    /* Whether the box appears at all. Unchanged in effect, extracted so the
     * visibility rule and the colour rule cannot drift apart. */
    static boolean diagBoxShown(int unresolvedTypeFields, int diagnostics,
                                int issueCountsSkippedByBudget, boolean screenReachTruncated) {
        return unresolvedTypeFields > 0 || diagnostics > 0 || issueCountsSkippedByBudget > 0 ||
            screenReachTruncated
    }

    /* An unresolved type field, an issue count skipped by the budget, a truncated
     * screen reach and a suppressed read error are the four things that degrade
     * this report. Observations are deliberately not an input: no number of them
     * can turn the box into a warning. */
    static String diagClass(int unresolvedTypeFields, int issueCountsSkippedByBudget,
                            boolean screenReachTruncated, int readErrors) {
        return (unresolvedTypeFields > 0 || issueCountsSkippedByBudget > 0 || screenReachTruncated ||
            readErrors > 0) ? DIAG_WARN : DIAG_INFO
    }

    /* Everything in a diagnostics list that is not a deliberate observation. */
    static List<String> readErrorsOf(List<String> diagnostics, List<String> observations) {
        List<String> errors = new ArrayList<String>()
        if (diagnostics == null) {
            return errors
        }
        for (String entry : diagnostics) {
            if (observations == null || !observations.contains(entry)) {
                errors.add(entry)
            }
        }
        return errors
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
 * Confluence page export - decision read
 * ========================================================================== */

/* Result of reading the Decision column back from an existing export page.
 * The three outcomes are kept distinguishable on purpose: a failed read must
 * never look like "this page has no decisions yet", because the caller would
 * then render an empty Decision column and overwrite every administrator note.
 * Same discipline as every measurement in this report - a failed read is never
 * reported as an empty or zero result. */
class DecisionRead {

    static final String NONE = "none"
    static final String PARSED = "parsed"
    static final String FAILED = "failed"

    String outcome = NONE
    String reason
    String pageId
    int pageVersion

    Map<String, String> decisions = new LinkedHashMap<String, String>()

    /* The single gate every write path has to pass. FAILED never gets through. */
    boolean isWriteAllowed() {
        return outcome == NONE || outcome == PARSED
    }

    DecisionRead fail(String why) {
        outcome = FAILED
        reason = why
        decisions.clear()
        return this
    }

    Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        result.put("outcome", outcome)
        result.put("reason", reason)
        result.put("decisions", Integer.valueOf(decisions.size()))
        result.put("pageId", pageId)
        result.put("pageVersion", Integer.valueOf(pageVersion))
        return result
    }
}

/* Rendered storage format plus what happened to the carried-over decisions. */
class ExportOutcome {
    String storage
    int decisionsRead
    int decisionsCarried
    List<String> orphanKeys = new ArrayList<String>()
    List<String> warnings = new ArrayList<String>()
}


/* =============================================================================
 * Confluence page export - storage format
 * ========================================================================== */

/* Free of any Jira and any Confluence type: Maps, Strings and the report DTOs
 * only. The transport lives further down, this class only decides what the page
 * says. java.util.regex is written out in full so the whole class block stays
 * self-contained for the offline harness. */
class PageExport {

    /* Written into every generated page and required on every update. A page
     * that does not carry it was not produced by this export, so its body is
     * never replaced - neither by a repeat run nor by a forged request. */
    static final String MARKER = "cfcon-app-footprint-export/1"

    /* Column headers are the contract. Both columns are located by NAME on read,
     * never by position, so inserting a column does not orphan a single note. */
    static final String COL_KEY = "App Key"
    static final String COL_DECISION = "Decision"

    /* State and annotation get one column each. Every column is located by name
     * on read, so this extra one orphans not a single decision. */
    static final String COL_NOTES = "Notes"

    static final String NOT_APPLICABLE = "notApplicable"
    static final String PARTIAL = "partial"
    static final String DEFAULT_TITLE = "JIRA App Footprint - Executive Summary"

    static final int MAX_PAYLOAD_CHARS = 4000000
    static final int MAX_TITLE_CHARS = 255
    static final int MAX_MODULE_TYPES = 20

    /* A request carries either the id of a picked parent or the title of one to
     * be created, never both. The refusal text is a constant so the offline
     * suite can assert on the contract rather than on a copy of the sentence. */
    static final String PARENT_BOTH = "The request carries a parent page id and a parent page title at the same time. " +
        "Exactly one of them is expected: the id of a page that was picked, or the title of a page to create. " +
        "Nothing is written."

    /* The parent instruction of a request: exactly one of id and title, or
     * neither. Empty on success, otherwise the reason. Pure string logic on
     * purpose, so the rule that a request never carries both is checked by the
     * offline suite and not only on a running instance. */
    static String parentProblem(String parentId, String parentTitle, String reportTitle) {
        String id = parentId == null ? "" : parentId.trim()
        String parent = parentTitle == null ? "" : parentTitle.trim()
        String report = reportTitle == null ? "" : reportTitle.trim()
        if (!id.isEmpty() && !parent.isEmpty()) {
            return PARENT_BOTH
        }
        if (parent.length() > MAX_TITLE_CHARS) {
            return "The parent page title exceeds " + String.valueOf(MAX_TITLE_CHARS) + " characters."
        }
        /* A page cannot be its own parent, and Confluence titles are unique per
         * space, so the two titles being equal has no outcome that works. Caught
         * here rather than halfway through: otherwise the container page is
         * created first and the report write then fails on the duplicate title,
         * leaving a page behind that nothing was ever filed under. */
        if (!parent.isEmpty() && parent.equalsIgnoreCase(report)) {
            return "The parent page and the report page carry the same title \"" + parent +
                "\". A page cannot be its own parent. Nothing is written."
        }
        return ""
    }

    /* ---- Parent position ---------------------------------------------------- */

    /* What this run does about the position of the report page.
     *
     * A parent named in THIS run - picked from the search or created from a typed
     * title - is an instruction, and it is carried out even when the report page
     * already exists. That is the defect this replaces: the parent was applied on
     * the create branch only, so a second run rewrote the report and left it
     * wherever it was, while the response still reported the parent.
     *
     * The protection the old guard was built for is kept, narrowed to the case it
     * actually covers: a run that names no parent does not touch the position, so
     * a page an administrator moved by hand stays moved. */
    static final String MOVE_REQUESTED = "move"
    static final String MOVE_NOT_REQUESTED = "not-requested"
    static final String MOVE_ALREADY_THERE = "already-there"

    /* Pure decision, no instance needed, so the offline suite checks the rule and
     * not a run that happened to behave. An unknown current position resolves to
     * "move": carrying out the instruction is the safe direction, and only a
     * positive match skips. The skip exists so an unchanged repeat run does not
     * rewrite the page into the position it already holds. */
    static String moveDecision(String requestedParentId, String currentParentId) {
        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return MOVE_NOT_REQUESTED
        }
        String current = currentParentId == null ? "" : currentParentId.trim()
        if (!current.isEmpty() && current.equals(requested)) {
            return MOVE_ALREADY_THERE
        }
        return MOVE_REQUESTED
    }

    /* Three states and no fourth. They are strings rather than a JSON boolean with
     * a special case, because a browser that writes if (!body.parentApplied) reads
     * a mixed boolean-or-string field as a success - which is exactly the silent
     * mismatch this measurement exists to prevent. */
    static final String PARENT_APPLIED_TRUE = "true"
    static final String PARENT_APPLIED_FALSE = "false"
    static final String PARENT_APPLIED_UNKNOWN = "unknown"

    /* The direct parent named by a Confluence content response, kept apart from the
     * case where no ancestors arrived at all. Ancestors run from the root of the
     * space downwards, so the direct parent is the last entry that names an id.
     *
     * measured=true with a null parentId means the response carried an ancestor
     * array and it was empty, so the page sits at the top level of the space - a
     * real measurement. measured=false means the response carried no ancestor
     * array at all, which measures nothing and must never be read as "the page has
     * no parent". rowsOf is deliberately not used here: it answers an absent key
     * and an empty array with the same empty list, and that is the one distinction
     * this method exists to make. */
    static Map<String, Object> innermostAncestor(Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("measured", Boolean.FALSE)
        out.put("parentId", null)
        if (content == null) {
            return out
        }
        Object node = content.get("ancestors")
        if (!(node instanceof List)) {
            return out
        }
        out.put("measured", Boolean.TRUE)
        List<Object> rows = (List<Object>) node
        for (int i = rows.size() - 1; i >= 0; i--) {
            Object row = rows.get(i)
            if (!(row instanceof Map)) {
                continue
            }
            Object id = ((Map<String, Object>) row).get("id")
            if (id != null && !String.valueOf(id).trim().isEmpty()) {
                out.put("parentId", String.valueOf(id).trim())
                return out
            }
        }
        return out
    }

    /* The verdict on the position, and it is a measurement or it is nothing.
     *
     * "true"    - the read-back answered and named the requested parent.
     * "false"   - the read-back answered and named something else, or nothing.
     * "unknown" - the read-back itself did not answer.
     *
     * A failed or empty read is never reported as a successful move, and never as
     * a failed one either: neither was measured, so neither is claimed. applied
     * stays null when this run named no parent, because then there is no question
     * to answer and the position was deliberately left alone.
     *
     * A move call that returned without throwing is a report about itself and is
     * deliberately not an input here. moveError only sharpens the wording of a
     * verdict that was measured either way. */
    static Map<String, Object> parentOutcome(String requestedParentId, boolean readBackOk,
                                             String actualParentId, String moveError) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("applied", null)
        out.put("reason", null)

        String requested = requestedParentId == null ? "" : requestedParentId.trim()
        if (requested.isEmpty()) {
            return out
        }

        String failure = moveError == null ? "" : moveError.trim()

        if (!readBackOk) {
            out.put("applied", PARENT_APPLIED_UNKNOWN)
            out.put("reason", "The report page was written, but its position could not be read back" +
                (failure.isEmpty() ? "" : " and the move reported \"" + failure + "\"") +
                ", so whether it sits under the parent page was not measured. Open the parent page and " +
                "check before relying on this run.")
            return out
        }

        String actual = actualParentId == null ? "" : actualParentId.trim()
        if (actual.equals(requested)) {
            out.put("applied", PARENT_APPLIED_TRUE)
            return out
        }

        out.put("applied", PARENT_APPLIED_FALSE)
        out.put("reason", "The report page was written, but it does not sit under the parent page that was " +
            "requested: it sits " + (actual.isEmpty() ? "at the top level of the space" : "under page " + actual) +
            "." + (failure.isEmpty() ? "" : " The move reported \"" + failure + "\"."))
        return out
    }

    /* Body of a parent page this export creates. Minimal on purpose: it says what
     * the page is for and where it came from, and it holds no report data, which
     * lives on the child page and is rewritten on every run. */
    static final String PARENT_BODY = "<p>Container page for the Jira App Footprint Analysis export. " +
        "It was created by that export because the chosen parent page did not exist yet. " +
        "The report itself is the child page below; this page carries no report data and is never rewritten.</p>"

    /* Space picker paging. 20 pages of 200 covers every instance we have seen;
     * past that the picker reports itself truncated rather than showing a short
     * list that looks complete. */
    static final int SPACE_PAGE_SIZE = 200
    static final int MAX_SPACE_PAGES = 20

    /* Search stages. The page search asks Confluence for at most this many titles
     * and refuses a shorter term, so a single keystroke never pulls a whole space
     * back. The space list is filtered against the same minimum in the browser. */
    static final int SEARCH_LIMIT = 25
    static final int MIN_SEARCH_CHARS = 2

    /* Idle pause before a typed title is searched for. The parent field has no
     * button, so the search is what typing does - but not once per keystroke:
     * that is a call per character and a list that is rebuilt faster than it can
     * be read. */
    static final int SEARCH_IDLE_MS = 300

    /* A CQL string literal ends at a quote, and CQL documents "*" and "?" as
     * wildcards and "~" as an operator character, but documents no escaping rule
     * for literals. Everything that could change the meaning of a query is
     * therefore removed rather than escaped, and the caller appends the one
     * wildcard it wants - which also makes a leading wildcard impossible, as the
     * CQL text-search documentation requires. */
    static final String CQL_STRIP = "\"\\*?~\n\r"

    static String cqlTerm(String value) {
        if (value == null) {
            return ""
        }
        StringBuilder out = new StringBuilder()
        for (int i = 0; i < value.length(); i++) {
            String character = value.substring(i, i + 1)
            out.append(CQL_STRIP.contains(character) ? " " : character)
        }
        return out.toString().trim()
    }

    /* A space key is an identifier, not a search term, and must never go through
     * cqlTerm(): that sanitiser drops "~", so the personal space "~cfaysal"
     * silently became the key "cfaysal", which exists nowhere. Confluence then
     * answers zero hits and no error, and the mistake is invisible. The key is
     * therefore checked instead of cleaned, and a key that fails the check is
     * refused by name rather than searched for in a mangled form.
     *
     * The set below is a whitelist. A leading "~" marks a personal space; after
     * it stands the user key, which is not documented to be alphanumeric, so the
     * punctuation user keys are known to carry is admitted as well. Nothing in
     * the set can end a CQL string literal or act as a wildcard, which is what
     * cqlTerm() was protecting against in the first place. */
    static final String SPACE_KEY_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz" + "0123456789" + "_-.@"

    /* Empty on success, otherwise the reason in words. A reason rather than a
     * boolean, so the caller can name the offending value AND say what is wrong
     * with it - "invalid space key" sends an administrator guessing. */
    static String spaceKeyProblem(String value) {
        String key = value == null ? "" : value.trim()
        if (key.isEmpty()) {
            return "it is empty"
        }
        String body = key.startsWith("~") ? key.substring(1) : key
        if (body.isEmpty()) {
            return "a personal space key carries the user key after the tilde, \"~\" on its own is not a key"
        }
        for (int i = 0; i < body.length(); i++) {
            String character = body.substring(i, i + 1)
            if (SPACE_KEY_CHARS.contains(character)) {
                continue
            }
            if (character == "~") {
                return "only a leading tilde is allowed, and it marks a personal space"
            }
            if (character.trim().isEmpty()) {
                return "a space key contains no whitespace"
            }
            return "the character \"" + character + "\" is not allowed in a space key"
        }
        return ""
    }

    /* Confluence hands empty cells back self-closed after an editor round trip,
     * so both forms are matched. The self-closing alternative has to come first,
     * otherwise <td/> is consumed by the open-tag branch and swallows the row. */
    static final java.util.regex.Pattern TBODY = java.util.regex.Pattern.compile("(?s)<tbody[^>]*>(.*?)</tbody>")
    static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>")
    static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("(?s)<t[hd][^>]*/>|<t[hd][^>]*>(.*?)</t[hd]>")
    static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("<[^>]+>")

    static String plainText(String cellHtml) {
        if (cellHtml == null) {
            return ""
        }
        String value = TAG.matcher(cellHtml).replaceAll(" ")
        value = value.replace("&nbsp;", " ").replace("&#160;", " ").replace("\u00A0", " ")
        value = value.replace("&lt;", "<").replace("&gt;", ">")
        value = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        value = value.replace("&amp;", "&")
        return value.replaceAll("\\s+", " ").trim()
    }

    /* Whitespace, a non-breaking space and the wrappers an editor leaves behind
     * carry no decision. Anything else that is still a tag does: a status
     * lozenge, an image, an emoticon, a link. */
    static final java.util.regex.Pattern LAYOUT_TAG =
        java.util.regex.Pattern.compile("(?i)</?(?:p|br|div|span)(?:\\s[^>]*)?/?>")

    /* A cell is empty only when it holds neither text nor element content.
     * Deciding that on the plain text alone dropped every cell whose markup
     * carries no text node, and the row was not even counted as read. */
    static boolean isEmptyCell(String cellHtml) {
        if (cellHtml == null) {
            return true
        }
        if (!plainText(cellHtml).isEmpty()) {
            return false
        }
        String rest = LAYOUT_TAG.matcher(cellHtml).replaceAll("")
        rest = rest.replace("&nbsp;", "").replace("&#160;", "").replace("\u00A0", "")
        return rest.trim().isEmpty()
    }

    /* The placeholder written into a row that carries no decision: a grey status
     * lozenge the administrator edits instead of building one. The Confluence
     * status macro takes a colour and a title and carries no body. */
    static final String DECISION_SEED =
        "<ac:structured-macro ac:name=\"status\" ac:schema-version=\"1\">" +
        "<ac:parameter ac:name=\"colour\">Grey</ac:parameter>" +
        "<ac:parameter ac:name=\"title\">TBD</ac:parameter>" +
        "</ac:structured-macro>"

    static final java.util.regex.Pattern MACRO_ID =
        java.util.regex.Pattern.compile("\\s+ac:macro-id=\"[^\"]*\"")

    /* The seed is this export's own markup, never an administrator's note, so it
     * reads back as no decision. An editor round trip stamps a macro-id onto
     * every macro and may wrap the cell in a paragraph, so the comparison is made
     * on the normalised form. Change the colour or the title and it is a decision
     * again, carried over verbatim like any other. */
    static boolean isDecisionSeed(String cellHtml) {
        if (cellHtml == null) {
            return false
        }
        String value = cellHtml.trim()
        if (value.startsWith("<p>") && value.endsWith("</p>")) {
            value = value.substring(3, value.length() - 4).trim()
        }
        value = MACRO_ID.matcher(value).replaceAll("")
        return value.replaceAll(">\\s+<", "><").trim() == DECISION_SEED
    }

    static List<String> cellsOf(String rowHtml) {
        List<String> cells = new ArrayList<String>()
        if (rowHtml == null) {
            return cells
        }
        java.util.regex.Matcher matcher = CELL.matcher(rowHtml)
        while (matcher.find()) {
            String inner = matcher.group(1)
            cells.add(inner == null ? "" : inner)
        }
        return cells
    }

    static int headerIndex(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (plainText(header.get(i)).equalsIgnoreCase(name)) {
                return i
            }
        }
        return -1
    }

    /* Exception class plus message. Both the decision read and the write path
     * report a failure in exactly this wording. */
    static String errorDetail(Throwable error) {
        String detail = error.getClass().getSimpleName()
        String message = error.getMessage()
        if (message != null && !message.trim().isEmpty()) {
            detail = detail + ": " + message.trim()
        }
        return detail
    }

    /* Reads every decision table on the page, not just the first one: the
     * orphaned-decision table is a second table and its notes have to survive
     * as well. Anything unexpected is FAILED, never an empty success. */
    static DecisionRead parseDecisions(String storage) {
        DecisionRead read = new DecisionRead()

        try {
            if (storage == null || storage.trim().isEmpty()) {
                return read.fail("The existing page has an empty body. It was not produced by this export, so it is not overwritten.")
            }
            if (!storage.contains(MARKER)) {
                return read.fail("The existing page does not carry the export marker \"" + MARKER + "\". It was not produced by this export, so it is not overwritten.")
            }

            int tablesMatched = 0
            java.util.regex.Matcher bodyMatcher = TBODY.matcher(storage)

            while (bodyMatcher.find()) {
                List<String> rows = new ArrayList<String>()
                java.util.regex.Matcher rowMatcher = ROW.matcher(bodyMatcher.group(1))
                while (rowMatcher.find()) {
                    rows.add(rowMatcher.group(1))
                }
                if (rows.isEmpty()) {
                    continue
                }

                List<String> header = cellsOf(rows.get(0))
                int keyIndex = headerIndex(header, COL_KEY)
                int decisionIndex = headerIndex(header, COL_DECISION)
                if (keyIndex < 0 || decisionIndex < 0) {
                    continue
                }
                tablesMatched++

                int required = Math.max(keyIndex, decisionIndex) + 1
                for (int i = 1; i < rows.size(); i++) {
                    List<String> cells = cellsOf(rows.get(i))
                    if (cells.isEmpty()) {
                        continue
                    }
                    if (cells.size() < required) {
                        return read.fail("Row " + String.valueOf(i) + " of a decision table carries " + String.valueOf(cells.size()) +
                            " cell(s) where " + String.valueOf(required) + " are needed. The table structure was changed; nothing is written.")
                    }

                    String key = plainText(cells.get(keyIndex))
                    String decisionHtml = cells.get(decisionIndex).trim()
                    if (key.isEmpty() || isEmptyCell(decisionHtml) || isDecisionSeed(decisionHtml)) {
                        continue
                    }
                    if (read.decisions.containsKey(key)) {
                        return read.fail("App key \"" + key + "\" carries more than one decision on the existing page. That is ambiguous; nothing is written.")
                    }
                    read.decisions.put(key, decisionHtml)
                }
            }

            if (tablesMatched == 0) {
                return read.fail("No table with the columns \"" + COL_KEY + "\" and \"" + COL_DECISION +
                    "\" was found on the existing page. The read is inconclusive; nothing is written.")
            }

            read.outcome = DecisionRead.PARSED
            return read
        } catch (Throwable error) {
            return read.fail("The decision read failed (" + errorDetail(error) + "); nothing is written.")
        }
    }

    /* ---- Payload accessors (the POST body is JSON, so nothing is assumed) --- */

    static String str(Map<String, Object> source, String key, String fallback) {
        Object raw = source == null ? null : source.get(key)
        if (raw == null) {
            return fallback
        }
        String value = raw.toString().trim()
        return value.isEmpty() ? fallback : value
    }

    static long lng(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        if (raw instanceof Number) {
            return ((Number) raw).longValue()
        }
        if (raw == null) {
            return 0L
        }
        try {
            return Long.parseLong(raw.toString().trim())
        } catch (NumberFormatException ignored) {
            return 0L
        }
    }

    /* A payload figure, formatted the way the report itself formats it. */
    static String numberOf(Map<String, Object> source, String key, Locale locale) {
        return Fp.number(Long.valueOf(lng(source, key)), locale)
    }

    static boolean flag(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue()
        }
        return raw != null && raw.toString().trim().equalsIgnoreCase("true")
    }

    static Map<String, Object> sub(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key)
        return raw instanceof Map ? copyMap((Map<?, ?>) raw) : new LinkedHashMap<String, Object>()
    }

    static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        if (source == null) {
            return result
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object rawKey = entry.getKey()
            if (rawKey != null) {
                result.put(rawKey.toString(), entry.getValue())
            }
        }
        return result
    }

    static List<Map<String, Object>> rowsOf(Map<String, Object> source, String key) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>()
        Object raw = source == null ? null : source.get(key)
        if (!(raw instanceof List)) {
            return result
        }
        for (Object element : (List<?>) raw) {
            if (element instanceof Map) {
                result.add(copyMap((Map<?, ?>) element))
            }
        }
        return result
    }

    /* ---- Measurement state ------------------------------------------------- */

    /* Derived from the model, never from the rendered HTML: the model still knows
     * whether a zero is a measured zero or an unmeasured one. An app without any
     * app-owned custom field has a genuine zero here, not a gap. */
    static String associationState(AppFootprint app, boolean issueCounts) {
        if (!issueCounts) {
            return Fp.DISABLED
        }
        if (app.customFields.isEmpty()) {
            return Fp.MEASURED
        }
        int measured = 0
        for (CustomFieldFootprint field : app.customFields) {
            if (field.issuesWithValueState == Fp.MEASURED) {
                measured++
            }
        }
        if (measured == 0) {
            return Fp.BUDGET
        }
        return measured == app.customFields.size() ? Fp.MEASURED : PARTIAL
    }

    /* Project reach already carries its own state in the model. NOT_EVALUATED
     * stays distinguishable from a measured zero, exactly as the report card
     * renders it - n/e is not n/m and neither of them is 0. */
    static String reachState(AppFootprint app, boolean includeReach) {
        if (!includeReach) {
            return Fp.DISABLED
        }
        if (app.impactState == Fp.NOT_EVALUATED) {
            return Fp.NOT_EVALUATED
        }
        return app.impactPartial ? PARTIAL : Fp.MEASURED
    }

    /* Same states for a whole-report total, driven by the report's partial flag. */
    static String summaryState(boolean dimensionEnabled, boolean partial) {
        if (!dimensionEnabled) {
            return Fp.DISABLED
        }
        return partial ? PARTIAL : Fp.MEASURED
    }

    /* A value the report shows as off, n/e or n/m must not appear as 0 here. */
    static String usageText(String state, Number value, Locale locale) {
        if (state == NOT_APPLICABLE) {
            return Fp.NA
        }
        if (state == Fp.MEASURED) {
            return Fp.number(value, locale)
        }
        if (state == PARTIAL) {
            return Fp.number(value, locale) + " *"
        }
        if (state == Fp.DISABLED) {
            return "off"
        }
        if (state == Fp.NOT_EVALUATED) {
            return "n/e"
        }
        return "n/m"
    }

    /* ---- Storage format ---------------------------------------------------- */

    static String cell(String innerHtml) {
        return "<td><p>" + innerHtml + "</p></td>"
    }

    static String head(String label) {
        return "<th><p>" + Fp.html(label) + "</p></th>"
    }

    static String metricRow(String label, String value) {
        return "<tr>" + cell(Fp.html(label)) + cell(Fp.html(value)) + "</tr>"
    }

    /* The one place a carried-over decision is written out. Raw cell content, so
     * an administrator's own wording next to KEEP survives, not just the keyword.
     * Never escaped and never regenerated - it is the administrator's text. */
    static String decisionCell(String decisionHtml) {
        return decisionHtml == null ? cell("&#160;") : "<td>" + decisionHtml + "</td>"
    }

    /* Category and module-type counts are per app in the model. The page shows
     * the instance-wide picture, so they are folded together here: the payload
     * stays the app model and the aggregation stays reproducible. */
    static Map<String, Integer> foldCounts(List<Map<String, Object>> apps, String key) {
        Map<String, Integer> total = new LinkedHashMap<String, Integer>()
        for (Map<String, Object> app : apps) {
            Map<String, Object> counts = sub(app, key)
            for (Map.Entry<String, Object> entry : counts.entrySet()) {
                String name = entry.getKey()
                if (name == null || name.trim().isEmpty()) {
                    continue
                }
                Object raw = entry.getValue()
                int value = raw instanceof Number ? ((Number) raw).intValue() : 0
                Integer seen = total.get(name)
                total.put(name, Integer.valueOf(seen == null ? value : seen.intValue() + value))
            }
        }
        return total
    }

    /* Descending by count, ties by name, optionally capped. */
    static List<Map.Entry<String, Integer>> sortedByCount(Map<String, Integer> counts, int cap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet())
        entries.sort { Map.Entry<String, Integer> a, Map.Entry<String, Integer> b ->
            int byCount = Integer.compare(b.getValue().intValue(), a.getValue().intValue())
            if (byCount != 0) {
                return byCount
            }
            return a.getKey().compareToIgnoreCase(b.getKey())
        }
        if (cap > 0 && entries.size() > cap) {
            return entries.subList(0, cap)
        }
        return entries
    }

    static String renderInstance(Map<String, Object> instance, Locale locale) {
        StringBuilder out = new StringBuilder()
        out.append("<h2>Instance</h2>")
        out.append("<table><tbody><tr>").append(head("Property")).append(head("Value")).append("</tr>")
        out.append(metricRow("Report version", Fp.VERSION))
        out.append(metricRow("Instance", str(instance, "title", Fp.NA)))
        out.append(metricRow("Base URL", str(instance, "baseUrl", Fp.NA)))
        out.append(metricRow("Jira version", str(instance, "jiraVersion", Fp.NA)))
        out.append(metricRow("Jira build", str(instance, "jiraBuild", Fp.NA)))
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderSummary(Map<String, Object> summary, Locale locale) {
        String associationState = str(summary, "associationState", Fp.BUDGET)
        String reachState = str(summary, "reachState", Fp.BUDGET)

        StringBuilder out = new StringBuilder()
        out.append("<h2>Key Figures</h2>")
        out.append("<table><tbody><tr>").append(head("Metric")).append(head("Value")).append("</tr>")
        out.append(metricRow("Apps in report", numberOf(summary, "apps", locale)))
        out.append(metricRow("Disabled apps", numberOf(summary, "disabledApps", locale)))
        out.append(metricRow("Apps with a detectable footprint", numberOf(summary, "appsWithFootprint", locale)))
        out.append(metricRow("Decommission candidates", numberOf(summary, "decommissionCandidates", locale)))
        out.append(metricRow("App custom fields", numberOf(summary, "customFields", locale)))
        out.append(metricRow("Issue-field associations", usageText(associationState, Long.valueOf(lng(summary, "issueFieldAssociations")), locale)))
        out.append(metricRow("Screen placements", numberOf(summary, "screenPlacements", locale)))
        out.append(metricRow("Workflow references", numberOf(summary, "workflowReferences", locale)))
        out.append(metricRow("Workflows scanned", numberOf(summary, "workflowsScanned", locale) + " of " + numberOf(summary, "workflowsTotal", locale)))
        out.append(metricRow("Projects touched by an app", usageText(reachState, Long.valueOf(lng(summary, "impactedProjects")), locale)))
        out.append(metricRow("Issues in the reach of an app", usageText(reachState, Long.valueOf(lng(summary, "impactedIssues")), locale)))
        out.append(metricRow("Custom fields in the instance", numberOf(summary, "customFieldsTotal", locale)))
        out.append(metricRow("Custom fields with an unresolved type", numberOf(summary, "customFieldsWithUnresolvedType", locale)))
        out.append(metricRow("Fields not counted by budget", numberOf(summary, "issueCountsSkippedByBudget", locale)))
        out.append(metricRow("Suppressed read errors", numberOf(summary, "readErrors", locale)))
        out.append(metricRow("Observations", numberOf(summary, "observations", locale)))
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderModules(List<Map<String, Object>> apps, Locale locale) {
        Map<String, Integer> categories = foldCounts(apps, "categories")
        Map<String, Integer> types = foldCounts(apps, "moduleTypes")
        if (categories.isEmpty() && types.isEmpty()) {
            return ""
        }

        StringBuilder out = new StringBuilder()
        out.append("<h2>Module Landscape</h2>")
        out.append("<p>")
        out.append(Fp.html("Enabled extension modules of every app in this report, folded together. Categories come from an " +
            "ordered heuristic over the descriptor class name, first match wins, so a module appears in one category only."))
        out.append("</p>")

        if (!categories.isEmpty()) {
            out.append("<table><tbody><tr>").append(head("Module Category")).append(head("Enabled Modules")).append("</tr>")
            for (Map.Entry<String, Integer> entry : sortedByCount(categories, 0)) {
                out.append(metricRow(entry.getKey(), Fp.number(entry.getValue(), locale)))
            }
            out.append("</tbody></table>")
        }

        if (!types.isEmpty()) {
            out.append("<table><tbody><tr>").append(head("Module Type")).append(head("Enabled Modules")).append("</tr>")
            for (Map.Entry<String, Integer> entry : sortedByCount(types, MAX_MODULE_TYPES)) {
                out.append(metricRow(entry.getKey(), Fp.number(entry.getValue(), locale)))
            }
            out.append("</tbody></table>")
        }
        return out.toString()
    }

    static String renderApps(List<Map<String, Object>> apps, DecisionRead read, ExportOutcome outcome, Locale locale) {
        StringBuilder out = new StringBuilder()
        out.append("<h2>Apps and Decisions</h2>")
        out.append("<table><tbody><tr>")
        out.append(head("App")).append(head(COL_KEY)).append(head("Vendor")).append(head("Version"))
        out.append(head("Enabled Modules")).append(head("Custom Fields")).append(head("Issue-Field Associations"))
        out.append(head("Screens / Unique")).append(head("Workflows / Active / References"))
        out.append(head("Projects Touched")).append(head("Issues In Reach"))
        out.append(head("Status")).append(head(COL_NOTES)).append(head(COL_DECISION))
        out.append("</tr>")

        for (Map<String, Object> app : apps) {
            String pluginKey = str(app, "pluginKey", "")
            if (pluginKey.isEmpty()) {
                continue
            }

            String decision = read.decisions.get(pluginKey)
            if (decision != null) {
                outcome.decisionsCarried++
            }

            /* State and annotation never share a cell. Built as one list of
             * exceptions, the cell loses the state as soon as a single annotation
             * applies, and an enabled app carrying a diagnostic then reads as if
             * it were not enabled at all. */
            String state = str(app, "state", null)
            String status = flag(app, "enabled") ? "Enabled" : "Disabled"
            if (state != null && !state.equalsIgnoreCase("ENABLED")) {
                status = status + " (state " + state + ")"
            }

            List<String> notes = new ArrayList<String>()
            if (flag(app, "systemProvided")) {
                notes.add("System provided")
            }
            if (!flag(app, "detected")) {
                notes.add("No detectable footprint")
            }
            /* Every per-app diagnostic in this report comes from Fp.note, so all
             * of them are read errors. The observations are instance-wide. */
            if (lng(app, "diagnostics") > 0L) {
                notes.add("Suppressed read errors " + numberOf(app, "diagnostics", locale))
            }

            String associationState = str(app, "associationState", Fp.BUDGET)
            String reachState = str(app, "reachState", Fp.BUDGET)

            out.append("<tr>")
            out.append(cell(Fp.html(str(app, "displayName", pluginKey))))
            out.append(cell(Fp.html(pluginKey)))
            out.append(cell(Fp.html(str(app, "vendor", Fp.NA))))
            out.append(cell(Fp.html(str(app, "version", Fp.NA))))
            out.append(cell(Fp.html(numberOf(app, "enabledModules", locale))))
            out.append(cell(Fp.html(numberOf(app, "customFields", locale))))
            out.append(cell(Fp.html(usageText(associationState, Long.valueOf(lng(app, "issueFieldAssociations")), locale))))
            out.append(cell(Fp.html(numberOf(app, "screenPlacements", locale) + " / " + numberOf(app, "uniqueScreens", locale))))
            out.append(cell(Fp.html(numberOf(app, "workflows", locale) + " / " + numberOf(app, "activeWorkflows", locale) +
                " / " + numberOf(app, "workflowReferences", locale))))
            out.append(cell(Fp.html(usageText(reachState, Long.valueOf(lng(app, "impactedProjects")), locale))))
            out.append(cell(Fp.html(app.get("impactedIssues") == null && reachState == Fp.MEASURED ?
                "n/e" : usageText(reachState, Long.valueOf(lng(app, "impactedIssues")), locale))))
            out.append(cell(Fp.html(status)))
            out.append(cell(Fp.html(notes.isEmpty() ? Fp.NA : String.join(", ", notes))))
            /* A row that carries no decision is seeded with the grey lozenge, a
             * row that carries one keeps its cell verbatim: the Decision column
             * is still never regenerated. */
            out.append(decisionCell(decision == null ? DECISION_SEED : decision))
            out.append("</tr>")
        }

        out.append("</tbody></table>")
        return out.toString()
    }

    /* Decisions whose app is gone are never dropped silently: they are named on
     * the page, in their own table with the same two column headers, so the next
     * run reads them back instead of losing them. */
    static String renderOrphans(DecisionRead read, ExportOutcome outcome) {
        if (outcome.orphanKeys.isEmpty()) {
            return ""
        }
        StringBuilder out = new StringBuilder()
        out.append("<h2>Decisions Without a Matching App</h2>")
        out.append("<p>")
        out.append(Fp.html(String.valueOf(outcome.orphanKeys.size()) + " decision(s) from the previous version of this page could not be matched to an app in this report. " +
            "The app is no longer installed, or the current report options filter it out. The notes are kept here and are read back on the next run."))
        out.append("</p>")
        out.append("<table><tbody><tr>").append(head(COL_KEY)).append(head(COL_DECISION)).append("</tr>")
        for (String key : outcome.orphanKeys) {
            out.append("<tr>").append(cell(Fp.html(key)))
            out.append(decisionCell(read.decisions.get(key)))
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderNotes(Map<String, Object> options, Locale locale) {
        StringBuilder out = new StringBuilder()
        out.append("<h2>Reading This Page</h2><ul>")
        out.append("<li>").append(Fp.html("Everything except the " + COL_DECISION + " column is regenerated on each run. Edits to any other column are lost on the next run.")).append("</li>")
        out.append("<li>").append(Fp.html("The " + COL_DECISION + " column is keyed by " + COL_KEY + ". Write KEEP, REMOVE or free text; the cell is carried over verbatim.")).append("</li>")
        out.append("<li>").append(Fp.html("n/m means not measured and n/e means not evaluated. off means the dimension was switched off for this run. A trailing * marks a lower bound. None of them is a zero.")).append("</li>")
        out.append("<li>").append(Fp.html("Issue-field associations are the sum of issues carrying a non-empty value in an app-owned custom field. That is not a unique issue count.")).append("</li>")
        out.append("<li>").append(Fp.html("Projects Touched is the union of the projects an app reaches through workflow schemes and through screen schemes. " +
            "Issues In Reach counts every issue in those projects, not issues that use the app: it is an upper bound on exposure, not a usage number.")).append("</li>")
        out.append("<li>").append(Fp.html("An app without a detectable footprint can still be in daily use. UI-only, REST-only and runtime-only functionality leaves no configuration trace, and this report measures no requests and no clicks.")).append("</li>")
        out.append("<li>").append(Fp.html("Report options for this run: includeSystem=" + String.valueOf(flag(options, "includeSystem")) +
            ", includeDisabled=" + String.valueOf(flag(options, "includeDisabled")) +
            ", includeDrafts=" + String.valueOf(flag(options, "includeDrafts")) +
            ", includeModules=" + String.valueOf(flag(options, "includeModules")) +
            ", includeReach=" + String.valueOf(flag(options, "includeReach")) +
            ", issueCounts=" + String.valueOf(flag(options, "issueCounts")) +
            ", issueBudgetMs=" + numberOf(options, "issueBudgetMs", locale) + ".")).append("</li>")
        out.append("</ul>")
        return out.toString()
    }

    static ExportOutcome render(Map<String, Object> payload, DecisionRead read, Locale locale) {
        ExportOutcome outcome = new ExportOutcome()
        outcome.decisionsRead = read.decisions.size()

        Map<String, Object> report = sub(payload, "report")
        Map<String, Object> instance = sub(payload, "instance")
        Map<String, Object> options = sub(payload, "options")
        Map<String, Object> summary = sub(payload, "summary")
        List<Map<String, Object>> apps = rowsOf(payload, "apps")

        Set<String> knownKeys = new LinkedHashSet<String>()
        for (Map<String, Object> app : apps) {
            String pluginKey = str(app, "pluginKey", "")
            if (!pluginKey.isEmpty()) {
                knownKeys.add(pluginKey)
            }
        }
        for (String key : read.decisions.keySet()) {
            if (!knownKeys.contains(key)) {
                outcome.orphanKeys.add(key)
            }
        }

        String appTable = renderApps(apps, read, outcome, locale)

        StringBuilder out = new StringBuilder(1 << 16)
        out.append("<p><em>")
        out.append(Fp.html("Generated " + str(report, "generatedAt", Fp.NA) +
            " · report version " + Fp.VERSION +
            " · export marker " + MARKER + " · do not remove this line."))
        out.append("</em></p>")
        out.append("<p>")
        out.append(Fp.html("This page is regenerated by the Jira App Footprint Analysis endpoint. Every column except "))
        out.append("<strong>").append(Fp.html(COL_DECISION)).append("</strong>")
        out.append(Fp.html(" is overwritten on each run. The " + COL_DECISION + " column is read back from this page and carried over."))
        out.append("</p>")

        if (!outcome.orphanKeys.isEmpty()) {
            String warning = String.valueOf(outcome.orphanKeys.size()) + " of " + String.valueOf(outcome.decisionsRead) +
                " decision(s) could not be matched to an app in this report and were moved to \"Decisions Without a Matching App\"."
            outcome.warnings.add(warning)
            out.append("<p><strong>").append(Fp.html("Carry-over warning: ")).append("</strong>").append(Fp.html(warning)).append("</p>")
        }

        out.append(renderInstance(instance, locale))
        out.append(renderSummary(summary, locale))
        out.append(appTable)
        out.append(renderModules(apps, locale))
        out.append(renderOrphans(read, outcome))
        out.append(renderNotes(options, locale))

        outcome.storage = out.toString()
        return outcome
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

    /* The deliberate subset of globalDiagnostics. Nothing failed here: an
     * exhausted budget is something this report states on purpose, and reporting
     * it as a suppressed read error sends an administrator hunting a failure that
     * never happened. */
    List<String> globalObservations = new ArrayList<String>()

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
            Fp.observe(globalDiagnostics, globalObservations, "screen scheme reach -> time budget exhausted after " +
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
    int totalObservations = globalObservations.size()
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

    /* Per-app diagnostics are all read errors here, Fp.note being their only
     * source, so the observations are exactly the global ones. */
    int totalReadErrors = totalDiagnostics - totalObservations

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
                version: Fp.VERSION,
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
                diagnostics: totalDiagnostics,
                readErrors: totalReadErrors,
                observations: totalObservations
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
            observations: globalObservations,
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

    /* ---- Confluence page export: payload and space picker ------------------ */

    /* The page is built from the model the report itself measured, never from the
     * rendered HTML: only the model still knows whether a zero was measured or
     * only not measured. The state per app travels with the figure. */
    List<Map<String, Object>> exportApps = new ArrayList<Map<String, Object>>()
    for (AppFootprint app : apps) {
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("pluginKey", app.pluginKey)
        row.put("displayName", app.displayName)
        row.put("vendor", app.vendor)
        row.put("version", app.version)
        row.put("enabled", Boolean.valueOf(app.enabled))
        row.put("systemProvided", Boolean.valueOf(app.systemProvided))
        row.put("state", app.state)
        row.put("detected", Boolean.valueOf(app.detected))
        row.put("enabledModules", Integer.valueOf(app.enabledModuleCount))
        row.put("categories", app.categoryCounts)
        row.put("moduleTypes", app.moduleTypeCounts)
        row.put("customFields", Integer.valueOf(app.customFields.size()))
        row.put("associationState", PageExport.associationState(app, issueCounts))
        row.put("issueFieldAssociations", Long.valueOf(app.issueFieldAssociations))
        row.put("screenPlacements", Integer.valueOf(app.screenPlacements))
        row.put("uniqueScreens", Integer.valueOf(app.uniqueScreens))
        row.put("workflows", Integer.valueOf(app.workflowCount))
        row.put("activeWorkflows", Integer.valueOf(app.activeWorkflowCount))
        row.put("workflowReferences", Integer.valueOf(app.workflowReferenceCount))
        row.put("reachState", PageExport.reachState(app, includeReach))
        row.put("impactedProjects", Integer.valueOf(app.impactedProjectKeys.size()))
        row.put("impactedIssues", app.impactedIssues)
        row.put("diagnostics", Integer.valueOf(app.diagnosticCount))
        exportApps.add(row)
    }

    Map<String, Object> exportSummary = new LinkedHashMap<String, Object>()
    exportSummary.put("apps", Integer.valueOf(apps.size()))
    exportSummary.put("disabledApps", Integer.valueOf(disabledApps))
    exportSummary.put("appsWithFootprint", Integer.valueOf(appsWithFootprint))
    exportSummary.put("decommissionCandidates", Integer.valueOf(decommissionCandidates.size()))
    exportSummary.put("customFields", Integer.valueOf(totalCustomFields))
    exportSummary.put("associationState", PageExport.summaryState(issueCounts, issueTotalsPartial))
    exportSummary.put("issueFieldAssociations", Long.valueOf(totalIssueFieldAssociations))
    exportSummary.put("screenPlacements", Integer.valueOf(totalScreenPlacements))
    exportSummary.put("workflowReferences", Integer.valueOf(totalWorkflowReferences))
    exportSummary.put("workflowsScanned", Integer.valueOf(scannableWorkflows.size()))
    exportSummary.put("workflowsTotal", Integer.valueOf(workflowSnapshots.size()))
    exportSummary.put("reachState", PageExport.summaryState(includeReach, impactedIssuesPartial))
    exportSummary.put("impactedProjects", Integer.valueOf(allImpactedProjects.size()))
    exportSummary.put("impactedIssues", Long.valueOf(impactedIssuesTotal))
    exportSummary.put("customFieldsTotal", Integer.valueOf(allCustomFields.size()))
    exportSummary.put("customFieldsWithUnresolvedType", Integer.valueOf(unresolvedTypeFields))
    exportSummary.put("issueCountsSkippedByBudget", Integer.valueOf(issueCountsSkippedByBudget))
    exportSummary.put("diagnostics", Integer.valueOf(totalDiagnostics))
    exportSummary.put("readErrors", Integer.valueOf(totalReadErrors))
    exportSummary.put("observations", Integer.valueOf(totalObservations))

    Map<String, Object> exportReport = new LinkedHashMap<String, Object>()
    exportReport.put("name", "Jira App Footprint Analysis")
    exportReport.put("version", Fp.VERSION)
    exportReport.put("generatedAt", generatedAt)

    Map<String, Object> exportModel = new LinkedHashMap<String, Object>()
    exportModel.put("report", exportReport)
    exportModel.put("instance", instanceInfo)
    exportModel.put("options", optionsInfo)
    exportModel.put("summary", exportSummary)
    exportModel.put("apps", exportApps)
    String exportPayload = Fp.html(JsonOutput.toJson(exportModel))

    /* No space picker is built here, and no application link is read. Rendering
     * this report performs no outbound call at all: everything the export form
     * needs is fetched on demand by the POST branch once the button is pressed. */

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
.diag-info { background: var(--blue-soft); border-color: var(--purple-border); }
.diag-warn { background: var(--yellow-soft); border-color: var(--yellow-border); }
.diag ul { margin: 8px 0 0; padding-left: 20px; }
.diag li { margin: 2px 0; }

/* confluence page export */
.export-card {
    background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
    padding: 14px 18px; margin-bottom: 18px; box-shadow: var(--shadow);
}
.export-title { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
.export-note { color: var(--text-subtle); font-size: 13px; max-width: 1080px; }
.export-grid { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin-top: 12px; }
.export-field {
    display: flex; flex-direction: column; gap: 4px; color: var(--text-subtle);
    font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .035em;
}
.export-field input, .export-field select {
    height: 34px; padding: 0 10px; border: 1px solid var(--border); border-radius: 4px;
    background: var(--surface); color: var(--text); font-size: 14px; font-weight: 400;
    text-transform: none; letter-spacing: 0;
}
.export-field select { min-width: 300px; }
.export-field input.wide { min-width: 320px; }
.export-card button.button { cursor: pointer; height: 34px; }
.export-card button.button[disabled] { opacity: .55; cursor: not-allowed; }
.export-status { margin-top: 10px; font-size: 12px; }
.export-settings { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-subtle); }
.export-stage { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border-subtle); }
.export-chosen { align-self: flex-end; padding-bottom: 8px; color: var(--text-subtle); font-size: 12px; }
.export-results { margin-top: 8px; max-width: 680px; }
.export-hit {
    display: block; width: 100%; margin-bottom: 4px; padding: 6px 10px; text-align: left;
    border: 1px solid var(--border); border-radius: 4px; background: var(--surface-subtle);
    color: var(--text); font-size: 13px; cursor: pointer;
}
.export-hit:hover { border-color: var(--blue); background: var(--blue-soft); }
.export-empty { color: var(--text-subtle); font-size: 12px; font-style: italic; }

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

    if (Fp.diagBoxShown(unresolvedTypeFields, totalDiagnostics, issueCountsSkippedByBudget, screenReachTruncated)) {

        html.append("""<div class="${Fp.diagClass(unresolvedTypeFields, issueCountsSkippedByBudget, screenReachTruncated, totalReadErrors)}">
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
        if (totalReadErrors > 0) {
            html.append("""        <li>
            ${num(totalReadErrors)} read(s) failed and were suppressed. Affected apps carry a
            <span class="badge badge-diag">DIAGNOSTICS</span> badge, the details are inside the app card.
        </li>
""")
        }
        for (String entry : Fp.readErrorsOf(globalDiagnostics, globalObservations)) {
            html.append("        <li class=\"mono\">" + esc(entry) + "</li>\n")
        }
        if (totalObservations > 0) {
            html.append("""        <li>
            ${num(totalObservations)} observation(s) were recorded. Nothing failed and nothing was
            suppressed: these are statements this report makes on purpose.
        </li>
""")
        }
        for (String entry : globalObservations) {
            html.append("        <li class=\"mono\">" + esc(entry) + "</li>\n")
        }

        html.append("""    </ul>
</div>
""")
    }

    /* ---- Confluence page export card --------------------------------------- */

    /* One button and nothing else. The settings area below it stays empty until
     * the click, because filling it would mean calling Confluence for a report
     * nobody is exporting. Each stage unhides the next one. */

    html.append("""<div class="export-card">
    <div class="export-title">Export to Confluence</div>
    <div class="export-note">
        Writes an executive summary of this report into a Confluence page over a Jira application link and
        updates that same page on every later run. The <strong>Decision</strong> column stays untouched: it is
        read back from the existing page and carried over verbatim. If that read fails, nothing is written at all.
        A page that does not carry this export's marker is never overwritten. Nothing is read from Confluence
        until the button below is pressed.
    </div>
    <div class="export-grid">
        <button id="exportOpen" class="button" type="button" onclick="openExport()">Export to Confluence</button>
    </div>
    <div id="exportSettings" class="export-settings hidden">
        <div class="export-grid">
            <label class="export-field">Target Confluence
                <select id="exportTarget" onchange="targetChosen()"></select>
            </label>
            <div class="export-chosen" id="exportTargetNote">Reading the application links...</div>
        </div>
        <div id="exportSpaceStage" class="export-stage hidden">
            <div class="export-grid">
                <label class="export-field">Space - search by name or key
                    <input id="exportSpaceQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${PageExport.MIN_SEARCH_CHARS} characters..." oninput="searchSpaces()"
                           onkeydown="pickFirstHit(event, 'exportSpaceResults')">
                </label>
                <div class="export-chosen" id="exportSpaceChosen">No space selected.</div>
            </div>
            <div id="exportSpaceResults" class="export-results"></div>
        </div>
        <div id="exportPageStage" class="export-stage hidden">
            <div class="export-grid">
                <label class="export-field">Parent page - search by title (optional)
                    <input id="exportParentQuery" class="wide" type="search" autocomplete="off"
                           placeholder="Type at least ${PageExport.MIN_SEARCH_CHARS} characters..." oninput="parentTyped()"
                           onkeydown="pickFirstHit(event, 'exportParentResults')">
                </label>
                <div class="export-chosen" id="exportParentChosen">No parent page: the page is created at the top level of the space.</div>
            </div>
            <div id="exportParentResults" class="export-results"></div>
            <div class="export-grid">
                <label class="export-field">Page title
                    <input id="exportTitle" class="wide" type="text" value="${esc(PageExport.DEFAULT_TITLE)}">
                </label>
                <button id="exportRun" class="button" type="button" onclick="exportToConfluence()">Generate Confluence Page</button>
            </div>
        </div>
    </div>
    <div id="exportStatus" class="export-status muted">Not written yet.</div>
    <input id="exportPayload" type="hidden" value="${exportPayload}">
    <input id="exportSpace" type="hidden" value="">
    <input id="exportParent" type="hidden" value="">
</div>
""")

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
        <li>The measurement is read-only: it performs no write in Jira. The Confluence page export is the only
            outbound path in this report, and rendering this page uses none of it. The application links, the
            space list and the page search are read only after <strong>Export to Confluence</strong> has been
            pressed, and exactly one Confluence page is written, only when the page is generated.</li>
    </ul>
    Report version ${Fp.VERSION} &nbsp;&middot;&nbsp; execution time ${num(executionMs)} ms &nbsp;&middot;&nbsp;
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
/* The export is staged: nothing above ran a lookup, so every stage below asks the
   POST branch of this same endpoint for exactly what it needs, and no further. */
var exportSpaceList = [];
var exportPageSeq = 0;
var exportPageTimer = null;

function el(id) { return document.getElementById(id); }
function say(cssClass, text) {
    var node = el('exportStatus');
    node.className = 'export-status ' + cssClass;
    node.textContent = text;
    return node;
}
function exportPost(payload) {
    return fetch(window.location.pathname, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
        body: JSON.stringify(payload)
    }).then(function (response) {
        return response.json().then(function (parsed) { return { ok: response.ok, status: response.status, body: parsed }; });
    });
}
function hit(label, onPick) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'export-hit';
    button.textContent = label;
    button.onclick = onPick;
    return button;
}
function emptyNote(text) {
    var note = document.createElement('div');
    note.className = 'export-empty';
    note.textContent = text;
    return note;
}
/* Enter picks the first hit. The results are buttons in document order, so the
   first one in the box is the first match. Enter with no hit does nothing, the
   default is always suppressed so Enter can never submit or reload the page, and
   picking a hit with the mouse keeps working unchanged. */
function pickFirstHit(event, boxId) {
    if (event.key !== 'Enter') { return; }
    event.preventDefault();
    var first = el(boxId).querySelector('.export-hit');
    if (first) { first.click(); }
}

/* Stage 1. The first lookup of the whole report: which Confluence links exist. */
function openExport() {
    el('exportOpen').disabled = true;
    el('exportSettings').classList.remove('hidden');
    say('muted', 'Reading the Confluence application links...');
    exportPost({ action: 'links' }).then(function (result) {
        var body = result.body || {};
        var select = el('exportTarget');
        if (!result.ok || body.ok !== true) {
            el('exportSettings').classList.add('hidden');
            el('exportOpen').disabled = false;
            say('bad', body.error || 'The Confluence application links could not be read.');
            return;
        }
        var links = body.links || [];
        select.innerHTML = '';
        if (links.length > 1) { select.appendChild(new Option('Select a Confluence...', '')); }
        for (var i = 0; i < links.length; i++) {
            var label = links[i].name + (links[i].primary ? ' (primary)' : '') +
                (links[i].displayUrl ? ' - ' + links[i].displayUrl : '');
            var option = new Option(label, links[i].id);
            if (links[i].primary || links.length === 1) { option.selected = true; }
            select.appendChild(option);
        }
        el('exportTargetNote').textContent = links.length === 1
            ? 'One Confluence application link, preselected.'
            : String(links.length) + ' Confluence application links configured.';
        say('muted', 'Pick the target Confluence, then the space.');
        targetChosen();
    }).catch(function (error) {
        el('exportOpen').disabled = false;
        say('bad', 'The Confluence application links could not be read: ' + error);
    });
}

/* Stage 2. A target was picked, so that target's spaces may be listed. */
function targetChosen() {
    el('exportSpace').value = '';
    el('exportSpaceQuery').value = '';
    el('exportSpaceResults').innerHTML = '';
    el('exportSpaceChosen').textContent = 'No space selected.';
    el('exportPageStage').classList.add('hidden');
    exportSpaceList = [];
    if (!el('exportTarget').value) { el('exportSpaceStage').classList.add('hidden'); return; }
    el('exportSpaceStage').classList.remove('hidden');
    say('muted', 'Reading the spaces of the selected Confluence...');
    exportPost({ action: 'spaces', applicationLinkId: el('exportTarget').value }).then(function (result) {
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            el('exportSpaceStage').classList.add('hidden');
            say('bad', body.error || 'The Confluence space list could not be read.');
            return;
        }
        exportSpaceList = body.spaces || [];
        say('muted', String(exportSpaceList.length) + ' space(s) available' +
            (body.truncated === true ? ', and the list is truncated - the instance has more' : '') +
            '. Type at least ${PageExport.MIN_SEARCH_CHARS} characters to search by name or key.');
    }).catch(function (error) {
        el('exportSpaceStage').classList.add('hidden');
        say('bad', 'The Confluence space list could not be read: ' + error);
    });
}

/* Stage 3a. Search, not a dropdown: only matches are ever put into the page. */
function searchSpaces() {
    var query = el('exportSpaceQuery').value.trim().toLowerCase();
    var box = el('exportSpaceResults');
    box.innerHTML = '';
    if (query.length < ${PageExport.MIN_SEARCH_CHARS}) { return; }
    var shown = 0;
    for (var i = 0; i < exportSpaceList.length && shown < ${PageExport.SEARCH_LIMIT}; i++) {
        var space = exportSpaceList[i];
        if (space.name.toLowerCase().indexOf(query) < 0 && space.key.toLowerCase().indexOf(query) < 0) { continue; }
        box.appendChild(hit(space.name + '  (' + space.key + ')', chooseSpace(space)));
        shown++;
    }
    if (shown === 0) { box.appendChild(emptyNote('No space matches "' + query + '".')); }
}
function chooseSpace(space) {
    return function () {
        el('exportSpace').value = space.key;
        el('exportSpaceQuery').value = space.name;
        el('exportSpaceResults').innerHTML = '';
        el('exportSpaceChosen').textContent = 'Space: ' + space.name + ' (' + space.key + ')';
        /* A parent search that is still running belongs to the previous space, so
           it is discarded here as well - otherwise its answer would drop a list of
           foreign pages into the field of the space just picked. */
        if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
        exportPageSeq++;
        el('exportParent').value = '';
        el('exportParentQuery').value = '';
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'No parent page: the page is created at the top level of the space.';
        el('exportPageStage').classList.remove('hidden');
        say('muted', 'Space ' + space.key + ' selected. Pick a parent page or leave it empty, then generate.');
    };
}

/* Stage 3b. The parent field has no button: typing is what starts the search,
   after a short idle pause rather than on every keystroke. The list that comes
   back STAYS until an entry is picked or the field falls below the minimum - a
   list that disappears while it is being read cannot confirm anything, which is
   what made the previous version unusable. Out-of-order answers are dropped, so
   a slow answer to an older term never replaces the list of the current one. */
function parentTyped() {
    /* Editing the term drops the picked parent, so a stale id can never travel
       with a title the administrator has since changed. What travels then is the
       title, and the generating run adopts or creates that page. */
    el('exportParent').value = '';
    var query = el('exportParentQuery').value.trim();
    el('exportParentChosen').textContent = query
        ? 'Parent page "' + query + '": pick it below if it is listed, otherwise it is created when the page is generated.'
        : 'No parent page: the page is created at the top level of the space.';
    if (exportPageTimer) { window.clearTimeout(exportPageTimer); }
    if (query.length < ${PageExport.MIN_SEARCH_CHARS}) {
        /* Bumping the sequence here discards an answer that is still in flight,
           so an empty field never fills back up on its own. */
        exportPageSeq++;
        el('exportParentResults').innerHTML = '';
        return;
    }
    exportPageTimer = window.setTimeout(searchParents, ${PageExport.SEARCH_IDLE_MS});
}
function searchParents() {
    var query = el('exportParentQuery').value.trim();
    var box = el('exportParentResults');
    if (query.length < ${PageExport.MIN_SEARCH_CHARS}) { box.innerHTML = ''; return; }
    var seq = ++exportPageSeq;
    exportPost({
        action: 'pages',
        applicationLinkId: el('exportTarget').value,
        spaceKey: el('exportSpace').value,
        query: query
    }).then(function (result) {
        if (seq !== exportPageSeq) { return; }
        var body = result.body || {};
        box.innerHTML = '';
        if (!result.ok || body.ok !== true) {
            box.appendChild(emptyNote(body.error || 'The page search failed.'));
            return;
        }
        var pages = body.pages || [];
        if (pages.length === 0) {
            box.appendChild(emptyNote('Not found - will be created'));
            return;
        }
        for (var i = 0; i < pages.length; i++) {
            box.appendChild(hit(pages[i].title + '  #' + pages[i].id, chooseParent(pages[i])));
        }
        if (body.truncated === true) {
            box.appendChild(emptyNote('More pages match than are listed here. Type more of the title to narrow it down.'));
        }
    }).catch(function (error) {
        if (seq === exportPageSeq) {
            box.innerHTML = '';
            box.appendChild(emptyNote('The page search failed: ' + error));
        }
    });
}
function chooseParent(page) {
    return function () {
        el('exportParent').value = page.id;
        el('exportParentQuery').value = page.title;
        el('exportParentResults').innerHTML = '';
        el('exportParentChosen').textContent = 'Parent page: ' + page.title + ' (id ' + page.id + ')';
    };
}

/* Stage 4. The write. */
function exportToConfluence() {
    var button = el('exportRun');
    function fail(text) { say('bad', text); }
    var payload;
    try { payload = JSON.parse(el('exportPayload').value); }
    catch (error) { fail('Export payload could not be read: ' + error); return; }
    payload.applicationLinkId = el('exportTarget').value;
    payload.spaceKey = el('exportSpace').value;
    /* Either the id of a page that was picked, or the title that was typed and
       never picked - never both. The server refuses a request that carries two
       parent instructions, so the choice is made here and only here. */
    payload.parentPageId = el('exportParent').value.trim();
    payload.parentTitle = payload.parentPageId ? '' : el('exportParentQuery').value.trim();
    payload.title = el('exportTitle').value.trim();
    if (!payload.applicationLinkId) { fail('Select the target Confluence first.'); return; }
    if (!payload.spaceKey) { fail('Select a space first.'); return; }
    if (!payload.title) { fail('Enter a page title first.'); return; }
    button.disabled = true;
    say('muted', 'Writing page...');
    exportPost(payload).then(function (result) {
        button.disabled = false;
        var body = result.body || {};
        if (!result.ok || body.ok !== true) {
            fail('Nothing was written (' + result.status + '): ' + (body.error || 'unknown error'));
            return;
        }
        var version = body.pageVersion === null ? 'unknown' : body.pageVersion;
        /* Found and created are reported apart. An administrator who reads
           "found" believes the parent was already there and stops looking for
           the page this run has just made. */
        var parent = '';
        if (body.parentAction === 'created') {
            parent = ' Parent page created: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        } else if (body.parentAction === 'found') {
            parent = ' Parent page found: "' + body.parentTitle + '" (id ' + body.parentPageId + ').';
        }
        /* A parent that was named and not applied is said plainly, and the line
           stops reading as a plain success. A silent mismatch is the worst outcome
           here: the run looks like it worked and the report is not where it was
           put. The three states are compared as strings on purpose - "unknown" is
           not a failure and is never reported as one. */
        var tone = 'good';
        if (body.parentApplied === 'false') {
            tone = 'bad';
            parent += ' PARENT NOT APPLIED. ' +
                (body.parentAppliedReason || 'The page was not moved under the parent page.');
        } else if (body.parentApplied === 'unknown') {
            tone = 'warn';
            parent += ' PARENT NOT CONFIRMED. ' +
                (body.parentAppliedReason || 'The position could not be read back.');
        }
        var status = say(tone, 'Page ' + body.action + ': "' + body.title + '" in ' + body.spaceKey +
            ' (version ' + version + '). Decision read: ' + body.decisionRead +
            ', carried over: ' + body.decisionsCarried + ' of ' + body.decisionsRead +
            ', without a matching app: ' + body.orphanedDecisions + '.' + parent);
        if (body.pageUrl) {
            var link = document.createElement('a');
            link.href = body.pageUrl;
            link.target = '_blank';
            link.rel = 'noopener';
            link.textContent = ' Open the page';
            status.appendChild(link);
        }
    }).catch(function (error) {
        button.disabled = false;
        fail('Request failed, nothing was written: ' + error);
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


/* =============================================================================
 * Confluence application link transport
 *
 * The only outbound path in this file. A Jira JVM holds no Confluence type, so
 * the space list, the existence check and the write all travel over the Jira
 * application link. Every helper here returns a result map and never throws:
 * a failed call must arrive at the caller as a failure, never as an empty
 * answer that a later branch could mistake for "nothing there".
 * ========================================================================== */

/* The concrete Confluence application type, resolved at runtime instead of being
 * imported. The applinks API documents
 * com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType as a
 * public interface extending ApplicationType since applinks 3.0, and that is what
 * makes the typed getApplicationLinks(Class) and getPrimaryApplicationLink(Class)
 * usable here instead of a class-name string comparison. Whether ScriptRunner
 * exposes that sub-package on a given instance is NOT documented, so a missing
 * class degrades to the old simple-name match rather than breaking the endpoint -
 * the same runtime resolution the JAX-RS Response class uses above. */
Class<? extends ApplicationType> confluenceApplicationType() {
    try {
        return (Class<? extends ApplicationType>)
            Class.forName("com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType")
    } catch (Throwable ignored) {
        return null
    }
}

/* Reading one link, each getter guarded on its own: a half-configured link must
 * shorten the label, not abort the list. */
String confluenceLinkId(ApplicationLink link) {
    try {
        return link == null || link.getId() == null ? null : link.getId().get()
    } catch (Exception ignored) {
        return null
    }
}

String confluenceLinkName(ApplicationLink link) {
    try {
        String name = link == null ? null : link.getName()
        return name == null || name.trim().isEmpty() ? "Confluence" : name
    } catch (Exception ignored) {
        return "Confluence"
    }
}

String confluenceLinkUrl(ApplicationLink link) {
    try {
        Object url = link == null ? null : link.getDisplayUrl()
        return url == null ? null : url.toString()
    } catch (Exception ignored) {
        return null
    }
}

/* Every Confluence application link this Jira has, primary first, then by name.
 *
 * This used to return the first link whose type simple-name matched, silently. On
 * an instance with two Confluence links the export could write to the wrong site
 * with no way to tell, so the administrator now picks the target and the picked id
 * travels with every later stage of the same export.
 *
 * The ApplicationId is passed inside one export cycle only and is NEVER persisted.
 * The applinks documentation states on getId() that the id changes when an
 * administrator upgrades the remote application to use Unified Application Links,
 * and that a plugin storing the id has to listen for ApplicationLinksIDChangedEvent.
 * Nothing here outlives the request, so no listener is needed - do not start
 * storing this id without adding that listener. */
Map<String, Object> confluenceApplicationLinks() {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<ApplicationLink> links = new ArrayList<ApplicationLink>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("links", links)
    result.put("primaryId", null)
    result.put("typed", Boolean.FALSE)
    result.put("seen", Integer.valueOf(-1))
    result.put("seenTypes", "")

    ApplicationLinkService service = null
    try {
        service = ComponentLocator.getComponent(ApplicationLinkService)
    } catch (Exception error) {
        result.put("error", "The application link service could not be read (" + PageExport.errorDetail(error) + ").")
        return result
    }
    if (service == null) {
        result.put("error", "The application link service is not available in this Jira instance.")
        return result
    }

    Class<? extends ApplicationType> type = confluenceApplicationType()
    result.put("typed", Boolean.valueOf(type != null))
    String primaryId = null

    try {
        if (type != null) {
            for (ApplicationLink link : service.getApplicationLinks(type)) {
                if (link != null) {
                    links.add(link)
                }
            }
            try {
                primaryId = confluenceLinkId(service.getPrimaryApplicationLink(type))
            } catch (Exception ignored) {
                primaryId = null
            }
        }

        /* The typed lookup only works if this script and the applinks plugin resolve
         * the very same ConfluenceApplicationType class. Across OSGi class loaders
         * that is not guaranteed, and a mismatch returns an EMPTY LIST rather than an
         * error, which reads as "no link configured" on an instance that plainly has
         * one. Measured on jira-test 2026-08-22. So whenever the typed lookup finds
         * nothing, fall back to the untyped scan that confluence-addon-analysis.groovy
         * has used in production for years. The reported flag follows the path that
         * actually produced the list, not the one that was attempted. */
        if (links.isEmpty()) {
            result.put("typed", Boolean.FALSE)
            primaryId = null
            int seen = 0
            Set<String> seenTypes = new TreeSet<String>()
            for (ApplicationLink link : service.getApplicationLinks()) {
                if (link == null) {
                    continue
                }
                seen++
                String typeName = link.getType() == null ? "unknown" : link.getType().getClass().getSimpleName()
                seenTypes.add(typeName)
                if (typeName == "ConfluenceApplicationTypeImpl" || typeName.contains("Confluence")) {
                    links.add(link)
                }
            }
            /* What the instance actually offered, so a refusal is a measurement and
             * not a dead end. Without this the administrator is told to create a link
             * that may already exist. */
            result.put("seen", Integer.valueOf(seen))
            result.put("seenTypes", seenTypes.isEmpty() ? "none" : seenTypes.join(", "))
        }
    } catch (Exception error) {
        result.put("error", "The Confluence application links could not be listed (" +
            PageExport.errorDetail(error) + ").")
        return result
    }

    /* Without the typed lookup the primary comes from the link itself, which the
     * applinks API documents as isPrimary(). */
    if (primaryId == null) {
        for (ApplicationLink link : links) {
            boolean primary = false
            try {
                primary = link.isPrimary()
            } catch (Exception ignored) {
                primary = false
            }
            if (primary) {
                primaryId = confluenceLinkId(link)
                break
            }
        }
    }

    final String preselected = primaryId
    links.sort { ApplicationLink a, ApplicationLink b ->
        boolean aPrimary = preselected != null && preselected == confluenceLinkId(a)
        boolean bPrimary = preselected != null && preselected == confluenceLinkId(b)
        if (aPrimary != bPrimary) {
            return aPrimary ? -1 : 1
        }
        return confluenceLinkName(a).compareToIgnoreCase(confluenceLinkName(b))
    }

    result.put("ok", Boolean.TRUE)
    result.put("primaryId", primaryId)
    return result
}

/* The target the administrator picked, plus its request factory. The id is matched
 * inside the Confluence list rather than handed to getApplicationLink(ApplicationId),
 * so a request naming a link of any other type resolves to nothing instead of to a
 * foreign target. Every stage - space search, page search and write - resolves the
 * same way, so they cannot disagree about where they are pointing. */
Map<String, Object> confluenceTarget(List<ApplicationLink> links, String applicationLinkId) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("link", null)
    result.put("factory", null)

    if (applicationLinkId == null || applicationLinkId.trim().isEmpty()) {
        result.put("error", "No target Confluence was selected, so there is nowhere to look and nothing is written.")
        return result
    }

    ApplicationLink link = null
    for (ApplicationLink candidate : links) {
        String id = confluenceLinkId(candidate)
        if (id != null && id == applicationLinkId) {
            link = candidate
            break
        }
    }
    if (link == null) {
        result.put("error", "The selected Confluence application link is not among this instance's Confluence links. " +
            "Reopen the export and pick the target again. Nothing is written.")
        return result
    }

    ApplicationLinkRequestFactory factory = null
    try {
        factory = link.createAuthenticatedRequestFactory()
    } catch (Exception error) {
        result.put("error", "The Confluence application link \"" + confluenceLinkName(link) + "\" did not hand out an " +
            "authenticated request factory (" + PageExport.errorDetail(error) + "). Nothing is written.")
        return result
    }
    if (factory == null) {
        result.put("error", "The Confluence application link \"" + confluenceLinkName(link) + "\" did not hand out an " +
            "authenticated request factory. Nothing is written.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("link", link)
    result.put("factory", factory)
    return result
}

/* Browse URL of a written page, built from the link's own address. */
String confluencePageUrl(ApplicationLink link, String pageId) {
    if (link == null || pageId == null || pageId.trim().isEmpty()) {
        return null
    }
    Object base = null
    try {
        base = link.getDisplayUrl()
    } catch (Exception ignored) {
        base = null
    }
    if (base == null) {
        try {
            base = link.getRpcUrl()
        } catch (Exception ignored) {
            return null
        }
    }
    if (base == null) {
        return null
    }
    String prefix = base.toString()
    while (prefix.endsWith("/")) {
        prefix = prefix.substring(0, prefix.length() - 1)
    }
    return prefix + "/pages/viewpage.action?pageId=" + pageId
}

/* One authenticated call. The three failure modes an administrator actually
 * meets are separated: no factory, no authorisation for the impersonated user,
 * and a refusal from Confluence itself. */
Map<String, Object> confluenceCall(ApplicationLinkRequestFactory factory, Request.MethodType method, String url, String jsonBody) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("json", null)

    if (factory == null) {
        result.put("error", "The Confluence application link exists but did not hand out an authenticated request factory.")
        return result
    }

    String raw = null
    try {
        def request = factory.createRequest(method, url)
        request.addHeader("Accept", "application/json")
        if (jsonBody != null && !jsonBody.isEmpty()) {
            request.addHeader("Content-Type", "application/json")
            request.setRequestBody(jsonBody)
        }
        raw = request.execute()
    } catch (CredentialsRequiredException ignored) {
        result.put("error", "Confluence did not accept the impersonated call: this Jira user has not authorised the " +
            "Confluence application link yet. Authorise it once from a page that offers the link's authentication " +
            "prompt, then run the export again.")
        return result
    } catch (Exception error) {
        String detail = PageExport.errorDetail(error)
        String message = "The call to Confluence failed for " + url + " (" + detail + ")."
        /* A 401 over an application link is almost never a wrong path. This factory
         * impersonates the calling Jira user on the Confluence side, so the usual
         * cause is that this user has no account there, or none with permission.
         * Measured on a customer instance 2026-08-22: link found and preselected,
         * space call 401, user did not exist in Confluence. Saying so turns a dead
         * end into the next step. */
        if (detail != null && detail.contains("401")) {
            message = message + " A 401 here means Confluence refused the call, not that the address was wrong. " +
                "The export calls Confluence as the Jira user who runs it, so check that this user exists in " +
                "Confluence and may read spaces there."
        }
        result.put("error", message)
        return result
    }

    if (raw == null || raw.trim().isEmpty()) {
        result.put("error", "Confluence returned an empty response for " + url + ".")
        return result
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(raw)
    } catch (Exception error) {
        result.put("error", "Confluence returned a response that is not JSON for " + url +
            " (" + PageExport.errorDetail(error) + ").")
        return result
    }
    if (!(parsed instanceof Map)) {
        result.put("error", "Confluence returned a JSON value that is not an object for " + url + ".")
        return result
    }

    /* A Confluence REST refusal carries statusCode and message in the body. It is
     * not documented that execute() throws on a 4xx, so the body is inspected as
     * well - a refusal must never pass as a successful empty answer. */
    Map<String, Object> json = PageExport.copyMap((Map<?, ?>) parsed)
    Object statusCode = json.get("statusCode")
    if (statusCode instanceof Number && ((Number) statusCode).intValue() >= 400) {
        result.put("error", "Confluence refused the call to " + url + " with HTTP " +
            String.valueOf(((Number) statusCode).intValue()) + ": " + PageExport.str(json, "message", "no message"))
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("json", json)
    return result
}

/* Every current space, paged. GET /rest/api/space is documented with start and
 * limit; the loop stops when a page comes back shorter than the page size, and
 * the page cap keeps a changed paging contract from turning into an endless
 * loop. A truncated list says so rather than looking complete. */
Map<String, Object> confluenceSpaces(ApplicationLinkRequestFactory factory) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<Map<String, Object>> spaces = new ArrayList<Map<String, Object>>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("spaces", spaces)
    result.put("truncated", Boolean.FALSE)

    Set<String> seen = new HashSet<String>()
    int start = 0

    for (int page = 0; page < PageExport.MAX_SPACE_PAGES; page++) {
        String url = "/rest/api/space?status=current&limit=" + String.valueOf(PageExport.SPACE_PAGE_SIZE) +
            "&start=" + String.valueOf(start)
        Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
        if (call.get("ok") != Boolean.TRUE) {
            result.put("error", call.get("error"))
            return result
        }

        List<Map<String, Object>> rows = PageExport.rowsOf((Map<String, Object>) call.get("json"), "results")
        for (Map<String, Object> row : rows) {
            String key = PageExport.str(row, "key", "")
            if (key.isEmpty() || !seen.add(key)) {
                continue
            }
            Map<String, Object> space = new LinkedHashMap<String, Object>()
            space.put("key", key)
            space.put("name", PageExport.str(row, "name", key))
            spaces.add(space)
        }

        if (rows.size() < PageExport.SPACE_PAGE_SIZE) {
            result.put("ok", Boolean.TRUE)
            return result
        }
        start += PageExport.SPACE_PAGE_SIZE
    }

    result.put("ok", Boolean.TRUE)
    result.put("truncated", Boolean.TRUE)
    return result
}

/* The parent page, resolved and located. A parent that does not exist or sits in
 * another space is refused before anything is written. */
Map<String, Object> confluenceParentPage(ApplicationLinkRequestFactory factory, String parentId) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("spaceKey", null)
    result.put("title", null)

    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET,
        "/rest/api/content/" + parentId + "?expand=space", null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page " + parentId + " could not be read from Confluence: " +
            String.valueOf(call.get("error")))
        return result
    }

    Map<String, Object> json = (Map<String, Object>) call.get("json")
    String id = PageExport.str(json, "id", "")
    if (id.isEmpty()) {
        result.put("error", "There is no Confluence page with the ID " + parentId + ".")
        return result
    }

    String parentSpace = PageExport.str(PageExport.sub(json, "space"), "key", "")
    if (parentSpace.isEmpty()) {
        result.put("error", "The space of the parent page " + parentId + " could not be read, so its location " +
            "cannot be confirmed. Nothing is written.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("spaceKey", parentSpace)
    result.put("title", PageExport.str(json, "title", ""))
    return result
}

/* Parent page candidates, searched by title inside one space, so the administrator
 * never has to look up a raw page id. GET /rest/api/content/search takes a cql
 * query; the CQL reference documents the fields type, space and title, documents
 * "~" (CONTAINS) on title, and documents "*" as the multi-character wildcard that
 * must not be the first character of a term. The answer is the same paginated
 * content collection the existence check already reads, so results is parsed the
 * same way. An empty result set is an empty result set; every failure carries its
 * reason instead. */
Map<String, Object> confluenceSearchPages(ApplicationLinkRequestFactory factory, String spaceKey, String query) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("pages", pages)
    result.put("truncated", Boolean.FALSE)

    /* The space key is validated, not sanitised. It used to run through cqlTerm()
     * exactly like the search term below, which cost every personal space its
     * leading tilde and turned the search into one over a space that does not
     * exist - answered with zero hits and no error. Only the title is a search
     * term and only the title is still cleaned. */
    String space = spaceKey == null ? "" : spaceKey.trim()
    String spaceProblem = PageExport.spaceKeyProblem(space)
    if (!spaceProblem.isEmpty()) {
        result.put("error", "The space key \"" + String.valueOf(spaceKey) + "\" cannot be searched in: " +
            spaceProblem + ".")
        return result
    }
    String term = PageExport.cqlTerm(query)
    if (term.isEmpty()) {
        result.put("error", "The search term holds nothing that can be searched for.")
        return result
    }

    String cql = "type=page and space=\"" + space + "\" and title~\"" + term + "*\""
    String url = "/rest/api/content/search?limit=" + String.valueOf(PageExport.SEARCH_LIMIT) +
        "&cql=" + URLEncoder.encode(cql, "UTF-8")
    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The page search in \"" + spaceKey + "\" failed: " + String.valueOf(call.get("error")))
        return result
    }

    List<Map<String, Object>> rows = PageExport.rowsOf((Map<String, Object>) call.get("json"), "results")
    /* The request carries a limit, so a full page of hits means there may be more.
     * Saying so is the point: a silently cut list reads as "that is everything". */
    if (rows.size() >= PageExport.SEARCH_LIMIT) {
        result.put("truncated", Boolean.TRUE)
    }
    for (Map<String, Object> row : rows) {
        String id = PageExport.str(row, "id", "")
        String title = PageExport.str(row, "title", "")
        if (id.isEmpty() || title.isEmpty()) {
            continue
        }
        Map<String, Object> page = new LinkedHashMap<String, Object>()
        page.put("id", id)
        page.put("title", title)
        pages.add(page)
    }
    pages.sort { Map<String, Object> a, Map<String, Object> b ->
        return PageExport.str(a, "title", "").compareToIgnoreCase(PageExport.str(b, "title", ""))
    }

    result.put("ok", Boolean.TRUE)
    return result
}

/* The parent page named by a title: adopted when it already exists, created when
 * it does not. There is no Create button - this runs as part of the generating
 * request, which is the only moment at which the answer is still current.
 *
 * The exact-title check sits here, immediately before the create, and not only in
 * the search the browser ran earlier. That covers the page somebody else created
 * in between and the administrator who saw a hit, did not click it and generated
 * anyway. Neither produces a second page carrying the same title.
 *
 * created=true is set on the create path only, so the caller can report finding
 * and creating apart. A failed lookup carries its reason and never degrades into
 * "no such page", which the caller would answer by creating a duplicate. The
 * 401 hint about a Jira user without a Confluence account arrives through
 * confluenceCall, the same path the export itself uses. */
Map<String, Object> confluenceParentByTitle(ApplicationLinkRequestFactory factory, String spaceKey, String title) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("id", null)
    result.put("created", Boolean.FALSE)

    String lookupUrl = "/rest/api/content?type=page&spaceKey=" + URLEncoder.encode(spaceKey, "UTF-8") +
        "&title=" + URLEncoder.encode(title, "UTF-8") + "&limit=2"
    Map<String, Object> lookup = confluenceCall(factory, Request.MethodType.GET, lookupUrl, null)
    if (lookup.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page \"" + title + "\" could not be looked up in \"" + spaceKey + "\": " +
            String.valueOf(lookup.get("error")) + " That is a failed read, not a space without that page, so " +
            "nothing was created.")
        return result
    }

    List<Map<String, Object>> rows = PageExport.rowsOf((Map<String, Object>) lookup.get("json"), "results")
    if (!rows.isEmpty()) {
        String existingId = PageExport.str(rows.get(0), "id", "")
        if (existingId.isEmpty()) {
            result.put("error", "Confluence named a page \"" + title + "\" in \"" + spaceKey +
                "\" but gave no id for it, so it cannot be used as a parent.")
            return result
        }
        result.put("ok", Boolean.TRUE)
        result.put("id", existingId)
        return result
    }

    Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
    spacePayload.put("key", spaceKey)

    Map<String, Object> storageBody = new LinkedHashMap<String, Object>()
    storageBody.put("value", PageExport.PARENT_BODY)
    storageBody.put("representation", "storage")

    Map<String, Object> bodyPayload = new LinkedHashMap<String, Object>()
    bodyPayload.put("storage", storageBody)

    Map<String, Object> payload = new LinkedHashMap<String, Object>()
    payload.put("type", "page")
    payload.put("title", title)
    payload.put("space", spacePayload)
    payload.put("body", bodyPayload)

    Map<String, Object> call = confluenceCall(factory, Request.MethodType.POST, "/rest/api/content",
        JsonOutput.toJson(payload))
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", "The parent page \"" + title + "\" could not be created in \"" + spaceKey + "\": " +
            String.valueOf(call.get("error")))
        return result
    }

    String createdId = PageExport.str((Map<String, Object>) call.get("json"), "id", "")
    if (createdId.isEmpty()) {
        result.put("error", "Confluence accepted the parent page \"" + title + "\" but returned no page id, so the " +
            "report has no confirmed place to go.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("id", createdId)
    result.put("created", Boolean.TRUE)
    return result
}

/* The existence check. found=false only when Confluence answered and the result
 * set was empty; every other outcome is ok=false with a reason. storageRead
 * stays false when the body did not arrive, which the caller treats as a failed
 * read - not as a page without decisions. */
Map<String, Object> confluenceFindPage(ApplicationLinkRequestFactory factory, String spaceKey, String title) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("found", Boolean.FALSE)
    result.put("id", null)
    result.put("version", Integer.valueOf(0))
    result.put("storage", null)
    result.put("storageRead", Boolean.FALSE)
    /* Where the page sits today, so the write below can tell a move it has to make
     * from one it does not. It rides along with the existence check and costs no
     * extra call. parentMeasured stays false when no ancestor array came back,
     * which is not a measurement and never means "the page has no parent". */
    result.put("parentId", null)
    result.put("parentMeasured", Boolean.FALSE)

    String url = "/rest/api/content?type=page&spaceKey=" + URLEncoder.encode(spaceKey, "UTF-8") +
        "&title=" + URLEncoder.encode(title, "UTF-8") + "&expand=body.storage,version,ancestors&limit=2"
    Map<String, Object> call = confluenceCall(factory, Request.MethodType.GET, url, null)
    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", call.get("error"))
        return result
    }

    List<Map<String, Object>> rows = PageExport.rowsOf((Map<String, Object>) call.get("json"), "results")
    result.put("ok", Boolean.TRUE)
    if (rows.isEmpty()) {
        return result
    }

    Map<String, Object> page = rows.get(0)
    result.put("found", Boolean.TRUE)
    result.put("id", PageExport.str(page, "id", null))
    result.put("version", Integer.valueOf((int) PageExport.lng(PageExport.sub(page, "version"), "number")))

    Map<String, Object> chain = PageExport.innermostAncestor(page)
    result.put("parentMeasured", chain.get("measured"))
    result.put("parentId", chain.get("parentId"))

    Map<String, Object> storage = PageExport.sub(PageExport.sub(page, "body"), "storage")
    Object value = storage.get("value")
    if (value != null) {
        result.put("storage", value.toString())
        result.put("storageRead", Boolean.TRUE)
    }
    return result
}

/* Create or update. The update path sends version number + 1 with a message, the
 * documented way to write a new version.
 *
 * Ancestors now travel with the write when this run named a parent, on update as
 * well as on create. Whether a PUT that carries ancestors actually moves a page in
 * Confluence Data Center 10 is NOT VERIFIED: the Atlassian REST reference renders
 * its content with JavaScript and hands back only navigation, and no REST resource
 * jar was available to read the annotations from. Community summaries claim it
 * works. That is hearsay and nothing here asserts it. It is sent, the position is
 * then measured by the read-back below, and the caller reports the measurement.
 *
 * Being unverified, it is also not allowed to cost the report. A PUT that carries
 * ancestors and is rejected is retried once without them, so the report is written
 * where it already was and the verdict says the parent was not applied.
 *
 * A run that names no parent sends no ancestors on update at all, so a page an
 * administrator moved by hand keeps its place. */
Map<String, Object> confluenceWritePage(ApplicationLinkRequestFactory factory, String spaceKey, String title,
                                        String storage, String parentId, String existingId, int existingVersion,
                                        String moveDecision) {
    Map<String, Object> result = new LinkedHashMap<String, Object>()
    result.put("ok", Boolean.FALSE)
    result.put("error", null)
    result.put("id", existingId)
    result.put("version", null)
    result.put("parentMeasured", Boolean.FALSE)
    result.put("actualParentId", null)
    result.put("parentSendError", null)

    Map<String, Object> storageBody = new LinkedHashMap<String, Object>()
    storageBody.put("value", storage)
    storageBody.put("representation", "storage")

    Map<String, Object> bodyPayload = new LinkedHashMap<String, Object>()
    bodyPayload.put("storage", storageBody)

    Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
    spacePayload.put("key", spaceKey)

    Map<String, Object> payload = new LinkedHashMap<String, Object>()
    payload.put("type", "page")
    payload.put("title", title)
    payload.put("space", spacePayload)
    payload.put("body", bodyPayload)

    List<Map<String, Object>> ancestors = null
    if (parentId != null && !parentId.trim().isEmpty()) {
        Map<String, Object> ancestor = new LinkedHashMap<String, Object>()
        ancestor.put("id", parentId.trim())
        ancestors = new ArrayList<Map<String, Object>>()
        ancestors.add(ancestor)
    }

    String writeUrl = "/rest/api/content/" + existingId
    Map<String, Object> call = null
    if (existingId == null || existingId.trim().isEmpty()) {
        if (ancestors != null) {
            payload.put("ancestors", ancestors)
        }
        call = confluenceCall(factory, Request.MethodType.POST, "/rest/api/content", JsonOutput.toJson(payload))
    } else {
        Map<String, Object> version = new LinkedHashMap<String, Object>()
        version.put("number", Integer.valueOf(existingVersion + 1))
        version.put("message", "Jira App Footprint Analysis export")

        payload.put("id", existingId)
        payload.put("version", version)

        /* Only a move this run actually has to make. A page that already sits
         * directly under the named parent is written without ancestors, so an
         * unchanged repeat run does not send a reparent it does not need. */
        boolean sentAncestors = ancestors != null && PageExport.MOVE_REQUESTED.equals(moveDecision)
        if (sentAncestors) {
            payload.put("ancestors", ancestors)
        }
        call = confluenceCall(factory, Request.MethodType.PUT, writeUrl, JsonOutput.toJson(payload))

        if (call.get("ok") != Boolean.TRUE && sentAncestors) {
            /* The report matters more than its position, and the ancestors on this
             * PUT are unverified. A rejected write is retried once without them
             * rather than losing the report to an experiment. The retry reuses the
             * same version number on purpose: if the first PUT did change the page
             * after all, the retry fails on the version conflict and the caller
             * reports a failed write instead of writing a second version. */
            result.put("parentSendError", String.valueOf(call.get("error")))
            payload.remove("ancestors")
            call = confluenceCall(factory, Request.MethodType.PUT, writeUrl, JsonOutput.toJson(payload))
        }
    }

    if (call.get("ok") != Boolean.TRUE) {
        result.put("error", call.get("error"))
        return result
    }

    Map<String, Object> json = (Map<String, Object>) call.get("json")
    String writtenId = PageExport.str(json, "id", existingId)
    if (writtenId == null || writtenId.trim().isEmpty()) {
        result.put("error", "Confluence accepted the write but returned no page ID, so the result cannot be confirmed.")
        return result
    }

    result.put("ok", Boolean.TRUE)
    result.put("id", writtenId)

    /* The version and the position are both read back rather than assumed. An
     * accepted write is the server reporting on itself; it is not a measurement of
     * the page, and for the position it is not even a documented one. If the
     * read-back does not answer, the version stays null and parentMeasured stays
     * false, and the caller says so for each - the page is written either way, but
     * an unconfirmed number is never invented and an unmeasured position is
     * reported as unknown rather than as a move that worked. */
    Map<String, Object> verify = confluenceCall(factory, Request.MethodType.GET,
        "/rest/api/content/" + writtenId + "?expand=version,ancestors", null)
    if (verify.get("ok") == Boolean.TRUE) {
        Map<String, Object> verified = (Map<String, Object>) verify.get("json")
        Map<String, Object> chain = PageExport.innermostAncestor(verified)
        result.put("parentMeasured", chain.get("measured"))
        result.put("actualParentId", chain.get("parentId"))
        long confirmed = PageExport.lng(PageExport.sub(verified, "version"), "number")
        if (confirmed > 0L) {
            result.put("version", Integer.valueOf((int) confirmed))
            return result
        }
    }

    long fromWrite = PageExport.lng(PageExport.sub(json, "version"), "number")
    if (fromWrite > 0L) {
        result.put("version", Integer.valueOf((int) fromWrite))
    }
    return result
}


/* =============================================================================
 * REST Endpoint - Confluence page export (POST)
 * ========================================================================== */

/* Same endpoint name as the report with a different httpMethod. The Adaptavist
 * documentation states that several closures with the same name and different
 * verbs may live in one file, so the report page can POST to its own URL without
 * knowing the REST base path.
 *
 * CSRF - UNVERIFIED. The Custom REST Endpoint documentation does not say whether
 * these endpoints sit behind the Jira XSRF filter, so the report page sends
 * X-Atlassian-Token: no-check, which is required if the filter applies and
 * harmless if it does not. Reading that header back would need the three-argument
 * HttpServletRequest closure form, and the servlet package this ScriptRunner
 * version passes (javax or jakarta) is exactly what this file avoids depending
 * on, so no header check is attempted here. What IS enforced on the server: the
 * jira-administrators gate, the Confluence permissions of the impersonated user
 * on the far side of the application link, and the rule that only a page carrying
 * the export marker is ever updated - a forged request can neither replace a
 * foreign page nor drop a decision. TO CONFIRM before relying on more than that:
 * whether the XSRF filter covers ScriptRunner endpoints, and which
 * HttpServletRequest type is passed, so an explicit header check can be added. */
appFootprint(
    httpMethod: "POST",
    groups: ["jira-administrators"]
) { queryParams, body ->

    long started = System.currentTimeMillis()

    /* ---- JAX-RS Response, resolved at runtime (javax / jakarta neutral) --- */

    Class responseClass = null
    try {
        responseClass = Class.forName("jakarta.ws.rs.core.Response")
    } catch (ClassNotFoundException ignored) {
        responseClass = Class.forName("javax.ws.rs.core.Response")
    }

    def refuse = { int status, String stage, String message ->
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("written", Boolean.FALSE)
        payload.put("stage", stage)
        payload.put("error", message)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return responseClass.status(status)
            .entity(JsonOutput.prettyPrint(JsonOutput.toJson(payload)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    /* ---- Request ----------------------------------------------------------- */

    String requestBody = body == null ? null : body.toString()

    if (requestBody == null || requestBody.trim().isEmpty()) {
        return refuse(400, "request", "The request body is empty. The export payload is expected as JSON.")
    }
    if (requestBody.length() > PageExport.MAX_PAYLOAD_CHARS) {
        return refuse(413, "request", "The export payload exceeds " + String.valueOf(PageExport.MAX_PAYLOAD_CHARS) + " characters.")
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(requestBody)
    } catch (Exception error) {
        return refuse(400, "request", "The request body is not valid JSON: " + error.getClass().getSimpleName())
    }
    if (!(parsed instanceof Map)) {
        return refuse(400, "request", "The request body must be a JSON object.")
    }

    /* The payload is the report model the GET branch serialised for this run, so
     * the page shows exactly the figures the administrator saw. It travels through
     * the browser, which means an administrator could tamper with it - the same
     * administrator who may edit any page they can reach anyway. Everything is
     * escaped on the way into storage format, and the decision carry-over below is
     * unaffected by it: decisions come from the existing page and are read here. */
    Map<String, Object> request = PageExport.copyMap((Map<?, ?>) parsed)

    /* ---- Staged lookups ---------------------------------------------------- */

    /* Rendering the report reads nothing. Everything the export form needs arrives
     * here on demand, one stage per request, discriminated by "action":
     * links -> spaces -> pages -> write. A body without an action is the write, so
     * the write path below keeps the shape and the order it always had. */
    String action = PageExport.str(request, "action", "write").toLowerCase(Locale.ROOT)

    def answer = { Map<String, Object> data ->
        data.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return responseClass
            .ok(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    if (action == "links" || action == "spaces" || action == "pages") {
        Map<String, Object> lookup = confluenceApplicationLinks()
        if (lookup.get("ok") != Boolean.TRUE) {
            return refuse(500, "link", String.valueOf(lookup.get("error")))
        }
        List<ApplicationLink> confluenceLinks = (List<ApplicationLink>) lookup.get("links")
        if (confluenceLinks.isEmpty()) {
            return refuse(500, "link", "No Confluence application link was found in this Jira instance, so there " +
                "is nowhere to write. Seen: " + String.valueOf(lookup.get("seen")) + " application link(s), type(s): " +
                String.valueOf(lookup.get("seenTypes")) + ". If a Confluence link does exist, report those two values. " +
                "Otherwise create the link under Administration > Applications > Application links, then press " +
                "Export to Confluence again.")
        }

        if (action == "links") {
            String primaryId = lookup.get("primaryId") == null ? null : lookup.get("primaryId").toString()
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>()
            for (ApplicationLink link : confluenceLinks) {
                String id = confluenceLinkId(link)
                if (id == null) {
                    continue
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>()
                row.put("id", id)
                row.put("name", confluenceLinkName(link))
                row.put("displayUrl", confluenceLinkUrl(link))
                row.put("primary", Boolean.valueOf(primaryId != null && primaryId == id))
                rows.add(row)
            }
            if (rows.isEmpty()) {
                return refuse(500, "link", "The Confluence application links of this instance carry no readable id, " +
                    "so no target can be picked.")
            }
            /* Exactly one link is still offered as a list of one, preselected by the
             * browser, so the administrator always sees where the page will land. */
            Map<String, Object> linkPayload = new LinkedHashMap<String, Object>()
            linkPayload.put("ok", Boolean.TRUE)
            linkPayload.put("action", "links")
            linkPayload.put("typedLookup", lookup.get("typed"))
            linkPayload.put("links", rows)
            return answer(linkPayload)
        }

        Map<String, Object> target = confluenceTarget(confluenceLinks, PageExport.str(request, "applicationLinkId", ""))
        if (target.get("ok") != Boolean.TRUE) {
            return refuse(500, "link", String.valueOf(target.get("error")))
        }
        ApplicationLink targetLink = (ApplicationLink) target.get("link")
        ApplicationLinkRequestFactory targetFactory = (ApplicationLinkRequestFactory) target.get("factory")

        if (action == "spaces") {
            Map<String, Object> spaceResult = confluenceSpaces(targetFactory)
            if (spaceResult.get("ok") != Boolean.TRUE) {
                return refuse(500, "spaces", "The space list could not be read over the application link \"" +
                    confluenceLinkName(targetLink) + "\": " + String.valueOf(spaceResult.get("error")))
            }
            List<Map<String, Object>> spaceRows = (List<Map<String, Object>>) spaceResult.get("spaces")
            if (spaceRows.isEmpty()) {
                return refuse(500, "spaces", "Confluence answered over the application link \"" +
                    confluenceLinkName(targetLink) + "\" but returned no space this user may see, so no space can be picked.")
            }
            spaceRows.sort { Map<String, Object> a, Map<String, Object> b ->
                int byName = PageExport.str(a, "name", "").compareToIgnoreCase(PageExport.str(b, "name", ""))
                if (byName != 0) {
                    return byName
                }
                return PageExport.str(a, "key", "").compareToIgnoreCase(PageExport.str(b, "key", ""))
            }
            /* GET /rest/api/space documents no substring parameter, so the whole
             * current-space list is read once per target and the search runs over
             * it. What reaches the page is the matches, never the full list. */
            Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
            spacePayload.put("ok", Boolean.TRUE)
            spacePayload.put("action", "spaces")
            spacePayload.put("target", confluenceLinkName(targetLink))
            spacePayload.put("spaces", spaceRows)
            spacePayload.put("truncated", spaceResult.get("truncated"))
            return answer(spacePayload)
        }

        String searchSpace = PageExport.str(request, "spaceKey", "")
        String searchQuery = PageExport.str(request, "query", "")
        if (searchSpace.isEmpty()) {
            return refuse(400, "pages", "No space was selected, so there is nothing to search in.")
        }
        if (searchQuery.trim().length() < PageExport.MIN_SEARCH_CHARS) {
            return refuse(400, "pages", "Type at least " + String.valueOf(PageExport.MIN_SEARCH_CHARS) +
                " characters of the page title.")
        }
        Map<String, Object> found = confluenceSearchPages(targetFactory, searchSpace, searchQuery)
        if (found.get("ok") != Boolean.TRUE) {
            return refuse(500, "pages", String.valueOf(found.get("error")))
        }
        Map<String, Object> pagePayload = new LinkedHashMap<String, Object>()
        pagePayload.put("ok", Boolean.TRUE)
        pagePayload.put("action", "pages")
        pagePayload.put("spaceKey", searchSpace)
        pagePayload.put("pages", found.get("pages"))
        pagePayload.put("truncated", found.get("truncated"))
        return answer(pagePayload)
    }

    /* ---- Page export: validate --------------------------------------------- */

    Map<String, Object> options = PageExport.sub(request, "options")

    String spaceKey = PageExport.str(request, "spaceKey", "")
    String title = PageExport.str(request, "title", "")
    String parentRaw = PageExport.str(request, "parentPageId", "").trim()
    String parentTitleRaw = PageExport.str(request, "parentTitle", "").trim()
    String numbers = PageExport.str(options, "numbers", "de").toLowerCase(Locale.ROOT)
    Locale numberLocale = numbers == "en" ? Locale.ENGLISH : Locale.GERMANY

    if (spaceKey.isEmpty()) {
        return refuse(400, "validate", "No space was selected.")
    }
    String spaceProblem = PageExport.spaceKeyProblem(spaceKey)
    if (!spaceProblem.isEmpty()) {
        return refuse(400, "validate", "The space key \"" + spaceKey + "\" cannot be used: " + spaceProblem +
            ". Nothing is written.")
    }
    if (title.isEmpty()) {
        return refuse(400, "validate", "No page title was given.")
    }
    /* Exactly one parent instruction, never two. A picked page and a typed title
     * can disagree, and guessing which one the administrator meant is how a
     * report lands somewhere nobody looks. The request is refused instead. */
    String parentProblem = PageExport.parentProblem(parentRaw, parentTitleRaw, title)
    if (!parentProblem.isEmpty()) {
        return refuse(400, "validate", parentProblem)
    }
    if (title.length() > PageExport.MAX_TITLE_CHARS) {
        return refuse(400, "validate", "The page title exceeds " + String.valueOf(PageExport.MAX_TITLE_CHARS) + " characters.")
    }
    if (PageExport.rowsOf(request, "apps").isEmpty()) {
        return refuse(400, "validate", "The export payload carries no apps. Nothing is written.")
    }

    /* ---- Application link -------------------------------------------------- */

    /* The target is the one the administrator picked in the form, resolved by its
     * ApplicationId inside the Confluence links of this instance. The link is no
     * longer guessed as "the first Confluence link that matched", which on an
     * instance with two Confluence links could write to the wrong site silently. */
    Map<String, Object> writeLookup = confluenceApplicationLinks()
    if (writeLookup.get("ok") != Boolean.TRUE) {
        return refuse(500, "link", String.valueOf(writeLookup.get("error")) + " Nothing is written.")
    }
    List<ApplicationLink> writeLinks = (List<ApplicationLink>) writeLookup.get("links")
    if (writeLinks.isEmpty()) {
        return refuse(500, "link", "No Confluence application link was found in this Jira instance, so there is " +
            "nowhere to write. Seen: " + String.valueOf(writeLookup.get("seen")) + " application link(s), type(s): " +
            String.valueOf(writeLookup.get("seenTypes")) + ". Nothing is written.")
    }

    Map<String, Object> writeTarget = confluenceTarget(writeLinks, PageExport.str(request, "applicationLinkId", ""))
    if (writeTarget.get("ok") != Boolean.TRUE) {
        return refuse(500, "link", String.valueOf(writeTarget.get("error")))
    }
    ApplicationLink link = (ApplicationLink) writeTarget.get("link")
    ApplicationLinkRequestFactory factory = (ApplicationLinkRequestFactory) writeTarget.get("factory")

    /* ---- Parent page ------------------------------------------------------- */

    /* Three outcomes, kept apart in the response: no parent, a parent that was
     * found, and a parent this run created. Creating is never reported as
     * finding - an administrator who reads "found" believes the page was already
     * there and stops looking for the one that was just made. */
    String parentId = null
    String parentTitle = null
    String parentAction = "none"

    if (!parentRaw.isEmpty()) {
        try {
            Long.parseLong(parentRaw)
        } catch (NumberFormatException ignored) {
            return refuse(400, "validate", "The parent page ID \"" + parentRaw + "\" is not a number.")
        }
        Map<String, Object> parent = confluenceParentPage(factory, parentRaw)
        if (parent.get("ok") != Boolean.TRUE) {
            return refuse(400, "validate", String.valueOf(parent.get("error")))
        }
        String parentSpace = String.valueOf(parent.get("spaceKey"))
        if (!spaceKey.equalsIgnoreCase(parentSpace)) {
            return refuse(400, "validate", "The parent page " + parentRaw + " sits in space \"" + parentSpace +
                "\", not in \"" + spaceKey + "\". Nothing is written.")
        }
        parentId = parentRaw
        parentTitle = parent.get("title") == null ? null : parent.get("title").toString()
        parentAction = "found"
    }

    /* ---- Decision read ----------------------------------------------------- */

    Map<String, Object> existing = confluenceFindPage(factory, spaceKey, title)
    if (existing.get("ok") != Boolean.TRUE) {
        return refuse(409, "read", "The existing page could not be looked up in Confluence (" +
            String.valueOf(existing.get("error")) + "). Nothing is written, so no decision can be lost.")
    }

    DecisionRead read = new DecisionRead()
    boolean pageExists = existing.get("found") == Boolean.TRUE

    if (pageExists) {
        if (existing.get("storageRead") != Boolean.TRUE) {
            return refuse(409, "read", "A page with this title already exists in \"" + spaceKey + "\" but its body did " +
                "not arrive over the application link. Nothing is written, so no decision can be lost.")
        }
        read = PageExport.parseDecisions(String.valueOf(existing.get("storage")))
        read.pageId = existing.get("id") == null ? null : existing.get("id").toString()
        read.pageVersion = ((Number) existing.get("version")).intValue()
    }

    /* Fail closed. This is the only path to a write and a FAILED read never passes
     * it: no create, no update, reported to the caller as a failure. A page that
     * would lose administrator notes is never produced. */
    if (!read.isWriteAllowed()) {
        Map<String, Object> refusal = new LinkedHashMap<String, Object>()
        refusal.put("ok", Boolean.FALSE)
        refusal.put("written", Boolean.FALSE)
        refusal.put("stage", "read")
        refusal.put("error", read.reason)
        refusal.put("decisionRead", read.outcome)
        refusal.put("decisionReadDetail", read.asMap())
        refusal.put("spaceKey", spaceKey)
        refusal.put("title", title)
        refusal.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return responseClass.status(409)
            .entity(JsonOutput.prettyPrint(JsonOutput.toJson(refusal)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    /* ---- Parent page from a typed title ------------------------------------ */

    /* There is no Create button: a title that was typed and never picked is
     * resolved by the generating run. It sits AFTER the fail-closed decision read
     * on purpose - a run that is about to be refused with a 409 must not leave a
     * container page behind that nothing was ever filed under. */
    if (parentId == null && !parentTitleRaw.isEmpty()) {
        Map<String, Object> parent = confluenceParentByTitle(factory, spaceKey, parentTitleRaw)
        if (parent.get("ok") != Boolean.TRUE) {
            /* No fallback to the top level of the space. A report filed where
             * nobody expects it is worse than a run that stops and says why. */
            return refuse(500, "parent", String.valueOf(parent.get("error")) + " Nothing is written.")
        }
        parentId = String.valueOf(parent.get("id"))
        parentTitle = parentTitleRaw
        parentAction = parent.get("created") == Boolean.TRUE ? "created" : "found"
    }

    /* ---- Write ------------------------------------------------------------- */

    ExportOutcome outcome = PageExport.render(request, read, numberLocale)

    /* What this run does about the position. A parent named in this run is carried
     * out even when the report page already exists - that is the defect this fixes,
     * a typed parent title that produced the parent page and then filed nothing
     * under it. A run that names no parent leaves the position alone.
     *
     * The current parent came back with the existence check above at no extra call.
     * When it was not readable the decision resolves to "move": carrying out the
     * instruction is the safe direction, and only a positive match skips. */
    String currentParentId = existing.get("parentMeasured") == Boolean.TRUE && existing.get("parentId") != null
        ? existing.get("parentId").toString()
        : null
    String moveDecision = PageExport.moveDecision(parentId, currentParentId)

    Map<String, Object> written = confluenceWritePage(factory, spaceKey, title, outcome.storage,
        parentId, read.pageId, read.pageVersion, moveDecision)
    if (written.get("ok") != Boolean.TRUE) {
        return refuse(500, "write", "The Confluence page could not be written: " + String.valueOf(written.get("error")))
    }

    String pageId = written.get("id") == null ? null : written.get("id").toString()
    Object pageVersion = written.get("version")
    if (pageVersion == null) {
        outcome.warnings.add("The page was written, but its new version number could not be read back from Confluence.")
    }

    /* The verdict on the position, and it is the read-back that decides it, not the
     * accepted write. This matters more here than on the Confluence endpoint: the
     * move rides on an ancestors array in a PUT whose reparenting behaviour on
     * Confluence Data Center 10 no primary source could confirm. Measuring it is
     * the whole reason the field exists, and an unmeasured position is reported as
     * unknown rather than as a success. */
    Map<String, Object> parentVerdict = PageExport.parentOutcome(parentId,
        written.get("parentMeasured") == Boolean.TRUE,
        written.get("actualParentId") == null ? null : written.get("actualParentId").toString(),
        written.get("parentSendError") == null ? null : written.get("parentSendError").toString())
    if (parentVerdict.get("reason") != null) {
        outcome.warnings.add(parentVerdict.get("reason").toString())
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>()
    response.put("ok", Boolean.TRUE)
    response.put("written", Boolean.TRUE)
    response.put("action", pageExists ? "updated" : "created")
    response.put("target", confluenceLinkName(link))
    response.put("spaceKey", spaceKey)
    response.put("title", title)
    response.put("pageId", pageId)
    response.put("pageVersion", pageVersion)
    response.put("pageUrl", confluencePageUrl(link, pageId))
    response.put("parentPageId", parentId)
    response.put("parentAction", parentAction)
    response.put("parentTitle", parentTitle)
    response.put("parentPageUrl", parentId == null ? null : confluencePageUrl(link, parentId))
    response.put("parentMove", moveDecision)
    response.put("parentApplied", parentVerdict.get("applied"))
    response.put("parentAppliedReason", parentVerdict.get("reason"))
    response.put("decisionRead", read.outcome)
    response.put("decisionReadDetail", read.asMap())
    response.put("decisionsRead", Integer.valueOf(outcome.decisionsRead))
    response.put("decisionsCarried", Integer.valueOf(outcome.decisionsCarried))
    response.put("orphanedDecisions", Integer.valueOf(outcome.orphanKeys.size()))
    response.put("orphanedKeys", outcome.orphanKeys)
    response.put("warnings", outcome.warnings)
    response.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))

    return responseClass
        .ok(JsonOutput.prettyPrint(JsonOutput.toJson(response)))
        .type("application/json; charset=UTF-8")
        .build()
}
