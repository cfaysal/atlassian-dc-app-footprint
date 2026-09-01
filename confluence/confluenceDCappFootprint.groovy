/* =============================================================================
 * Confluence Data Center - App Footprint Analysis
 * ScriptRunner Custom REST Endpoint. Read-only, admin-gated.
 *
 * Version
 *   Declared once as Cfp.VERSION below and printed by every output channel: the
 *   HTML report, the JSON and the generated page. The number lives in exactly
 *   one place, so this header cannot drift away from the code.
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
 *   includeArchived=true|false  default false
 *   includeModules=true|false   default false (HTML/JSON detail)
 *   scanUsage=true|false        default true
 *   scanAliases=true|false      default false
 *   scanBudgetMs=<long>         default 120000, 0 = unlimited
 *   appKey=<plugin-key>         optional single-app filter
 *   numbers=de|en               default de
 *   diag=true|false             default false, runs the read-path self-check
 *
 * Confluence page export (POST on the same endpoint URL)
 *   Writes an Executive Summary of the rendered report into a Confluence page.
 *   The administrator picks a space and optionally a parent page; a repeat run
 *   updates the same page instead of creating a second one. The Decision column
 *   of an existing page is read back and carried over verbatim. A failed or
 *   inconclusive read aborts the write - nothing is created and nothing updated.
 *   Everything is done through local Java APIs, no outbound network call.
 *   Rendering the report performs no lookup at all. The export is staged behind
 *   its button: the click opens the space search, choosing a space opens the
 *   parent page search, and only then can the page be generated. Each stage is
 *   one POST on this endpoint, discriminated by an "action" field in the body.
 *   The parent page search matches whole words in the title through the content
 *   index, no longer only the exact title. The field has no button: a title that
 *   was typed and never picked is adopted if such a page already exists and
 *   created by the generating run if it does not; the answer says which of the
 *   two happened. If it cannot be created the run aborts - the report is never
 *   filed at the top level of the space instead, where nobody would look for it.
 *   This endpoint writes into its own instance, so there is no target to pick.
 *
 * Reporting discipline
 *   - A failed read is never rendered as a measured zero.
 *   - A skipped/budgeted usage scan is marked n/m (not measured).
 *   - CURRENT and ARCHIVED macro usage are never mixed.
 *   - The report is read-only and performs no outbound network call.
 * =============================================================================
 */

/* The api.service.content layer, kept and no longer resolved.
 *
 * The space picker of the export used to read its list through
 * com.atlassian.confluence.api.service.content.SpaceService. That concrete type is
 * a Spring AOP proxy, and resolving it inside a ScriptRunner REST endpoint throws
 *   IllegalArgumentException: org.springframework.aop.SpringProxy referenced from
 *   a method is not visible from class loader ... ChainingClassLoader
 * Measured on a customer instance under OP-1063, and measured for the same type on
 * two Confluence 10.2.14 instances under OP-1005 in the sibling space-configuration
 * script. The picker therefore refused on every instance it was opened on. It now
 * reads the SPACES table; see SpaceCatalog and Db below.
 *
 * The imports stay because the finding is about ONE concrete type and its proxy, not
 * about the api.service layer as a whole. That distinction is measured, not assumed:
 * the same sibling script imports and runs api.service.settings.ExtendedPluginSettings
 * and ExtendedPluginSettingsFactory on the same instance line, so api.service.settings
 * is untouched by this. Anyone reinstating an API-layer read here should find what was
 * measured about this one type rather than rediscover it the hard way. */
import com.atlassian.confluence.api.model.Expansion
import com.atlassian.confluence.api.model.content.Space as ApiSpace
import com.atlassian.confluence.api.model.content.SpaceStatus as ApiSpaceStatus
import com.atlassian.confluence.api.model.pagination.PageResponse
import com.atlassian.confluence.api.model.pagination.SimplePageRequest
import com.atlassian.confluence.api.service.content.SpaceService as ApiSpaceService
import com.atlassian.confluence.api.service.content.SpaceService.SpaceFinder
import com.atlassian.confluence.core.BodyContent
import com.atlassian.confluence.core.BodyType
import com.atlassian.confluence.core.DefaultSaveContext
import com.atlassian.confluence.content.service.PageService
import com.atlassian.confluence.content.service.SpaceService
import com.atlassian.confluence.macro.browser.MacroMetadataSource
import com.atlassian.confluence.macro.browser.beans.MacroMetadata
import com.atlassian.confluence.pages.Page
import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.renderer.UserMacroConfig
import com.atlassian.confluence.renderer.UserMacroLibrary
import com.atlassian.confluence.search.service.ContentTypeEnum
import com.atlassian.confluence.search.v2.BooleanOperator
import com.atlassian.confluence.search.v2.Index
import com.atlassian.confluence.search.v2.SearchFieldMappings
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.SearchQuery
import com.atlassian.confluence.search.v2.query.BooleanQuery
import com.atlassian.confluence.search.v2.query.ContentTypeQuery
import com.atlassian.confluence.search.v2.query.InSpaceQuery
import com.atlassian.confluence.search.v2.query.MacroUsageQuery
import com.atlassian.confluence.search.v2.query.TextFieldQuery
import com.atlassian.confluence.search.v2.query.WildcardTextFieldQuery
import com.atlassian.confluence.setup.settings.GlobalSettingsManager
import com.atlassian.confluence.spaces.Space
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.confluence.spaces.SpaceStatus
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal
import com.atlassian.confluence.util.GeneralUtil
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
import groovy.json.JsonSlurper
import groovy.transform.BaseScript

import org.codehaus.groovy.runtime.InvokerHelper

import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern

@BaseScript CustomEndpointDelegate delegate


/* =============================================================================
 * Constants / utility
 * ========================================================================== */

class Cfp {

    static final String NA = "\u2014"

    /* The single place the report version lives. The file header points here and
     * every output channel prints this constant, so a report always names the
     * build that produced it. */
    static final String VERSION = "4.11"

    static final String MEASURED = "measured"
    static final String DISABLED = "disabled"
    static final String BUDGET = "budget"
    static final String ERROR = "error"
    static final String PARTIAL = "partial"

    /* OP-1066. Where the name of a macro came from. The content index is queried
     * by name, so the set of names decides what can be found at all, and a reader
     * has to be able to tell a name the app declared as a module from one that
     * only the instance-wide catalogue knew about. */
    static final String FROM_DESCRIPTOR = "descriptor"
    static final String FROM_CATALOG = "catalog"

    /* Names the catalogue holds for one app that the descriptor walk did not
     * produce, in catalogue order and without duplicates. Kept free of every
     * Confluence type so the offline suite can hold it to its behaviour: the
     * extraction of names from macro metadata is the part that needs an instance,
     * the decision what to do with them is not. A blank name is dropped rather
     * than scanned, because MacroUsageQuery on an empty string is not a question. */
    static List<String> catalogOnlyNames(Collection<String> catalogNames, Collection<String> alreadyEnumerated) {
        List<String> fresh = new ArrayList<String>()
        if (catalogNames == null) {
            return fresh
        }
        /* One set carries both rejections. A name the descriptor walk already
         * produced and a name this loop has already taken are the same thing to the
         * caller, so seen grows as names are accepted. */
        Set<String> seen = new HashSet<String>()
        if (alreadyEnumerated != null) {
            seen.addAll(alreadyEnumerated)
        }
        for (String name : catalogNames) {
            if (name == null || name.trim().isEmpty()) {
                continue
            }
            if (seen.contains(name)) {
                continue
            }
            seen.add(name)
            fresh.add(name)
        }
        return fresh
    }

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
    static boolean diagBoxShown(int macrosSkippedByBudget, int diagnostics) {
        return macrosSkippedByBudget > 0 || diagnostics > 0
    }

    /* A macro scan skipped by the budget and a suppressed read error are the two
     * things that degrade this report. Observations are deliberately not an input:
     * no number of them can turn the box into a warning. */
    static String diagClass(int macrosSkippedByBudget, int readErrors) {
        return (macrosSkippedByBudget > 0 || readErrors > 0) ? DIAG_WARN : DIAG_INFO
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

    /* The first few names plus the total. A diagnostic that names no names leaves
     * the administrator guessing, and one that names all of them on a large
     * instance prints an inventory instead of a note. */
    static final int MAX_NAMED = 5

    static String nameList(List<String> names) {
        if (names == null || names.isEmpty()) {
            return NA
        }
        int shown = Math.min(MAX_NAMED, names.size())
        String text = String.join(", ", new ArrayList<String>(names.subList(0, shown)))
        if (names.size() > shown) {
            text = text + ", ... (+" + String.valueOf(names.size() - shown) + " more)"
        }
        return text
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
    static final BigDecimal CRITICAL_PERCENT = new BigDecimal("50")
    static final BigDecimal HIGH_PERCENT = new BigDecimal("20")
    static final BigDecimal MEDIUM_PERCENT = new BigDecimal("5")

    static boolean isDecommissionCandidate(Boolean systemProvided, ImpactAssessment assessment) {
        return Boolean.FALSE.equals(systemProvided) && assessment != null &&
            "NO_DETECTABLE_FOOTPRINT".equals(assessment.level)
    }

    static int rankFor(ImpactDimension dimension) {
        if (dimension == null || !dimension.available() || dimension.numerator <= 0L) {
            return 0
        }
        BigDecimal scaledNumerator = BigDecimal.valueOf(dimension.numerator)
            .multiply(BigDecimal.valueOf(100L))
        BigDecimal denominator = BigDecimal.valueOf(dimension.denominator.longValue())
        if (scaledNumerator.compareTo(denominator.multiply(CRITICAL_PERCENT)) >= 0) {
            return 7
        }
        if (scaledNumerator.compareTo(denominator.multiply(HIGH_PERCENT)) >= 0) {
            return 6
        }
        if (scaledNumerator.compareTo(denominator.multiply(MEDIUM_PERCENT)) >= 0) {
            return 5
        }
        return 4
    }

    static ImpactAssessment assess(List<ImpactDimension> dimensions, boolean incomplete) {
        List<ImpactDimension> safe = dimensions == null ?
            Collections.<ImpactDimension>emptyList() : dimensions
        ImpactAssessment result = new ImpactAssessment()
        result.dimensions.addAll(safe)

        int selectedRank = 0
        boolean unavailablePositive = false
        for (ImpactDimension dimension : safe) {
            if (dimension == null) {
                continue
            }
            if (!dimension.available() && dimension.numerator > 0L) {
                unavailablePositive = true
            }
            int rank = rankFor(dimension)
            if (rank > selectedRank) {
                selectedRank = rank
            }
            BigDecimal dimensionPercent = dimension.percent()
            if (dimension.available() && dimensionPercent.compareTo(result.maxPercent) > 0) {
                result.maxPercent = dimensionPercent
            }
            result.partial = result.partial || dimension.partial
        }

        result.partial = result.partial || incomplete || unavailablePositive
        if (selectedRank > 0) {
            result.applyRank(selectedRank)
            for (ImpactDimension dimension : safe) {
                if (rankFor(dimension) == selectedRank) {
                    result.reasons.add(dimension.reason())
                }
            }
            return result
        }
        if (result.partial) {
            result.level = "REVIEW_REQUIRED"
            result.label = "Review required"
            result.rank = 2
            result.reasons.add("The measurable footprint is incomplete; zero impact is not established.")
            return result
        }
        result.level = "NO_DETECTABLE_FOOTPRINT"
        result.label = "No detectable footprint"
        result.rank = 0
        result.reasons.add("Every available instance-relative usage dimension is zero.")
        return result
    }
}

class ImpactDimension {
    String key
    String label
    long numerator
    Long denominator
    boolean partial

    ImpactDimension(String key, String label, long numerator, Long denominator, boolean partial) {
        this.key = key
        this.label = label
        this.numerator = numerator
        this.denominator = denominator
        this.partial = partial
    }

    boolean available() {
        return denominator != null && denominator.longValue() > 0L
    }

    BigDecimal percent() {
        if (!available() || numerator <= 0L) {
            return BigDecimal.ZERO.setScale(6)
        }
        BigDecimal value = BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100L))
            .divide(BigDecimal.valueOf(denominator.longValue()), 6, java.math.RoundingMode.HALF_UP)
        BigDecimal ceiling = BigDecimal.valueOf(100L).setScale(6)
        return value.compareTo(ceiling) > 0 ? ceiling : value
    }

    String reason() {
        StringBuilder out = new StringBuilder()
        out.append(label).append(": ").append(numerator).append(" of ")
            .append(denominator).append(" (")
            .append(percent().setScale(2, java.math.RoundingMode.HALF_UP)).append("%).")
        if (partial) {
            out.append(" This is a lower bound.")
        }
        return out.toString()
    }

    Map<String, Object> asMap() {
        return [
            key: key,
            label: label,
            numerator: numerator,
            denominator: denominator,
            available: available(),
            percent: available() ? percent() : null,
            partial: partial
        ] as LinkedHashMap
    }
}

class ImpactAssessment {
    String level
    String label
    int rank
    boolean partial
    BigDecimal maxPercent = BigDecimal.ZERO.setScale(6)
    List<String> reasons = new ArrayList<String>()
    List<ImpactDimension> dimensions = new ArrayList<ImpactDimension>()

    void applyRank(int selectedRank) {
        rank = selectedRank
        if (selectedRank == 7) {
            level = "CRITICAL"
            label = "Critical"
        } else if (selectedRank == 6) {
            level = "HIGH"
            label = "High"
        } else if (selectedRank == 5) {
            level = "MEDIUM"
            label = "Medium"
        } else {
            level = "LOW"
            label = "Low"
        }
    }

    Map<String, Object> asMap() {
        List<Map<String, Object>> dimensionMaps = new ArrayList<Map<String, Object>>()
        for (ImpactDimension dimension : dimensions) {
            if (dimension != null) {
                dimensionMaps.add(dimension.asMap())
            }
        }
        return [
            level: level,
            label: label,
            rank: rank,
            partial: partial,
            maxPercent: maxPercent,
            reasons: reasons,
            dimensions: dimensionMaps
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

    /* OP-1066. Cfp.FROM_DESCRIPTOR or Cfp.FROM_CATALOG. Defaulted to the
     * descriptor because every macro found before this existed came from there,
     * so an unset value can never silently claim the wider provenance. */
    String nameSource = Cfp.FROM_DESCRIPTOR
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

    Map<String, Object> asMap(boolean includeArchived = false) {
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
                state: includeArchived ? usageState : Cfp.DISABLED,
                used: includeArchived ? isArchivedUsed() : null,
                contentCount: includeArchived ? getArchivedContentCount() : null,
                spaceCount: includeArchived ? getArchivedSpaceCount() : null,
                spaceKeys: includeArchived ? new ArrayList<String>(archivedSpaceKeys) : null,
                contentTypes: includeArchived ? getArchivedContentTypeCounts() : null
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
    Boolean systemProvided
    boolean enabled
    String state

    List<ExtensionModuleInfo> modules = new ArrayList<ExtensionModuleInfo>()
    List<MacroFootprint> macros = new ArrayList<MacroFootprint>()
    List<String> diagnostics = new ArrayList<String>()

    /* The deliberate subset of diagnostics. diagnostics stays the complete list,
     * so every existing counter and every existing gate keeps its meaning. */
    List<String> observations = new ArrayList<String>()

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
    int observationCount

    /* OP-1066. Whether the instance-wide macro catalogue answered for this run,
     * and how many names it contributed to this app. TRUE means it answered, even
     * when it contributed nothing; FALSE means it did not, and null means it was
     * never asked. The three cases have to stay apart, because they are the
     * difference between a measured zero and an unasked one. */
    Boolean macroCatalogConsulted
    int catalogMacroCount

    /* A macro host whose macros the descriptor walk cannot see, with no catalogue
     * to fall back on. The content index is queried once per known macro name, so
     * an app in this state had no name searched for at all and its macro figures
     * are unasked rather than unfound. */
    boolean macroEnumerationNarrowed() {
        return categoryCount("Macros") > 0 && macros.isEmpty() && macroCatalogConsulted != Boolean.TRUE
    }

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
        observationCount = observations.size()

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
        /* OP-1066 widened the right-hand side. A macro whose name came from the
         * instance-wide catalogue has no enabled module of its own and never will,
         * so counting only enabledMacroCount would report a gap against every host
         * module the catalogue had just explained. */
        int classifiedMacroModules = categoryCount("Macros")
        if (classifiedMacroModules != enabledMacroCount + catalogMacroCount) {
            /* Nothing failed here, so this is an observation and not a suppressed
             * read error. It names the descriptor classes it counted, bounded the
             * way the macro name collision bounds its list, because "6 against 4"
             * on its own leaves the administrator no way to tell a real gap from a
             * false positive. */
            List<String> classifiedDescriptors = new ArrayList<String>()
            for (ExtensionModuleInfo module : modules) {
                if (module.enabled == Boolean.TRUE && "Macros" == module.category &&
                    module.descriptorName != null && !classifiedDescriptors.contains(module.descriptorName)) {
                    classifiedDescriptors.add(module.descriptorName)
                }
            }
            Cfp.observe(diagnostics, observations, "macro cross-check: " + classifiedMacroModules +
                " enabled module(s) classified as \"Macros\", " + enabledMacroCount +
                " macro(s) enumerated from descriptors and " + catalogMacroCount +
                " from the instance-wide catalogue. Classified by descriptor class: " +
                Cfp.nameList(classifiedDescriptors) +
                " - the enumeration or the class name classification may be incomplete")
            diagnosticCount++
            observationCount++
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

    Map<String, Object> asMap(boolean includeModules, ImpactAssessment impact,
                              boolean includeArchived = false) {
        List<Map<String, Object>> macroMaps = new ArrayList<Map<String, Object>>()
        for (MacroFootprint macro : macros) {
            macroMaps.add(macro.asMap(includeArchived))
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
                state: includeArchived ?
                    (archivedUsagePartial ? Cfp.PARTIAL : Cfp.MEASURED) : Cfp.DISABLED,
                detected: includeArchived ? hasArchivedFootprint() : null,
                partial: includeArchived ? archivedUsagePartial : null,
                usedMacros: includeArchived ? archivedUsedMacroCount : null,
                uniqueContent: includeArchived ? archivedUniqueContentCount : null,
                macroContentAssociations: includeArchived ? archivedAssociations : null,
                affectedSpaces: includeArchived ? archivedSpaceCount : null
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

class ImpactAnalyzer {

    static ImpactAssessment special(String level, String label, int rank, String reason) {
        ImpactAssessment result = new ImpactAssessment()
        result.level = level
        result.label = label
        result.rank = rank
        result.reasons.add(reason)
        return result
    }

    static ImpactAssessment assessConfluence(
        AppFootprint app,
        boolean usageScanned,
        boolean archivedUsageScanned,
        Long currentContentTotal,
        Long currentSpaceTotal,
        boolean inventoryIncomplete
    ) {
        if (!usageScanned) {
            return special("NOT_SCANNED", "Usage not scanned", 1,
                "Macro usage scanning was disabled.")
        }

        List<ImpactDimension> dimensions = new ArrayList<ImpactDimension>()
        dimensions.add(new ImpactDimension(
            "currentContent", "Current content reach",
            app.currentUniqueContentCount, currentContentTotal, app.currentUsagePartial))
        dimensions.add(new ImpactDimension(
            "currentAssociations", "Current macro association density",
            app.currentAssociations, currentContentTotal, app.currentUsagePartial))
        dimensions.add(new ImpactDimension(
            "currentSpaces", "Current space reach",
            app.currentSpaceCount, currentSpaceTotal, app.currentUsagePartial))

        ImpactAssessment measured = ImpactPolicy.assess(
            dimensions, inventoryIncomplete || app.currentUsagePartial)
        boolean archivedIncomplete = !archivedUsageScanned || app.archivedUsagePartial
        if (measured.rank >= 4) {
            measured.partial = measured.partial || archivedIncomplete
            return measured
        }
        if (measured.level == "REVIEW_REQUIRED") {
            return measured
        }
        /* OP-1066. Ahead of every remaining branch, and deliberately ahead of the
         * archived one: an app whose macro names were never known is not a weaker
         * case of an incomplete archived scan, it is a case where nothing was
         * asked. Before this branch existed, such an app reached the closing
         * verdict below and read as a decommission candidate carrying nothing but
         * zeros. That it did not happen on the instance this was found on was luck
         * - the archived branch caught it first. */
        if (app.macroEnumerationNarrowed()) {
            ImpactAssessment narrowed = special("REVIEW_REQUIRED", "Review required", 2,
                "The app registers a macro host but declares no macro as a plugin module, and the " +
                "instance-wide macro catalogue did not answer. The content index is queried once per " +
                "known macro name, so no name was searched for. The macro figures of this app are not " +
                "measured and are not a zero.")
            narrowed.partial = true
            return narrowed
        }
        if (app.hasArchivedFootprint()) {
            ImpactAssessment legacy = special("LEGACY_ONLY", "Legacy only", 3,
                "No current macro footprint was detected, but archived content still depends on the app.")
            legacy.partial = archivedIncomplete
            return legacy
        }
        if (archivedIncomplete) {
            ImpactAssessment review = special("REVIEW_REQUIRED", "Review required", 2,
                "Archived macro usage was not completely measured; a zero footprint is not established.")
            review.partial = true
            return review
        }
        if (app.hasInventoryOnlyPersistenceSignals()) {
            return special("REVIEW_REQUIRED", "Review required", 2,
                "The app provides blueprint, template or custom-content capabilities whose persisted usage cannot be determined generically.")
        }
        measured.reasons.clear()
        measured.reasons.add("No generic macro/configuration footprint was detected. Runtime, UI, REST or proprietary app usage is still possible.")
        return measured
    }
}

class Analyzer {

    static ImpactAssessment assessImpact(
        AppFootprint app,
        boolean usageScanned,
        boolean archivedUsageScanned,
        Long currentContentTotal,
        Long currentSpaceTotal,
        boolean inventoryIncomplete
    ) {
        return ImpactAnalyzer.assessConfluence(
            app, usageScanned, archivedUsageScanned,
            currentContentTotal, currentSpaceTotal, inventoryIncomplete)
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

    /* Parent page candidates by title, inside one space. The previous version
     * offered only getPage(spaceKey, title), an exact match, so typing
     * "footprint" never found "Confluence App Footprint - Executive Summary".
     * That exact lookup is kept and stays the first entry, so nothing it used to
     * find is lost; everything after it comes from the content index.
     *
     * Every type used here was read with javap against Confluence 10.2.10, and
     * the call shape is the one scanMacroName already uses:
     * scan(List<Index>, SearchQuery, Set<String>, Consumer<Map<String,String[]>>).
     * PageService reads each hit back as the same persistence Page type used by
     * the established export path.
     *
     * Whole words, and a trailing star on the last token only. A LEADING star is
     * deliberately absent: its behaviour on a tokenised field is not documented,
     * and an undocumented wildcard is not something this endpoint ships.
     *
     * The index supplies content ids and nothing else. Title and space of every
     * hit are read back through PageService's id locator, so what the
     * administrator sees comes from the database - an index entry may name a page
     * that was deleted or moved since it was written, and a hit that no longer
     * resolves, or resolves into another space, is dropped rather than offered.
     *
     * A search that throws is reported as a failed search. "No such page" is said
     * only when the search answered and named nothing: the caller answers a miss
     * by creating a page, and a swallowed error would create a duplicate. */
    static Map<String, Object> searchPagesByTitle(SearchManager searchManager, PageService pageService,
                                                  String spaceKey, String query, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>()
        result.put("ok", Boolean.FALSE)
        result.put("error", null)
        result.put("pages", pages)
        result.put("truncated", Boolean.FALSE)

        Set<String> takenIds = new LinkedHashSet<String>()

        /* The exact hit first. It is the one title lookup in this file with a
         * documented null contract, and it stays on top so a title that is
         * already correct is always the first thing offered. */
        try {
            Page exact = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, query).getPage()
            if (exact != null && takenIds.add(exact.getIdAsString())) {
                pages.add(pageRow(exact))
            }
        } catch (Exception error) {
            result.put("error", "The page \"" + query + "\" could not be looked up in \"" + spaceKey + "\" (" +
                PageExport.errorDetail(error) + "). That is a failed read, not a space without that page.")
            return result
        }

        List<String> tokens = PageExport.titleTokens(query)
        if (tokens.isEmpty()) {
            /* Punctuation only - there is no word to search for. The exact hit
             * above, if there was one, still stands. */
            result.put("ok", Boolean.TRUE)
            return result
        }

        final String contentIdField = SearchFieldMappings.CONTENT_ID.getName()
        final Set<String> requestedFields = new LinkedHashSet<String>()
        requestedFields.add(contentIdField)

        /* Four times the visible cap, so hits that resolve to null or into another
         * space can be dropped without emptying the list. Collection stops there;
         * seen keeps counting, which is how truncation is told apart from a result
         * set that simply fits. */
        final int idCap = limit * 4
        final List<String> hitIds = new ArrayList<String>()
        final int[] seen = new int[1]

        try {
            List<SearchQuery> clauses = new ArrayList<SearchQuery>()
            clauses.add(new ContentTypeQuery(ContentTypeEnum.PAGE))
            clauses.add(new InSpaceQuery(spaceKey))
            String titleField = SearchFieldMappings.TITLE.getName()
            if (tokens.size() > 1) {
                clauses.add(new TextFieldQuery(titleField,
                    String.join(" ", tokens.subList(0, tokens.size() - 1)), BooleanOperator.AND))
            }
            clauses.add(new WildcardTextFieldQuery(titleField,
                tokens.get(tokens.size() - 1) + "*", BooleanOperator.AND))

            searchManager.scan(
                Collections.singletonList(Index.CONTENT),
                BooleanQuery.andQuery(clauses.toArray(new SearchQuery[0])),
                requestedFields,
                new Consumer<Map<String, String[]>>() {
                    @Override
                    void accept(Map<String, String[]> document) {
                        String contentId = Cfp.firstValue(document, contentIdField)
                        if (contentId == null || contentId.trim().isEmpty()) {
                            return
                        }
                        seen[0]++
                        if (hitIds.size() < idCap) {
                            hitIds.add(contentId.trim())
                        }
                    }
                }
            )
        } catch (Exception error) {
            result.put("error", "The page search in \"" + spaceKey + "\" failed (" + PageExport.errorDetail(error) +
                "). That is a failed search, not a space without a matching page.")
            return result
        }

        List<Map<String, Object>> found = new ArrayList<Map<String, Object>>()
        for (String contentId : hitIds) {
            Page page = null
            try {
                page = pageService.getIdPageLocator(Long.parseLong(contentId)).getPage()
            } catch (Exception ignored) {
                /* One unreadable id costs one candidate, never the whole list.
                 * The search itself answered, which is the distinction that
                 * matters to the caller. */
                continue
            }
            if (page == null || !spaceKey.equalsIgnoreCase(String.valueOf(page.getSpaceKey()))) {
                continue
            }
            if (takenIds.add(page.getIdAsString())) {
                found.add(pageRow(page))
            }
        }
        found.sort { Map<String, Object> a, Map<String, Object> b ->
            return PageExport.str(a, "title", "").compareToIgnoreCase(PageExport.str(b, "title", ""))
        }

        for (Map<String, Object> row : found) {
            if (pages.size() >= limit) {
                result.put("truncated", Boolean.TRUE)
                break
            }
            pages.add(row)
        }
        if (seen[0] > hitIds.size()) {
            result.put("truncated", Boolean.TRUE)
        }

        result.put("ok", Boolean.TRUE)
        return result
    }

    private static Map<String, Object> pageRow(Page page) {
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("id", page.getIdAsString())
        row.put("title", page.getTitle())
        return row
    }
}


/* =============================================================================
 * The database read path
 *
 * SQL is the route of last resort in this script and it is taken for exactly one
 * thing: the space list the export form offers. The Java API that used to answer
 * it, com.atlassian.confluence.api.service.content.SpaceService, is a Spring AOP
 * proxy whose resolution throws inside a ScriptRunner REST endpoint - the measured
 * message stands at the import block on top of this file.
 *
 * The access shape is the one the sibling space-configuration script measured on a
 * live instance: TransactionalExecutorFactory, then createReadOnly(), then
 * execute(callback). NO SAL rdbms type is named statically. The callback interface
 * is loaded by name and implemented with a JDK proxy, so this file still compiles
 * on an instance where the package is absent instead of failing to start.
 *
 * createReadOnly() is not decoration. It is the one thing that makes the read-only
 * claim of this script enforceable rather than a matter of reviewing every
 * statement by eye. The single statement is a SELECT and its single value travels
 * as a bound parameter; nothing is concatenated into SQL anywhere below.
 * ========================================================================== */

class Db {

    static final String EXECUTOR_FACTORY = "com.atlassian.sal.api.rdbms.TransactionalExecutorFactory"
    static final String CONNECTION_CALLBACK = "com.atlassian.sal.api.rdbms.ConnectionCallback"

    /* Exception class plus message, clamped. PageExport.errorDetail says the same
     * thing and is deliberately not reused: that class sits inside the block the
     * offline suite compiles and this one cannot, because it names JDBC types. */
    static String why(Throwable error) {
        if (error == null) {
            return null
        }
        String message = error.getMessage()
        String detail = error.getClass().getSimpleName()
        if (message != null && !message.trim().isEmpty()) {
            detail = detail + " - " + message.trim()
        }
        return detail.length() > 300 ? detail.substring(0, 300) + " [clamped]" : detail
    }

    /* The factory, or null with the reason in the returned map. Acquisition and
     * resolution are different questions: a type that resolves and yields no
     * component has to read differently from a type that could not be loaded, and
     * neither of them may read like a database without spaces. */
    static Map<String, Object> factory() {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("factory", null)
        out.put("failure", null)
        try {
            Object component = ComponentLocator.getComponent(Class.forName(EXECUTOR_FACTORY))
            if (component == null) {
                out.put("failure", "The SAL read-only executor factory resolved but no component was " +
                    "returned, so no statement was attempted.")
            } else {
                out.put("factory", component)
            }
        } catch (Throwable error) {
            out.put("failure", "The SAL read-only executor factory could not be obtained: " + why(error) +
                ". No statement was attempted.")
        }
        return out
    }

    /* The body handed over as an implementation of the SAL callback interface, which
     * is known only as a runtime Class and is therefore implemented with a JDK proxy.
     * Extracted so the self-check below can build one without running a statement:
     * whether a callback can be built at all is one of the four things it reports. */
    static Object callback(Class callbackType, Closure body) {
        return Proxy.newProxyInstance(
            callbackType.getClassLoader(), [callbackType] as Class[],
            new InvocationHandler() {
                Object invoke(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName()
                    if (name == "execute") {
                        return body.call(arguments[0])
                    }
                    if (name == "toString") {
                        return "appFootprint-callback"
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
    }

    /* Runs the body against a read-only connection. The callback type is loaded by
     * name and implemented with a JDK proxy for the reason stated above, and the
     * factory is called through InvokerHelper rather than as a named method on an
     * Object: a dynamic method name would show up as an error in the ScriptRunner
     * editor, which is the one place an administrator reads this file before
     * running it. Failures are thrown, never swallowed - the caller turns them into
     * a refusal that names the reason. */
    static Object withConnection(Object executorFactory, Closure body) {
        Class callbackType = Class.forName(CONNECTION_CALLBACK)
        Object executor = InvokerHelper.invokeMethod(executorFactory, "createReadOnly", new Object[0])
        if (executor == null) {
            throw new IllegalStateException("createReadOnly() returned no executor")
        }
        return InvokerHelper.invokeMethod(executor, "execute",
            [callback(callbackType, body)] as Object[])
    }

    /* The Confluence schema is not a public API, so a column this script names can
     * disappear in an upgrade. It is read through the database catalogue BEFORE the
     * statement runs rather than by running the statement and seeing what happens: a
     * missing column has to produce a sentence naming it, not a picker that lists
     * nothing - or, if the vanished column is the one restricted on, a picker that
     * silently lists every space including the archived ones.
     *
     * Returns the missing columns, or a failure when the catalogue itself could not
     * be read. Those two are different answers and are never merged. */
    static Map<String, Object> shape(Connection connection, String table, List<String> required) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("table", table)
        out.put("missing", new ArrayList<String>())
        out.put("failure", null)
        try {
            Set<String> present = new LinkedHashSet<String>()
            DatabaseMetaData meta = connection.getMetaData()
            /* Identifier case is a property of the database, not of this file.
             * PostgreSQL folds unquoted names to lower case and other engines fold
             * to upper, so both spellings are asked for and the first that answers
             * wins. */
            for (String candidate : [table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)]) {
                ResultSet columns = meta.getColumns(null, null, candidate, null)
                try {
                    while (columns.next()) {
                        String name = columns.getString("COLUMN_NAME")
                        if (name != null) {
                            present.add(name.toLowerCase(Locale.ROOT))
                        }
                    }
                } finally {
                    columns.close()
                }
                if (!present.isEmpty()) {
                    break
                }
            }
            List<String> missing = (List<String>) out.get("missing")
            for (String column : required) {
                if (!present.contains(column.toLowerCase(Locale.ROOT))) {
                    missing.add(column)
                }
            }
        } catch (Throwable error) {
            out.put("failure", "The database catalogue could not be read: " + why(error))
        }
        return out
    }

    /* A read that failed and a read that found nothing return different objects. A
     * fail-soft accessor answering the same way for both is exactly how a failed
     * read turns into a proven absence, so the failure travels WITH the result. */
    static Map<String, Object> query(Connection connection, String sql, List<String> arguments,
                                     List<String> columns, int cap) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>()
        out.put("rows", rows)
        out.put("truncated", Boolean.FALSE)
        out.put("failure", null)
        out.put("cap", Integer.valueOf(cap))
        PreparedStatement statement = null
        try {
            statement = connection.prepareStatement(sql)
            for (int index = 0; index < arguments.size(); index++) {
                statement.setString(index + 1, arguments.get(index))
            }
            ResultSet results = statement.executeQuery()
            try {
                while (results.next()) {
                    if (rows.size() >= cap) {
                        /* Reached only when a row exists BEYOND the cap, so a result
                         * that ends exactly at the cap is not announced as cut. A
                         * report that cannot tell those apart announces a truncation
                         * that did not happen, or hides one that did. */
                        out.put("truncated", Boolean.TRUE)
                        break
                    }
                    Map<String, String> row = new LinkedHashMap<String, String>()
                    for (String column : columns) {
                        row.put(column, results.getString(column))
                    }
                    rows.add(row)
                }
            } finally {
                results.close()
            }
        } catch (Throwable error) {
            out.put("failure", "The statement failed: " + why(error))
        } finally {
            if (statement != null) {
                try {
                    statement.close()
                } catch (Throwable ignored) {
                    /* A close that fails changes nothing about the rows already read
                     * and must not turn a successful read into a failed one. */
                }
            }
        }
        return out
    }

    /* The picker read in one place: verify the shape, run the statement, and let
     * SpaceCatalog decide between a list and a refusal. The statement is not run at
     * all when a column it names is missing - running it anyway would either fail
     * with a database error saying less than the catalogue already said or, if the
     * missing column is the one restricted on, hand back every space there is. */
    static Map<String, Object> spaceRows(Connection connection) {
        if (connection == null) {
            return SpaceCatalog.spaceList(null, null)
        }
        Map<String, Object> shape = shape(connection, SpaceCatalog.TABLE, SpaceCatalog.COLUMNS)
        if (SpaceCatalog.shapeProblem(shape) != null) {
            return SpaceCatalog.spaceList(shape, null)
        }
        Map<String, Object> found = query(connection, SpaceCatalog.SQL, [SpaceCatalog.STATUS_CURRENT],
            SpaceCatalog.READ, SpaceCatalog.CAP)
        return SpaceCatalog.spaceList(shape, found)
    }

    /* The read path asked whether it resolves, one building block at a time.
     *
     * This method reports faults, so it may not raise one: every step is guarded on
     * its own, and a step that could not be attempted says so instead of counting
     * as a pass. The wording and every decision about it live in SelfCheck, which
     * names nothing that needs an instance and is therefore under test offline.
     *
     * deep=false is what a standard report pays for: one component lookup and one
     * class load. deep=true additionally creates the executor and reads the
     * catalogue, which opens a database connection, and is reached from diag=true
     * alone. */
    static List<Map<String, Object>> probe(boolean deep) {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>()

        Map<String, Object> resolved = factory()
        Object executorFactory = resolved.get("factory")
        steps.add(SelfCheck.step(SelfCheck.STEP_FACTORY, executorFactory != null,
            executorFactory == null ? String.valueOf(resolved.get("failure")) : null))

        Class callbackType = null
        String callbackFailure = null
        try {
            Class loaded = Class.forName(CONNECTION_CALLBACK)
            /* Loading the interface is half the question. Whether an implementation
             * can be built for it is the other half, and it is the half the read
             * actually depends on. */
            Closure nothing = { Object connection -> return null }
            if (callback(loaded, nothing) == null) {
                callbackFailure = "The callback interface loaded but no implementation could be built for it."
            } else {
                callbackType = loaded
            }
        } catch (Throwable error) {
            callbackFailure = "The connection callback interface could not be loaded: " + why(error)
        }
        steps.add(SelfCheck.step(SelfCheck.STEP_CALLBACK, callbackType != null, callbackFailure))

        Object executor = null
        if (!deep) {
            steps.add(SelfCheck.onRequest(SelfCheck.STEP_EXECUTOR))
        } else if (executorFactory == null) {
            steps.add(SelfCheck.blocked(SelfCheck.STEP_EXECUTOR, SelfCheck.STEP_FACTORY))
        } else {
            try {
                executor = InvokerHelper.invokeMethod(executorFactory, "createReadOnly", new Object[0])
                steps.add(SelfCheck.step(SelfCheck.STEP_EXECUTOR, executor != null,
                    "createReadOnly() returned no executor."))
            } catch (Throwable error) {
                steps.add(SelfCheck.step(SelfCheck.STEP_EXECUTOR, false,
                    "The read-only executor could not be created: " + why(error)))
            }
        }

        if (!deep) {
            steps.add(SelfCheck.onRequest(SelfCheck.STEP_CATALOGUE))
        } else if (executor == null || callbackType == null) {
            steps.add(SelfCheck.blocked(SelfCheck.STEP_CATALOGUE,
                executor == null ? SelfCheck.STEP_EXECUTOR : SelfCheck.STEP_CALLBACK))
        } else {
            try {
                Closure read = { Connection connection ->
                    return shape(connection, SpaceCatalog.TABLE, SpaceCatalog.COLUMNS)
                }
                Object answer = InvokerHelper.invokeMethod(executor, "execute",
                    [callback(callbackType, read)] as Object[])
                steps.add(SelfCheck.catalogue(answer instanceof Map ? (Map<String, Object>) answer : null))
            } catch (Throwable error) {
                steps.add(SelfCheck.step(SelfCheck.STEP_CATALOGUE, false,
                    "The database catalogue could not be read: " + why(error)))
            }
        }
        return steps
    }
}


/* =============================================================================
 * Confluence page export - decision read
 * ========================================================================== */

/* Result of reading the Decision column back from an existing export page.
 * The three outcomes are kept distinguishable on purpose: a failed read must
 * never look like "this page has no decisions yet", because the caller would
 * then render an empty Decision column and overwrite every administrator note.
 * Same discipline as the usage measurement above - a failed read is never
 * reported as an empty or zero result. */
/* OP-1066. The instance-wide macro catalogue, as a second source of macro names.
 *
 * WHY THIS EXISTS. The descriptor walk records a macro only when the module
 * descriptor implements MacroMetadataSource. An app that registers one generic
 * host module and instantiates its macros at runtime out of its own storage
 * therefore contributes no name at all, and a name is exactly what the content
 * index is queried with: Analyzer.scanMacroName runs once per known macro and
 * builds a MacroUsageQuery from that string. No name means no query, and the
 * figures that come out are unasked rather than unfound.
 *
 * Measured on a customer instance against Adaptavist ScriptRunner for Confluence
 * 10.8.0: 99 enabled modules, one of them classified as a macro module, zero
 * macros enumerated, while the app's own registry export held 17 script macros.
 *
 * THE SOURCE. MacroMetadataManager.getAllMacroMetadata() is documented as
 * "Retrieve all available metadata for macros in the system", and each
 * MacroMetadata carries getMacroName() and getPluginKey(). Attribution therefore
 * needs no heuristic and nothing is guessed: a name is attributed to the app whose
 * plugin key the catalogue itself names, or to nobody.
 *
 * RESOLVED BY NAME, exactly like Db. The manager is a Spring component and can
 * reach a ScriptRunner endpoint as an AOP proxy; naming such a type statically is
 * what took the export space picker down twice (OP-1008, OP-1063). Every read is
 * guarded separately, and a failure is reported as a failure. An empty catalogue
 * and an unreachable one must never produce the same result, because the whole
 * point of this class is that a zero says whether anything was asked. */
class MacroCatalog {

    static final String MANAGER = "com.atlassian.confluence.macro.browser.MacroMetadataManager"

    /* Keys: ok (Boolean), failure (String or null), byPlugin (plugin key to the
     * list of macro names it owns), total (int, names accepted overall). byPlugin
     * is always a map, never null, so a caller cannot read a failure as an empty
     * catalogue by accident - it has to look at ok. */
    static Map<String, Object> load() {
        Map<String, List<String>> byPlugin = new LinkedHashMap<String, List<String>>()
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("ok", Boolean.FALSE)
        out.put("failure", null)
        out.put("byPlugin", byPlugin)
        out.put("total", Integer.valueOf(0))

        Object manager = null
        try {
            manager = ComponentLocator.getComponent(Class.forName(MANAGER))
        } catch (Throwable error) {
            out.put("failure", "The instance-wide macro catalogue could not be obtained: " +
                Db.why(error) + ". No macro name was taken from it.")
            return out
        }
        if (manager == null) {
            out.put("failure", "The instance-wide macro catalogue resolved but no component was " +
                "returned. No macro name was taken from it.")
            return out
        }

        Object all = null
        try {
            all = InvokerHelper.invokeMethod(manager, "getAllMacroMetadata", new Object[0])
        } catch (Throwable error) {
            out.put("failure", "The instance-wide macro catalogue was reached but did not answer: " +
                Db.why(error) + ". No macro name was taken from it.")
            return out
        }
        if (!(all instanceof Collection)) {
            out.put("failure", "The instance-wide macro catalogue answered with " +
                (all == null ? "nothing" : all.getClass().getSimpleName()) +
                " instead of a collection. No macro name was taken from it.")
            return out
        }

        /* The catalogue answered. From here a name that cannot be read is a skipped
         * name and not a failed catalogue, so ok is already true: the difference
         * that matters to every caller is whether the question was asked at all. */
        int total = 0
        int unattributed = 0
        for (Object entry : (Collection) all) {
            String macroName = readString(entry, "getMacroName")
            if (macroName == null || macroName.trim().isEmpty()) {
                continue
            }
            /* getPluginKey is read as guardedly as the name rather than trusted: it is
             * verified present on MacroMetadata in the Confluence javadoc, but this
             * file runs on whatever version the customer has, and a macro that cannot
             * be attributed has to stay unattributed instead of throwing. */
            String pluginKey = readString(entry, "getPluginKey")
            if (pluginKey == null || pluginKey.trim().isEmpty()) {
                unattributed++
                continue
            }
            List<String> names = byPlugin.get(pluginKey)
            if (names == null) {
                names = new ArrayList<String>()
                byPlugin.put(pluginKey, names)
            }
            if (!names.contains(macroName)) {
                names.add(macroName)
                total++
            }
        }

        out.put("ok", Boolean.TRUE)
        out.put("total", Integer.valueOf(total))
        if (unattributed > 0) {
            out.put("failure", String.valueOf(unattributed) + " macro name(s) in the instance-wide " +
                "catalogue name no owning app and were therefore not attributed to one.")
        }
        return out
    }

    /* One string off one catalogue entry, invoked by name for the same reason the
     * manager itself is resolved by name. A value that cannot be read costs this one
     * entry its attribution and never the walk over the rest of the catalogue. */
    private static String readString(Object entry, String getter) {
        try {
            return (String) InvokerHelper.invokeMethod(entry, getter, new Object[0])
        } catch (Throwable ignored) {
            return null
        }
    }
}

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
    static final String DEFAULT_TITLE = "Confluence App Footprint - Executive Summary"

    static final int MAX_PAYLOAD_CHARS = 4000000
    static final int MAX_TITLE_CHARS = 255

    /* Search stages. The space search shows at most this many matches and ignores
     * a shorter term, so a single keystroke never renders the whole instance. */
    static final int SEARCH_LIMIT = 25
    static final int MIN_SEARCH_CHARS = 2

    /* Idle pause before a typed parent title is searched for. The field has no
     * button, so the search is what typing does - but not once per keystroke:
     * that is a call per character and a list rebuilt faster than it can be read. */
    static final int SEARCH_IDLE_MS = 300

    /* Upper bound on the words a title search is built from. Every token becomes
     * one clause of an AND query, and an unbounded clause count is an unbounded
     * query. Beyond this the extra words add nothing: the first few already cut
     * the result set down to what fits on the screen. */
    static final int MAX_TITLE_TOKENS = 8

    /* Whole words of a title search, in the order they were typed. Everything
     * that is neither a letter nor a digit separates, which is what a tokenised
     * title field does anyway, and which also means no character with a meaning
     * in the query language can survive into a term. Umlauts and every other
     * non-ASCII letter are letters and are kept. The caller appends the one
     * trailing star it wants; a term can therefore never start with one. */
    static List<String> titleTokens(String query) {
        List<String> tokens = new ArrayList<String>()
        if (query == null) {
            return tokens
        }
        StringBuilder current = new StringBuilder()
        for (int i = 0; i < query.length() && tokens.size() < MAX_TITLE_TOKENS; i++) {
            char character = query.charAt(i)
            if (Character.isLetterOrDigit(character)) {
                current.append(character)
                continue
            }
            if (current.length() > 0) {
                tokens.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length() > 0 && tokens.size() < MAX_TITLE_TOKENS) {
            tokens.add(current.toString())
        }
        return tokens
    }

    /* A request carries either the id of a picked parent or the title of one to
     * be created, never both. The refusal text is a constant so the offline suite
     * can assert on the contract rather than on a copy of the sentence. */
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

    /* The direct parent out of an ancestor chain, kept apart from the case where
     * no chain arrived at all. Ancestors run from the root of the space downwards,
     * so the direct parent is the last entry that names an id.
     *
     * measured=true with a null parentId means the read answered and the page sits
     * at the top level of the space - a real measurement. measured=false means no
     * chain was readable, which measures nothing and must never be read as "the
     * page has no parent".
     *
     * It takes the ids rather than the pages, so the rule stays free of Confluence
     * types and the offline suite can check it. The Jira endpoint holds the same
     * rule against a parsed JSON response instead. */
    static Map<String, Object> innermostAncestor(List<String> ancestorIds) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("measured", Boolean.FALSE)
        out.put("parentId", null)
        if (ancestorIds == null) {
            return out
        }
        out.put("measured", Boolean.TRUE)
        for (int i = ancestorIds.size() - 1; i >= 0; i--) {
            String id = ancestorIds.get(i)
            if (id != null && !id.trim().isEmpty()) {
                out.put("parentId", id.trim())
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
    static final String PARENT_BODY = "<p>Container page for the Confluence App Footprint Analysis export. " +
        "It was created by that export because the chosen parent page did not exist yet. " +
        "The report itself is the child page below; this page carries no report data and is never rewritten.</p>"

    /* Confluence hands empty cells back self-closed after an editor round trip,
     * so both forms are matched. The self-closing alternative has to come first,
     * otherwise <td/> is consumed by the open-tag branch and swallows the row. */
    static final Pattern TBODY = Pattern.compile("(?s)<tbody[^>]*>(.*?)</tbody>")
    static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>")
    static final Pattern CELL = Pattern.compile("(?s)<t[hd][^>]*/>|<t[hd][^>]*>(.*?)</t[hd]>")
    static final Pattern TAG = Pattern.compile("<[^>]+>")

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
    static final Pattern LAYOUT_TAG = Pattern.compile("(?i)</?(?:p|br|div|span)(?:\\s[^>]*)?/?>")

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

    static final Pattern MACRO_ID = Pattern.compile("\\s+ac:macro-id=\"[^\"]*\"")

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
        Matcher matcher = CELL.matcher(rowHtml)
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
            Matcher bodyMatcher = TBODY.matcher(storage)

            while (bodyMatcher.find()) {
                List<String> rows = new ArrayList<String>()
                Matcher rowMatcher = ROW.matcher(bodyMatcher.group(1))
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
        return Cfp.number(Long.valueOf(lng(source, key)), locale)
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
     * whether a zero is a measured zero or an unmeasured one. */
    static String usageState(AppFootprint app, boolean scanUsage, boolean dimensionEnabled) {
        if (!dimensionEnabled) {
            return NOT_APPLICABLE
        }
        if (!scanUsage) {
            return Cfp.DISABLED
        }
        if (app.macros.isEmpty()) {
            return Cfp.MEASURED
        }
        int measured = 0
        boolean complete = true
        for (MacroFootprint macro : app.macros) {
            if (macro.isMeasured()) {
                measured++
            }
            if (macro.usageState != Cfp.MEASURED) {
                complete = false
            }
        }
        if (measured == 0) {
            return Cfp.BUDGET
        }
        return complete ? Cfp.MEASURED : Cfp.PARTIAL
    }

    /* Same three states for a whole-report total, driven by the model's partial flag. */
    static String summaryState(boolean dimensionEnabled, boolean scanUsage, boolean partial) {
        if (!dimensionEnabled) {
            return NOT_APPLICABLE
        }
        if (!scanUsage) {
            return Cfp.DISABLED
        }
        return partial ? Cfp.PARTIAL : Cfp.MEASURED
    }

    /* A value the report shows as n/m must not appear as 0 on the page. */
    static String usageText(String state, Number value, Locale locale) {
        if (state == NOT_APPLICABLE) {
            return Cfp.NA
        }
        if (state == Cfp.MEASURED) {
            return Cfp.number(value, locale)
        }
        if (state == Cfp.PARTIAL) {
            return Cfp.number(value, locale) + " *"
        }
        if (state == Cfp.DISABLED) {
            return "off"
        }
        return "n/m"
    }

    /* ---- Storage format ---------------------------------------------------- */

    static String cell(String innerHtml) {
        return "<td><p>" + innerHtml + "</p></td>"
    }

    static String head(String label) {
        return "<th><p>" + Cfp.html(label) + "</p></th>"
    }

    static String metricRow(String label, String value) {
        return "<tr>" + cell(Cfp.html(label)) + cell(Cfp.html(value)) + "</tr>"
    }

    /* The one place a carried-over decision is written out. Raw cell content, so
     * an administrator's own wording next to KEEP survives, not just the keyword.
     * Never escaped and never regenerated - it is the administrator's text. */
    static String decisionCell(String decisionHtml) {
        return decisionHtml == null ? cell("&#160;") : "<td>" + decisionHtml + "</td>"
    }

    /* Which instance these numbers came from. The Jira sibling carries the same
     * block. A page that outlives the run has to say what it describes. */
    static String renderInstance(Map<String, Object> report) {
        String baseUrl = str(report, "baseUrl", "")
        String title = str(report, "siteTitle", "")
        String version = str(report, "instanceVersion", "")
        String build = str(report, "instanceBuild", "")
        if (baseUrl.isEmpty() && title.isEmpty() && version.isEmpty() && build.isEmpty()) {
            return ""
        }
        StringBuilder out = new StringBuilder()
        out.append("<h2>Instance</h2>")
        out.append("<table><tbody><tr>").append(head("Property")).append(head("Value")).append("</tr>")
        out.append(metricRow("Report version", Cfp.VERSION))
        out.append(metricRow("Instance", title.isEmpty() ? Cfp.NA : title))
        out.append(metricRow("Base URL", baseUrl.isEmpty() ? Cfp.NA : baseUrl))
        out.append(metricRow("Confluence version", version.isEmpty() ? Cfp.NA : version))
        out.append(metricRow("Confluence build", build.isEmpty() ? Cfp.NA : build))
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderSummary(Map<String, Object> summary, Map<String, Object> options, Locale locale) {
        Map<String, Object> impact = sub(summary, "impact")
        Map<String, Object> capabilities = sub(summary, "capabilities")
        Map<String, Object> current = sub(summary, "current")
        Map<String, Object> archived = sub(summary, "archived")
        Map<String, Object> userMacros = sub(summary, "nativeUserMacros")

        boolean scanUsage = flag(options, "scanUsage")
        String currentState = summaryState(true, scanUsage, flag(current, "partial"))
        String archivedState = summaryState(flag(options, "includeArchived"), scanUsage, flag(archived, "partial"))

        StringBuilder out = new StringBuilder()
        out.append("<h2>Key Figures</h2>")
        out.append("<table><tbody><tr>").append(head("Metric")).append(head("Value")).append("</tr>")
        out.append(metricRow("Apps in report", numberOf(summary, "apps", locale)))
        out.append(metricRow("Disabled apps", numberOf(summary, "disabledApps", locale)))
        out.append(metricRow("Decommission candidates", numberOf(summary, "decommissionCandidates", locale)))
        out.append(metricRow("Apps with a current footprint", numberOf(summary, "appsWithCurrentFootprint", locale)))
        out.append(metricRow("Apps with an archived footprint", numberOf(summary, "appsWithArchivedFootprint", locale)))
        out.append(metricRow("Provided app macros", numberOf(capabilities, "providedMacros", locale)))
        out.append(metricRow("Enabled app macros", numberOf(capabilities, "enabledMacros", locale)))
        out.append(metricRow("Used app macros, current", usageText(currentState, Long.valueOf(lng(current, "usedAppMacros")), locale)))
        out.append(metricRow("Unique current content", usageText(currentState, Long.valueOf(lng(current, "uniqueContent")), locale)))
        out.append(metricRow("Macro-content associations, current", usageText(currentState, Long.valueOf(lng(current, "macroContentAssociations")), locale)))
        out.append(metricRow("Affected current spaces", usageText(currentState, Long.valueOf(lng(current, "affectedSpaces")), locale)))
        out.append(metricRow("Used app macros, archived", usageText(archivedState, Long.valueOf(lng(archived, "usedAppMacros")), locale)))
        out.append(metricRow("Macro-content associations, archived", usageText(archivedState, Long.valueOf(lng(archived, "macroContentAssociations")), locale)))
        out.append(metricRow("Blueprints and templates", Cfp.number(Long.valueOf(lng(capabilities, "blueprints") + lng(capabilities, "templates")), locale)))
        out.append(metricRow("Custom content modules", numberOf(capabilities, "customContentModules", locale)))
        out.append(metricRow("Native user macros defined", numberOf(userMacros, "defined", locale)))
        out.append(metricRow("Macro scans skipped by budget", numberOf(summary, "macrosSkippedByBudget", locale)))
        out.append(metricRow("Suppressed read errors", numberOf(summary, "readErrors", locale)))
        out.append(metricRow("Observations", numberOf(summary, "observations", locale)))
        out.append("</tbody></table>")

        out.append("<h2>Impact Distribution</h2>")
        out.append("<table><tbody><tr>").append(head("Impact")).append(head("Apps")).append("</tr>")
        out.append(metricRow("Critical", numberOf(impact, "critical", locale)))
        out.append(metricRow("High", numberOf(impact, "high", locale)))
        out.append(metricRow("Medium", numberOf(impact, "medium", locale)))
        out.append(metricRow("Low", numberOf(impact, "low", locale)))
        out.append(metricRow("Legacy only", numberOf(impact, "legacyOnly", locale)))
        out.append(metricRow("Review required", numberOf(impact, "reviewRequired", locale)))
        out.append(metricRow("No detectable footprint", numberOf(impact, "noDetectableFootprint", locale)))
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderApps(List<Map<String, Object>> apps, DecisionRead read, ExportOutcome outcome, Locale locale) {
        StringBuilder out = new StringBuilder()
        out.append("<h2>Apps and Decisions</h2>")
        out.append("<table><tbody><tr>")
        out.append(head("App")).append(head(COL_KEY)).append(head("Vendor")).append(head("Version"))
        out.append(head("Impact")).append(head("Current Content")).append(head("Current Spaces"))
        out.append(head("Archived Content")).append(head("Status")).append(head(COL_NOTES)).append(head(COL_DECISION))
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
            String status = flag(app, "enabled") ? "Enabled" : "Disabled"

            List<String> notes = new ArrayList<String>()
            if (flag(app, "systemProvided")) {
                notes.add("System provided")
            }
            long readErrors = lng(app, "diagnostics") - lng(app, "observations")
            if (readErrors > 0L) {
                notes.add("Suppressed read errors " + Cfp.number(Long.valueOf(readErrors), locale))
            }
            if (lng(app, "observations") > 0L) {
                notes.add("Observations " + numberOf(app, "observations", locale))
            }

            String currentState = str(app, "currentState", Cfp.BUDGET)
            String archivedState = str(app, "archivedState", Cfp.BUDGET)

            out.append("<tr>")
            out.append(cell(Cfp.html(str(app, "displayName", pluginKey))))
            out.append(cell(Cfp.html(pluginKey)))
            out.append(cell(Cfp.html(str(app, "vendor", Cfp.NA))))
            out.append(cell(Cfp.html(str(app, "version", Cfp.NA))))
            out.append(cell(Cfp.html(str(app, "impactLabel", str(app, "impactLevel", Cfp.NA)))))
            out.append(cell(Cfp.html(usageText(currentState, Long.valueOf(lng(app, "currentContent")), locale))))
            out.append(cell(Cfp.html(usageText(currentState, Long.valueOf(lng(app, "currentSpaces")), locale))))
            out.append(cell(Cfp.html(usageText(archivedState, Long.valueOf(lng(app, "archivedContent")), locale))))
            out.append(cell(Cfp.html(status)))
            out.append(cell(Cfp.html(notes.isEmpty() ? Cfp.NA : String.join(", ", notes))))
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
        out.append(Cfp.html(String.valueOf(outcome.orphanKeys.size()) + " decision(s) from the previous version of this page could not be matched to an app in this report. " +
            "The app is no longer installed, or the current report options filter it out. The notes are kept here and are read back on the next run."))
        out.append("</p>")
        out.append("<table><tbody><tr>").append(head(COL_KEY)).append(head(COL_DECISION)).append("</tr>")
        for (String key : outcome.orphanKeys) {
            out.append("<tr>").append(cell(Cfp.html(key)))
            out.append(decisionCell(read.decisions.get(key)))
            out.append("</tr>")
        }
        out.append("</tbody></table>")
        return out.toString()
    }

    static String renderNotes(Map<String, Object> options, Locale locale) {
        StringBuilder out = new StringBuilder()
        out.append("<h2>Reading This Page</h2><ul>")
        out.append("<li>").append(Cfp.html("Everything except the " + COL_DECISION + " column is regenerated on each run. Edits to any other column are lost on the next run.")).append("</li>")
        out.append("<li>").append(Cfp.html("The " + COL_DECISION + " column is keyed by " + COL_KEY + ". Write KEEP, REMOVE or free text; the cell is carried over verbatim.")).append("</li>")
        out.append("<li>").append(Cfp.html("n/m means not measured, not zero. off means the usage scan was switched off for this run. A trailing * marks a lower bound.")).append("</li>")
        out.append("<li>").append(Cfp.html("Current usage only counts content in spaces with SpaceStatus.CURRENT. Archived spaces are reported separately.")).append("</li>")
        out.append("<li>").append(Cfp.html("Report options for this run: includeSystem=" + String.valueOf(flag(options, "includeSystem")) +
            ", includeDisabled=" + String.valueOf(flag(options, "includeDisabled")) +
            ", includeArchived=" + String.valueOf(flag(options, "includeArchived")) +
            ", scanUsage=" + String.valueOf(flag(options, "scanUsage")) +
            ", scanAliases=" + String.valueOf(flag(options, "scanAliases")) +
            ", scanBudgetMs=" + numberOf(options, "scanBudgetMs", locale) + ".")).append("</li>")
        out.append("<li>").append(Cfp.html("Impact is a local assessment heuristic configured in the report script; it is not an Atlassian classification.")).append("</li>")
        out.append("</ul>")
        return out.toString()
    }

    static ExportOutcome render(Map<String, Object> payload, DecisionRead read, Locale locale) {
        ExportOutcome outcome = new ExportOutcome()
        outcome.decisionsRead = read.decisions.size()

        Map<String, Object> report = sub(payload, "report")
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
        out.append(Cfp.html("Generated " + str(report, "generatedAt", Cfp.NA) +
            " \u00B7 report version " + Cfp.VERSION +
            " \u00B7 export marker " + MARKER + " \u00B7 do not remove this line."))
        out.append("</em></p>")
        out.append("<p>")
        out.append(Cfp.html("This page is regenerated by the Confluence App Footprint Analysis endpoint. Every column except "))
        out.append("<strong>").append(Cfp.html(COL_DECISION)).append("</strong>")
        out.append(Cfp.html(" is overwritten on each run. The " + COL_DECISION + " column is read back from this page and carried over."))
        out.append("</p>")

        if (!outcome.orphanKeys.isEmpty()) {
            String warning = String.valueOf(outcome.orphanKeys.size()) + " of " + String.valueOf(outcome.decisionsRead) +
                " decision(s) could not be matched to an app in this report and were moved to \"Decisions Without a Matching App\"."
            outcome.warnings.add(warning)
            out.append("<p><strong>").append(Cfp.html("Carry-over warning: ")).append("</strong>").append(Cfp.html(warning)).append("</p>")
        }

        out.append(renderInstance(report))
        out.append(renderSummary(summary, options, locale))
        out.append(appTable)
        out.append(renderOrphans(read, outcome))
        out.append(renderNotes(options, locale))

        outcome.storage = out.toString()
        return outcome
    }
}


/* =============================================================================
 * Confluence page export - the space picker
 * ========================================================================== */

/* The list of spaces the export form offers. It is read from the SPACES table and
 * not from a Confluence service, and the reason is measured rather than stylistic:
 * the route this replaces resolved
 * com.atlassian.confluence.api.service.content.SpaceService, that concrete type is
 * a Spring AOP proxy, and its resolution throws inside a ScriptRunner endpoint. The
 * import block at the top of this file carries the verbatim message and where it
 * was measured. The picker refused on every instance it was opened on - the
 * fail-loud path doing its job over a read path that cannot work.
 *
 * This class carries the DECISIONS only. Everything touching JDBC sits in Db above,
 * so every branch below - a verified list, a named missing column, a failed read
 * that stays a failed read - is exercised by the offline suite with plain maps and
 * without a database. */
class SpaceCatalog {

    static final String TABLE = "spaces"

    /* The columns the shape check verifies before a single row is read. spacestatus
     * is on the list because the statement RESTRICTS on it: a column that vanished
     * in an upgrade has to surface as a failed read naming the column, never as a
     * list of every space including the archived ones and never as a silent full
     * list. */
    static final List<String> COLUMNS = ["spacekey", "spacename", "spacestatus"]

    /* The stored spelling of SpaceStatus.CURRENT. It is BOUND, never pasted: no
     * value is interpolated into SQL anywhere in this script and a constant of this
     * script's own making is no exception to that rule. */
    static final String STATUS_CURRENT = "CURRENT"

    /* Ordering happens in SQL rather than in Groovy afterwards, so that the cap below
     * cuts by the same order the browser shows. A cap announcing an ordering the
     * statement did not use is worse than one announcing none.
     *
     * No string literal goes into COALESCE, NULLIF, DECODE or a concatenation against
     * a text column. Confluence stores text as NVARCHAR2 on Oracle, a literal '' is
     * CHAR, and those functions require one character set across their arguments:
     * measured on a customer instance as ORA-12704 in the sibling script, on a
     * statement that had been reviewed as portable and only ever run on PostgreSQL. A
     * nameless space therefore sorts under NULL, which Oracle and PostgreSQL both
     * place last on an ascending sort, and is still LABELLED with its key below. */
    static final String SQL =
        "SELECT s.spacekey, s.spacename FROM spaces s WHERE s.spacestatus = ? " +
        "ORDER BY LOWER(s.spacename), LOWER(s.spacekey)"

    /* The columns actually taken off each row. Shorter than COLUMNS on purpose: the
     * status is verified and restricted on, never carried. */
    static final List<String> READ = ["spacekey", "spacename"]

    /* The ordering a cap announcement has to name. */
    static final String ORDER = "space name, then space key"

    /* A safety limit, not a product decision. It sits far above any instance this
     * report is meant for, and if it is ever reached the browser says so and says
     * which ordering the rows were taken by. */
    static final int CAP = 20000

    /* The sentence that keeps a failed read from being read as a measurement. Every
     * refusal of this stage ends in it, in the endpoint as well as here. */
    static final String NOT_EMPTY = "That is a failed read, not an instance without spaces."

    /* The shape check turned into the one sentence the picker needs, or null when the
     * table is as this script expects it. A catalogue that could not be read and a
     * column that is not there are different answers and are never merged. */
    static String shapeProblem(Map<String, Object> shape) {
        if (shape == null) {
            return "The columns this stage reads could not be verified, so nothing was read."
        }
        String failure = PageExport.str(shape, "failure", null)
        if (failure != null) {
            return failure + " The columns this stage reads could not be verified, so nothing was read."
        }
        Object raw = shape.get("missing")
        List<String> missing = raw instanceof List ? (List<String>) raw : null
        if (missing == null || missing.isEmpty()) {
            return null
        }
        return "The table " + PageExport.str(shape, "table", TABLE) + " does not carry " +
            (missing.size() == 1 ? "the column " : "the columns ") + missing.join(", ") +
            " on this instance. The Confluence schema is not a public API and an upgrade can " +
            "change it. Nothing was read rather than reporting an empty result."
    }

    /* The one place this stage decides between a list and a refusal.
     *
     * Both arguments are answers about the same read: what the catalogue said about
     * the table, and what the statement returned. A missing shape check or a missing
     * statement result is a failed read and never an empty list - and never a list
     * either, because a list built without the verified restriction would quietly
     * include the archived spaces this report keeps separate everywhere else. */
    static Map<String, Object> spaceList(Map<String, Object> shape, Map<String, Object> found) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        List<Map<String, Object>> spaces = new ArrayList<Map<String, Object>>()
        result.put("ok", Boolean.FALSE)
        result.put("error", null)
        result.put("spaces", spaces)
        result.put("truncated", Boolean.FALSE)
        result.put("cap", Integer.valueOf(CAP))
        result.put("order", ORDER)

        String problem = shapeProblem(shape)
        if (problem != null) {
            result.put("error", "The space list could not be read. " + problem + " " + NOT_EMPTY)
            return result
        }
        if (found == null) {
            result.put("error", "The space list could not be read: the statement returned no result " +
                "at all, so no space was seen. " + NOT_EMPTY)
            return result
        }
        String failure = PageExport.str(found, "failure", null)
        if (failure != null) {
            result.put("error", "The space list could not be read (" + failure + "). " + NOT_EMPTY)
            return result
        }

        Object rawRows = found.get("rows")
        for (Map<String, String> row : (rawRows instanceof List ? (List<Map<String, String>>) rawRows
                                                                : new ArrayList<Map<String, String>>())) {
            String key = PageExport.str((Map<String, Object>) row, "spacekey", null)
            if (key == null) {
                /* A row with no key names nothing that can be picked. It costs itself
                 * and never the list. */
                continue
            }
            Map<String, Object> space = new LinkedHashMap<String, Object>()
            space.put("key", key)
            space.put("name", PageExport.str((Map<String, Object>) row, "spacename", key))
            spaces.add(space)
        }
        result.put("truncated", found.get("truncated") == Boolean.TRUE ? Boolean.TRUE : Boolean.FALSE)
        result.put("ok", Boolean.TRUE)
        return result
    }
}


/* =============================================================================
 * The read path answering for itself
 * ========================================================================== */

/* Why this exists at all: on the instance this was written for, the server log is
 * not reachable by the people who run this report. A failure there is a five
 * hundred and a referral number, and a tool whose reason lives only in a log that
 * nobody in the room can open is not diagnosable. So the read path behind the
 * export states in the report itself which of its parts resolve.
 *
 * It is not free and therefore not automatic. Without diag=true only the two cheap
 * steps are attempted - one component lookup, one class load - and the page stays
 * SILENT unless one of them refuses. The two deep steps open a database connection
 * and run on request only. A diagnostic block printed under every standard report
 * is read twice and skipped for good after that; that lesson was paid for once
 * already with the unassessed banner.
 *
 * DECISIONS AND WORDING ONLY. Nothing here loads a class, resolves a component or
 * touches a connection - Db does that and hands the answers over as plain maps, so
 * every branch below is exercised by the offline suite without a database. */
class SelfCheck {

    static final String YES = "yes"
    static final String NO = "no"
    static final String NOT_ATTEMPTED = "not attempted"

    static final String STEP_FACTORY = "The SAL read-only executor factory is found by name"
    static final String STEP_CALLBACK = "The connection callback interface loads and can be implemented"
    static final String STEP_EXECUTOR = "A read-only executor can be created"
    static final String STEP_CATALOGUE = "The space table columns can be read from the database catalogue"

    /* A step nobody ran is UNKNOWN. It is never green, and it is never counted as a
     * failure either - both would be an answer this check does not have. */
    static final String ON_REQUEST =
        "Not attempted. This step opens a database connection and runs with diag=true only."

    static final String HINT = "Add diag=true to this URL for the full self-check of that path."

    /* The report on the page reads none of this. Saying so is not reassurance, it
     * keeps a refusal here from being read as a doubt about the figures above. */
    static final String SCOPE =
        "These are the parts the space picker of the export needs. The report on this page reads none " +
        "of them, so a refusal here says nothing about the figures above."

    static Map<String, Object> entry(String name, String state, String detail) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put("step", name)
        out.put("state", state)
        out.put("detail", detail)
        return out
    }

    /* A resolved step carries no detail, and an unresolved one never carries an
     * empty one: a refusal without a reason sends an administrator to the log this
     * whole check exists because they cannot read. */
    static Map<String, Object> step(String name, boolean resolved, String detail) {
        if (resolved) {
            return entry(name, YES, null)
        }
        String reason = detail == null || detail.trim().isEmpty() ? "No reason was reported." : detail.trim()
        return entry(name, NO, reason)
    }

    static Map<String, Object> onRequest(String name) {
        return entry(name, NOT_ATTEMPTED, ON_REQUEST)
    }

    /* A step that had nothing to run against because an earlier one refused. It is
     * not a second failure - reporting it as one would turn one broken building
     * block into a list of them and hide which one to fix. */
    static Map<String, Object> blocked(String name, String because) {
        return entry(name, NOT_ATTEMPTED, "Not attempted: \"" + because +
            "\" did not resolve, so this step had nothing to run against.")
    }

    /* The catalogue step reads its verdict off the same shape check the picker
     * runs, so the self-check cannot pass a table the export would then refuse. */
    static Map<String, Object> catalogue(Map<String, Object> shape) {
        if (shape == null) {
            return step(STEP_CATALOGUE, false,
                "The catalogue read returned nothing at all, so no column was verified.")
        }
        String problem = SpaceCatalog.shapeProblem(shape)
        return step(STEP_CATALOGUE, problem == null, problem)
    }

    /* Db unreachable as a whole. The point of this check is that the reason reaches
     * the browser, and "the class holding the read path could not be touched" is a
     * reason - a report that dies instead is the state this release is fixing. */
    static List<Map<String, Object>> unreachable(String detail) {
        String reason = "The read path could not be examined at all: " +
            (detail == null || detail.trim().isEmpty() ? "no reason was reported" : detail.trim()) + "."
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>()
        for (String name : [STEP_FACTORY, STEP_CALLBACK, STEP_EXECUTOR, STEP_CATALOGUE]) {
            steps.add(step(name, false, reason))
        }
        return steps
    }

    static List<Map<String, Object>> failures(List<Map<String, Object>> steps) {
        List<Map<String, Object>> broken = new ArrayList<Map<String, Object>>()
        if (steps == null) {
            return broken
        }
        for (Map<String, Object> step : steps) {
            if (step != null && NO == step.get("state")) {
                broken.add(step)
            }
        }
        return broken
    }

    /* Whether the standard report says anything at all. */
    static boolean shown(List<Map<String, Object>> steps) {
        return !failures(steps).isEmpty()
    }

    /* The one line a standard report prints, and only when something refused. Null
     * means the page stays silent, which is the normal case. */
    static String summary(List<Map<String, Object>> steps) {
        List<Map<String, Object>> broken = failures(steps)
        if (broken.isEmpty()) {
            return null
        }
        StringBuilder line = new StringBuilder("The read path behind the export did not fully resolve: ")
        for (int index = 0; index < broken.size(); index++) {
            if (index > 0) {
                line.append(" ")
            }
            line.append(String.valueOf(broken.get(index).get("step"))).append(" - ")
                .append(String.valueOf(broken.get(index).get("detail")))
        }
        return line.append(" ").append(SCOPE).append(" ").append(HINT).toString()
    }

    /* The section, printed on request only. Exception type and message, never a
     * stack trace and never a server path: this page is pasted into tickets. */
    static String html(List<Map<String, Object>> steps) {
        boolean broken = shown(steps)
        StringBuilder out = new StringBuilder()
        out.append("<div class=\"").append(broken ? Cfp.DIAG_WARN : Cfp.DIAG_INFO).append("\">")
        out.append("<strong>Read path self-check</strong>")
        out.append("<div style=\"margin-top:6px\">Requested with diag=true. ").append(SCOPE).append(" ")
        out.append(broken ? "A building block below did not resolve, and that is what the export fails on."
                          : "Every step that was attempted resolved.")
        out.append("</div>")
        out.append("<div class=\"table-wrap\"><table><thead><tr><th>Building block</th>")
        out.append("<th>Resolves</th><th>Detail</th></tr></thead><tbody>")
        for (Map<String, Object> step : (steps == null ? new ArrayList<Map<String, Object>>() : steps)) {
            Object detail = step == null ? null : step.get("detail")
            out.append("<tr><td>").append(Cfp.html(step == null ? null : step.get("step")))
            out.append("</td><td>").append(Cfp.html(step == null ? null : step.get("state")))
            out.append("</td><td>").append(detail == null ? Cfp.NA : Cfp.html(detail))
            out.append("</td></tr>")
        }
        out.append("</tbody></table></div>")
        out.append("<div style=\"margin-top:6px\">Exception type and message only. No stack trace and no ")
        out.append("server path is printed, and running this check writes nothing to the instance.</div>")
        return out.append("</div>").toString()
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
    boolean includeArchived = Cfp.booleanParam(queryParams, "includeArchived", false)
    boolean includeModules = Cfp.booleanParam(queryParams, "includeModules", false)
    boolean scanUsage = Cfp.booleanParam(queryParams, "scanUsage", true)
    boolean scanAliases = Cfp.booleanParam(queryParams, "scanAliases", false)
    long scanBudgetMs = Cfp.longParam(queryParams, "scanBudgetMs", 120000L)
    String numbers = Cfp.stringParam(queryParams, "numbers", "de").toLowerCase(Locale.ROOT)
    /* The read-path self-check. Off by default and deliberately so: the deep steps
     * open a database connection, and a diagnostic block under every standard
     * report is skipped after the second read of it. Without this the page says one
     * line, and only when something did not resolve. */
    boolean diagRequested = Cfp.booleanParam(queryParams, "diag", false)

    Locale numberLocale = numbers == "en" ? Locale.ENGLISH : Locale.GERMANY
    long scanDeadline = scanBudgetMs > 0L ? started + scanBudgetMs : Long.MAX_VALUE

    Map<String, Object> activeParams = [
        format: format == "html" ? null : format,
        level: csvLevel == "app" ? null : csvLevel,
        appKey: appKeyFilter.isEmpty() ? null : appKeyFilter,
        includeSystem: includeSystem ? "true" : null,
        includeDisabled: includeDisabled ? null : "false",
        includeArchived: includeArchived ? "true" : null,
        includeModules: includeModules ? "true" : null,
        scanUsage: scanUsage ? null : "false",
        scanAliases: scanAliases ? "true" : null,
        scanBudgetMs: scanBudgetMs == 120000L ? null : String.valueOf(scanBudgetMs),
        numbers: numbers == "de" ? null : numbers,
        diag: diagRequested ? "true" : null
    ] as LinkedHashMap

    PluginAccessor pluginAccessor = ComponentLocator.getComponent(PluginAccessor.class)
    PluginMetadataManager pluginMetadataManager = ComponentLocator.getComponent(PluginMetadataManager.class)
    SearchManager searchManager = ComponentLocator.getComponent(SearchManager.class)
    SpaceManager spaceManager = ComponentLocator.getComponent(SpaceManager.class)
    PageManager pageManager = ComponentLocator.getComponent(PageManager.class)
    UserMacroLibrary userMacroLibrary = ComponentLocator.getComponent(UserMacroLibrary.class)
    I18NBeanFactory i18nBeanFactory = ComponentLocator.getComponent(I18NBeanFactory.class)
    I18NBean i18n = i18nBeanFactory == null ? null : i18nBeanFactory.getI18NBean()

    List<String> globalDiagnostics = new ArrayList<String>()

    /* The deliberate subset of globalDiagnostics. Nothing failed here: a name
     * collision is something this report states on purpose, and reporting it as a
     * suppressed read error sends an administrator hunting a failure that never
     * happened. */
    List<String> globalObservations = new ArrayList<String>()

    /* OP-1066. Loaded once for the whole run rather than per app: it is one call
     * for the entire instance, and asking it per plugin would multiply the cost of
     * the one thing that closes the gap. */
    Map<String, Object> macroCatalog = MacroCatalog.load()
    boolean macroCatalogOk = macroCatalog.get("ok") == Boolean.TRUE
    String macroCatalogFailure = (String) macroCatalog.get("failure")
    Map<String, List<String>> macroCatalogByPlugin =
        (Map<String, List<String>>) macroCatalog.get("byPlugin")
    if (!macroCatalogOk) {
        Cfp.observe(globalDiagnostics, globalObservations,
            "macro catalogue: " + macroCatalogFailure +
            " Macros that an app instantiates at runtime instead of declaring as a plugin module " +
            "are therefore invisible to this run, and the macro figures of such an app are not " +
            "measured rather than zero.")
    } else if (macroCatalogFailure != null) {
        Cfp.observe(globalDiagnostics, globalObservations,
            "macro catalogue: " + macroCatalogFailure)
    }

    String instanceBaseUrl = null
    String instanceSiteTitle = null
    String instanceVersion = null
    String instanceBuild = null

    try {
        GlobalSettingsManager globalSettingsManager = ComponentLocator.getComponent(GlobalSettingsManager.class)
        if (globalSettingsManager != null) {
            String rawBaseUrl = globalSettingsManager.getGlobalSettings().getBaseUrl()
            String rawSiteTitle = globalSettingsManager.getGlobalSettings().getSiteTitle()
            instanceBaseUrl = rawBaseUrl == null || rawBaseUrl.trim().isEmpty() ? null : rawBaseUrl.trim()
            instanceSiteTitle = rawSiteTitle == null || rawSiteTitle.trim().isEmpty() ? null : rawSiteTitle.trim()
        }
    } catch (Exception error) {
        Cfp.note(globalDiagnostics, "instance settings", error)
    }

    try {
        instanceVersion = GeneralUtil.getVersionNumber()
    } catch (Throwable error) {
        Cfp.note(globalDiagnostics, "Confluence version", error)
    }
    try {
        instanceBuild = String.valueOf(GeneralUtil.getBuildNumber())
    } catch (Throwable error) {
        Cfp.note(globalDiagnostics, "Confluence build", error)
    }

    Set<String> currentSpaceKeys = new HashSet<String>()
    Set<String> archivedSpaceKeys = new HashSet<String>()
    boolean currentSpaceInventoryComplete = true
    boolean archivedSpaceInventoryComplete = includeArchived

    try {
        currentSpaceKeys.addAll(spaceManager.getAllSpaceKeys(SpaceStatus.CURRENT))
    } catch (Exception error) {
        currentSpaceInventoryComplete = false
        Cfp.note(globalDiagnostics, "current space inventory", error)
    }

    if (includeArchived) {
        try {
            archivedSpaceKeys.addAll(spaceManager.getAllSpaceKeys(SpaceStatus.ARCHIVED))
        } catch (Exception error) {
            archivedSpaceInventoryComplete = false
            Cfp.note(globalDiagnostics, "archived space inventory", error)
        }
    }

    Long currentContentTotal = null
    boolean currentContentInventoryComplete = true
    try {
        if (pageManager == null) {
            throw new IllegalStateException("PageManager unavailable")
        }
        long currentPages = (long) pageManager.countCurrentPages()
        long currentBlogs = (long) pageManager.countCurrentBlogs()
        currentContentTotal = Long.valueOf(currentPages + currentBlogs)
    } catch (Exception error) {
        currentContentInventoryComplete = false
        Cfp.note(globalDiagnostics, "current content inventory", error)
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
            app.systemProvided = Boolean.valueOf(pluginMetadataManager.isSystemProvided(plugin))
        } catch (Exception error) {
            app.systemProvided = null
            Cfp.note(app.diagnostics, "system-provided flag", error)
        }

        if (app.systemProvided == Boolean.TRUE && !includeSystem) {
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

        /* OP-1066. The second name source. Only names the catalogue itself attributes
         * to this plugin key are taken, and only those the descriptor walk did not
         * already produce, so nothing is guessed and nothing is counted twice. They
         * carry no module of their own, which is the point: the app registered one
         * host and built these at runtime. */
        app.macroCatalogConsulted = Boolean.valueOf(macroCatalogOk)
        if (macroCatalogOk) {
            List<String> catalogNames = macroCatalogByPlugin.get(app.pluginKey)
            for (String catalogName : Cfp.catalogOnlyNames(catalogNames, macrosByName.keySet())) {
                MacroFootprint macro = new MacroFootprint()
                macro.source = "APP"
                macro.nameSource = Cfp.FROM_CATALOG
                macro.macroName = catalogName
                macro.displayName = catalogName
                macrosByName.put(catalogName, macro)
                app.catalogMacroCount++
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
    Long currentSpaceTotal = currentSpaceInventoryComplete ?
        Long.valueOf(currentSpaceKeys.size()) : null
    boolean impactInventoryIncomplete = !currentContentInventoryComplete ||
        !currentSpaceInventoryComplete || (includeArchived && !archivedSpaceInventoryComplete)
    for (AppFootprint app : apps) {
        impacts.put(app.pluginKey, Analyzer.assessImpact(
            app, scanUsage, includeArchived,
            currentContentTotal, currentSpaceTotal, impactInventoryIncomplete))
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
        Cfp.observe(globalDiagnostics, globalObservations, "macro name collision: " + collidingMacroNames.size() +
            " native user macro name(s) also occur as app macros (" + Cfp.nameList(collidingMacroNames) +
            "). Each of those macros may be counted once under its app and once under native user macros; this report does not merge them.")
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
    int notScannedApps = 0
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
    int totalObservations = globalObservations.size()

    Set<String> globalCurrentContentIds = new HashSet<String>()
    Set<String> globalArchivedContentIds = new HashSet<String>()
    Set<String> globalCurrentSpaces = new HashSet<String>()
    Set<String> globalArchivedSpaces = new HashSet<String>()
    List<AppFootprint> decommissionCandidates = new ArrayList<AppFootprint>()

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
        else if (impact.level == "NOT_SCANNED") notScannedApps++

        if (ImpactPolicy.isDecommissionCandidate(app.systemProvided, impact)) {
            decommissionCandidates.add(app)
        }

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
        totalObservations += app.observationCount

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

    /* Native user macro diagnostics all come from Cfp.note, so they are read
     * errors without exception. */
    int totalReadErrors = totalDiagnostics - totalObservations

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
    boolean archiveUsageEnabled = includeArchived && scanUsage
    String archiveUsageState = archiveUsageEnabled ?
        (archivedTotalsPartial ? Cfp.PARTIAL : Cfp.MEASURED) : Cfp.DISABLED

    /* =========================================================================
     * JSON
     * ======================================================================= */

    if (format == "json") {
        List<Map<String, Object>> appMaps = new ArrayList<Map<String, Object>>()
        for (AppFootprint app : apps) {
            appMaps.add(app.asMap(includeModules, impacts.get(app.pluginKey), archiveUsageEnabled))
        }

        List<Map<String, Object>> userMacroMaps = new ArrayList<Map<String, Object>>()
        for (MacroFootprint macro : userMacros) {
            userMacroMaps.add(macro.asMap(archiveUsageEnabled))
        }

        Map<String, Object> response = [
            report: [
                name: "Confluence App Footprint Analysis",
                version: Cfp.VERSION,
                generatedAt: generatedAt
            ] as LinkedHashMap,
            options: optionsInfo,
            spaceStatus: [
                currentSpaces: currentSpaceKeys.size(),
                archivedSpaces: includeArchived ? archivedSpaceKeys.size() : null,
                archivedState: includeArchived ?
                    (archivedSpaceInventoryComplete ? Cfp.MEASURED : Cfp.ERROR) : Cfp.DISABLED
            ] as LinkedHashMap,
            summary: [
                apps: apps.size(),
                disabledApps: disabledApps,
                decommissionCandidates: decommissionCandidates.size(),
                appsWithCurrentFootprint: appsWithCurrentFootprint,
                appsWithArchivedFootprint: archiveUsageEnabled ? appsWithArchivedFootprint : null,
                impact: [
                    critical: criticalApps,
                    high: highApps,
                    medium: mediumApps,
                    low: lowApps,
                    legacyOnly: legacyOnlyApps,
                    reviewRequired: reviewApps,
                    noDetectableFootprint: noFootprintApps,
                    notScanned: notScannedApps
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
                    state: archiveUsageState,
                    usedAppMacros: archiveUsageEnabled ? totalArchivedUsedMacros : null,
                    uniqueContent: archiveUsageEnabled ? globalArchivedContentIds.size() : null,
                    macroContentAssociations: archiveUsageEnabled ? totalArchivedAssociations : null,
                    partial: archiveUsageEnabled ? archivedTotalsPartial : null,
                    affectedSpaces: archiveUsageEnabled ? globalArchivedSpaces.size() : null
                ] as LinkedHashMap,
                nativeUserMacros: [
                    defined: userMacros.size(),
                    currentUsed: currentUsedUserMacros,
                    archivedState: archiveUsageState,
                    archivedUsed: archiveUsageEnabled ? archivedUsedUserMacros : null,
                    currentAssociations: currentUserMacroAssociations,
                    archivedAssociations: archiveUsageEnabled ? archivedUserMacroAssociations : null,
                    partial: archiveUsageEnabled ? userMacroTotalsPartial : null
                ] as LinkedHashMap,
                macrosSkippedByBudget: macrosSkippedByBudget,
                diagnostics: totalDiagnostics,
                readErrors: totalReadErrors,
                observations: totalObservations
            ] as LinkedHashMap,
            diagnostics: globalDiagnostics,
            observations: globalObservations,
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
            csv.append("source,app,vendor,pluginKey,macro,displayName,moduleEnabled,usageState,currentContent,currentSpaces,archivedContent,archivedSpaces,archivedState,otherContent,totalContent,aliases,diagnostics\n")

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
                    csv.append(archiveUsageEnabled ? macro.getArchivedContentCount() : "").append(",")
                    csv.append(archiveUsageEnabled ? macro.getArchivedSpaceCount() : "").append(",")
                    csv.append(Cfp.csv(archiveUsageEnabled ? macro.usageState : Cfp.DISABLED)).append(",")
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
                csv.append(archiveUsageEnabled ? macro.getArchivedContentCount() : "").append(",")
                csv.append(archiveUsageEnabled ? macro.getArchivedSpaceCount() : "").append(",")
                csv.append(Cfp.csv(archiveUsageEnabled ? macro.usageState : Cfp.DISABLED)).append(",")
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

        csv.append("pluginKey,displayName,vendor,version,enabled,state,systemProvided,impact,impactMaxPercent,impactPartial,impactReasons,impactDimensions,enabledModules,providedMacros,enabledMacros,blueprints,templates,customContentModules,currentUsedMacros,currentUniqueContent,currentAssociations,currentSpaces,currentComplete,archivedUsedMacros,archivedUniqueContent,archivedAssociations,archivedSpaces,archivedComplete,archivedState,diagnostics\n")

        for (AppFootprint app : apps) {
            ImpactAssessment impact = impacts.get(app.pluginKey)
            String appArchiveUsageState = archiveUsageEnabled ?
                (app.archivedUsagePartial ? Cfp.PARTIAL : Cfp.MEASURED) : Cfp.DISABLED
            csv.append(Cfp.csv(app.pluginKey)).append(",")
            csv.append(Cfp.csv(app.displayName)).append(",")
            csv.append(Cfp.csv(app.vendor)).append(",")
            csv.append(Cfp.csv(app.version)).append(",")
            csv.append(app.enabled).append(",")
            csv.append(Cfp.csv(app.state)).append(",")
            csv.append(app.systemProvided == null ? "" : app.systemProvided.toString()).append(",")
            csv.append(Cfp.csv(impact.level)).append(",")
            csv.append(Cfp.csv(impact.maxPercent.toPlainString())).append(",")
            csv.append(impact.partial).append(",")
            csv.append(Cfp.csv(String.join(" | ", impact.reasons))).append(",")
            csv.append(Cfp.csv(JsonOutput.toJson(impact.asMap().get("dimensions")))).append(",")
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
            csv.append(archiveUsageEnabled ? app.archivedUsedMacroCount : "").append(",")
            csv.append(archiveUsageEnabled ? app.archivedUniqueContentCount : "").append(",")
            csv.append(archiveUsageEnabled ? app.archivedAssociations : "").append(",")
            csv.append(archiveUsageEnabled ? app.archivedSpaceCount : "").append(",")
            csv.append(archiveUsageEnabled ? !app.archivedUsagePartial : "").append(",")
            csv.append(Cfp.csv(appArchiveUsageState)).append(",")
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

    /* The read path behind the export, asked whether it resolves. Rendering the
     * report reaches none of it, so the call is guarded to the point of paranoia:
     * a self-check able to take the report down with it is worse than none, and
     * this section exists because an endpoint stopped answering at all. A class
     * that cannot even be touched is an answer too, and it is printed as one. */
    List<Map<String, Object>> readPath = null
    try {
        readPath = Db.probe(diagRequested)
    } catch (Throwable error) {
        readPath = SelfCheck.unreachable(PageExport.errorDetail(error))
    }
    String readPathLine = diagRequested ? null : SelfCheck.summary(readPath)

    Map<String, Object> jsonOverrides = new LinkedHashMap<String, Object>()
    jsonOverrides.put("format", "json")
    String linkJson = Cfp.html(Cfp.link(activeParams, jsonOverrides))

    Map<String, Object> diagOverrides = new LinkedHashMap<String, Object>()
    diagOverrides.put("diag", "true")
    String linkDiag = Cfp.html(Cfp.link(activeParams, diagOverrides))

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
    archivedOverrides.put("includeArchived", includeArchived ? null : "true")
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

    /* ---- Confluence page export: model, space picker ----------------------- */

    /* The export payload is built from the same in-memory model the JSON branch
     * uses, never scraped back out of the rendered HTML: only the model still
     * carries the measurement state, so an n/m stays an n/m on the page. */
    List<Map<String, Object>> exportApps = new ArrayList<Map<String, Object>>()
    for (AppFootprint app : apps) {
        ImpactAssessment impact = impacts.get(app.pluginKey)
        Map<String, Object> row = new LinkedHashMap<String, Object>()
        row.put("pluginKey", app.pluginKey)
        row.put("displayName", app.displayName)
        row.put("vendor", app.vendor)
        row.put("version", app.version)
        row.put("enabled", Boolean.valueOf(app.enabled))
        row.put("systemProvided", app.systemProvided)
        row.put("impactLevel", impact.level)
        row.put("impactLabel", impact.label)
        row.put("impactMaxPercent", impact.maxPercent)
        row.put("impactPartial", Boolean.valueOf(impact.partial))
        row.put("impactReasons", impact.reasons)
        row.put("providedMacros", Integer.valueOf(app.macros.size()))
        row.put("currentState", PageExport.usageState(app, scanUsage, true))
        row.put("currentContent", Integer.valueOf(app.currentUniqueContentCount))
        row.put("currentSpaces", Integer.valueOf(app.currentSpaceCount))
        row.put("archivedState", PageExport.usageState(app, scanUsage, includeArchived))
        row.put("archivedContent", archiveUsageEnabled ?
            Integer.valueOf(app.archivedUniqueContentCount) : null)
        row.put("diagnostics", Integer.valueOf(app.diagnosticCount))
        row.put("observations", Integer.valueOf(app.observationCount))
        exportApps.add(row)
    }

    Map<String, Object> exportImpact = new LinkedHashMap<String, Object>()
    exportImpact.put("critical", Integer.valueOf(criticalApps))
    exportImpact.put("high", Integer.valueOf(highApps))
    exportImpact.put("medium", Integer.valueOf(mediumApps))
    exportImpact.put("low", Integer.valueOf(lowApps))
    exportImpact.put("legacyOnly", Integer.valueOf(legacyOnlyApps))
    exportImpact.put("reviewRequired", Integer.valueOf(reviewApps))
    exportImpact.put("noDetectableFootprint", Integer.valueOf(noFootprintApps))
    exportImpact.put("notScanned", Integer.valueOf(notScannedApps))

    Map<String, Object> exportCapabilities = new LinkedHashMap<String, Object>()
    exportCapabilities.put("providedMacros", Integer.valueOf(totalProvidedMacros))
    exportCapabilities.put("enabledMacros", Integer.valueOf(totalEnabledMacros))
    exportCapabilities.put("blueprints", Integer.valueOf(totalBlueprints))
    exportCapabilities.put("templates", Integer.valueOf(totalTemplates))
    exportCapabilities.put("customContentModules", Integer.valueOf(totalCustomContentModules))

    Map<String, Object> exportCurrent = new LinkedHashMap<String, Object>()
    exportCurrent.put("usedAppMacros", Integer.valueOf(totalCurrentUsedMacros))
    exportCurrent.put("uniqueContent", Integer.valueOf(globalCurrentContentIds.size()))
    exportCurrent.put("macroContentAssociations", Long.valueOf(totalCurrentAssociations))
    exportCurrent.put("partial", Boolean.valueOf(currentTotalsPartial))
    exportCurrent.put("affectedSpaces", Integer.valueOf(globalCurrentSpaces.size()))

    Map<String, Object> exportArchived = new LinkedHashMap<String, Object>()
    exportArchived.put("usedAppMacros", Integer.valueOf(totalArchivedUsedMacros))
    exportArchived.put("uniqueContent", Integer.valueOf(globalArchivedContentIds.size()))
    exportArchived.put("macroContentAssociations", Long.valueOf(totalArchivedAssociations))
    exportArchived.put("partial", Boolean.valueOf(archivedTotalsPartial))
    exportArchived.put("affectedSpaces", Integer.valueOf(globalArchivedSpaces.size()))

    Map<String, Object> exportUserMacros = new LinkedHashMap<String, Object>()
    exportUserMacros.put("defined", Integer.valueOf(userMacros.size()))
    exportUserMacros.put("currentUsed", Integer.valueOf(currentUsedUserMacros))
    exportUserMacros.put("archivedUsed", Integer.valueOf(archivedUsedUserMacros))
    exportUserMacros.put("partial", Boolean.valueOf(userMacroTotalsPartial))

    Map<String, Object> exportSummary = new LinkedHashMap<String, Object>()
    exportSummary.put("apps", Integer.valueOf(apps.size()))
    exportSummary.put("disabledApps", Integer.valueOf(disabledApps))
    exportSummary.put("decommissionCandidates", Integer.valueOf(decommissionCandidates.size()))
    exportSummary.put("appsWithCurrentFootprint", Integer.valueOf(appsWithCurrentFootprint))
    exportSummary.put("appsWithArchivedFootprint", Integer.valueOf(appsWithArchivedFootprint))
    exportSummary.put("impact", exportImpact)
    exportSummary.put("capabilities", exportCapabilities)
    exportSummary.put("current", exportCurrent)
    exportSummary.put("archived", exportArchived)
    exportSummary.put("nativeUserMacros", exportUserMacros)
    exportSummary.put("macrosSkippedByBudget", Integer.valueOf(macrosSkippedByBudget))
    exportSummary.put("diagnostics", Integer.valueOf(totalDiagnostics))
    exportSummary.put("readErrors", Integer.valueOf(totalReadErrors))
    exportSummary.put("observations", Integer.valueOf(totalObservations))

    Map<String, Object> exportReport = new LinkedHashMap<String, Object>()
    exportReport.put("name", "Confluence App Footprint Analysis")
    exportReport.put("version", Cfp.VERSION)
    exportReport.put("generatedAt", generatedAt)
    exportReport.put("siteTitle", instanceSiteTitle)
    exportReport.put("baseUrl", instanceBaseUrl)
    exportReport.put("instanceVersion", instanceVersion)
    exportReport.put("instanceBuild", instanceBuild)

    Map<String, Object> exportModel = new LinkedHashMap<String, Object>()
    exportModel.put("report", exportReport)
    exportModel.put("options", optionsInfo)
    exportModel.put("summary", exportSummary)
    exportModel.put("apps", exportApps)
    String exportPayload = Cfp.html(JsonOutput.toJson(exportModel))

    /* No space picker is built here. Rendering this report performs no export
     * lookup at all: the space search and the parent page search are served on
     * demand by the POST branch, once the export button is pressed. */

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
.instance{display:flex;flex-wrap:wrap;gap:6px 28px;margin:12px 0 20px;padding:10px 14px;background:#fff;border:1px solid var(--border);border-radius:6px;box-shadow:var(--shadow)}.instance div{font-size:13px}.instance strong{font-weight:600}
.actions{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end}.button{display:inline-flex;align-items:center;height:34px;padding:0 12px;border:1px solid var(--border);border-radius:5px;background:#fff;color:var(--text);text-decoration:none;font-size:13px;font-weight:600}.button.on{background:var(--blue-bg);border-color:var(--blue);color:var(--blue)}
.summary-grid{display:grid;grid-template-columns:repeat(8,minmax(125px,1fr));gap:10px;margin-bottom:16px}.summary-card{min-height:90px;padding:14px 15px;background:#fff;border:1px solid var(--border);border-radius:7px;box-shadow:var(--shadow)}.summary-value{font-size:24px;font-weight:650}.summary-label{margin-top:4px;color:var(--muted);font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.035em}
.notice,.diag{margin-bottom:16px;padding:12px 16px;border-radius:6px;background:var(--blue-bg);border:1px solid #b6d6ff;font-size:13px}.diag-info{background:var(--blue-bg);border-color:#b6d6ff}.diag-warn{background:var(--yellow-bg);border-color:var(--yellow-border)}.diag ul{margin:8px 0 0;padding-left:20px}
.legend{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:18px}.search{flex:1;min-width:280px;height:38px;padding:0 11px;border:1px solid var(--border);border-radius:5px;background:#fff;font-size:14px}select{height:38px;padding:0 9px;border:1px solid var(--border);border-radius:5px;background:#fff}.checkbox-label{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:13px}
.app-card,.user-macro-card{background:#fff;border:1px solid var(--border);border-radius:8px;margin-bottom:15px;box-shadow:var(--shadow);overflow:hidden}.app-card.is-disabled{border-left:4px solid var(--yellow)}.hidden{display:none!important}.app-header{padding:17px 20px;border-bottom:1px solid var(--border-subtle)}.app-header-row{display:flex;justify-content:space-between;align-items:flex-start;gap:24px}.app-name{font-size:18px;font-weight:650}.app-meta{margin-top:3px;color:var(--muted);font-size:12px}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;overflow-wrap:anywhere}
.badges{display:flex;flex:0 0 auto;flex-wrap:wrap;justify-content:flex-end;align-items:flex-start;gap:6px}.badge{display:inline-flex;align-items:center;height:24px;padding:0 9px;border-radius:999px;border:1px solid transparent;font-size:10px;font-weight:700;white-space:nowrap}.badge-current,.badge-low{color:var(--green);background:var(--green-bg);border-color:var(--green-border)}.badge-archived,.badge-high{color:var(--orange);background:var(--orange-bg);border-color:var(--orange-border)}.badge-critical,.badge-diag{color:var(--red);background:var(--red-bg);border-color:var(--red-border)}.badge-medium{color:var(--yellow);background:var(--yellow-bg);border-color:var(--yellow-border)}.badge-review,.badge-native,.badge-system{color:var(--purple);background:var(--purple-bg);border-color:var(--purple-border)}.badge-none,.badge-capability{color:var(--muted);background:#f1f2f4;border-color:var(--border)}.badge-disabled{color:var(--yellow);background:var(--yellow-bg);border-color:var(--yellow-border)}
.impact-reasons{margin:9px 0 0;padding-left:20px;color:var(--muted);font-size:12px}.metric-group-title{padding:8px 16px;background:var(--surface-subtle);border-top:1px solid var(--border-subtle);color:var(--muted);font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.metrics{display:grid;grid-template-columns:repeat(6,minmax(120px,1fr));border-bottom:1px solid var(--border-subtle)}.metric{min-height:76px;padding:12px 16px;border-right:1px solid var(--border-subtle)}.metric:last-child{border-right:0}.metric-value{font-size:19px;font-weight:650}.metric-label{margin-top:3px;color:var(--muted);font-size:11px}.archived-row{background:#fffaf7}.archived-value{color:var(--orange)}
.section{padding:16px 20px;border-bottom:1px solid var(--border-subtle)}.section:last-child{border-bottom:0}.section-title{margin-bottom:10px;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.category-grid{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:8px;margin-bottom:10px}.category{padding:9px 11px;background:var(--surface-subtle);border:1px solid var(--border-subtle);border-radius:5px}.category-name{font-size:12px;font-weight:650}.category-count{color:var(--muted);font-size:11px}.coverage{margin-top:10px;padding:10px 12px;background:#fafbfc;border:1px solid var(--border-subtle);border-radius:5px;color:var(--muted);font-size:12px}.main-section-title{margin:30px 0 12px;font-size:21px}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px 10px;text-align:left;border-bottom:1px solid var(--border-subtle);vertical-align:top}th{background:var(--surface-subtle);color:var(--muted);font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.035em;white-space:nowrap}tbody tr:hover{background:var(--surface-subtle)}.num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}.archived-num{color:var(--orange)}.good{color:var(--green);font-weight:600}.warn{color:var(--yellow);font-weight:600}.bad{color:var(--red);font-weight:600}.muted{color:var(--muted)}.empty{color:var(--muted);font-style:italic;padding:5px 0}.table-wrap{overflow-x:auto}
details{margin-top:9px}summary{cursor:pointer;color:var(--blue);font-size:12px;font-weight:600}.space-list{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.space-pill{padding:2px 7px;border:1px solid var(--border);border-radius:999px;background:var(--surface-subtle);font:11px ui-monospace,monospace}.space-pill-archived{color:var(--orange);background:var(--orange-bg);border-color:var(--orange-border)}.footer{margin-top:24px;padding:16px 20px;background:#fff;border:1px solid var(--border);border-radius:8px;color:var(--muted);font-size:12px}.footer ul{margin:8px 0;padding-left:18px}
.export-card{margin-bottom:18px;padding:16px 20px;background:#fff;border:1px solid var(--border);border-radius:8px;box-shadow:var(--shadow)}.export-grid{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-end;margin-top:10px}.export-field{display:flex;flex-direction:column;gap:4px;color:var(--muted);font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.035em}.export-field input{height:38px;padding:0 11px;border:1px solid var(--border);border-radius:5px;background:#fff;color:var(--text);font-size:14px;font-weight:400;text-transform:none;letter-spacing:0}.export-field select{min-width:300px}.export-field input.wide{min-width:320px}.export-status{margin-top:10px;font-size:12px}
.export-card button.button{cursor:pointer}.export-card button.button[disabled]{opacity:.55;cursor:not-allowed}.export-settings{margin-top:14px;padding-top:12px;border-top:1px solid var(--border-subtle)}.export-stage{margin-top:10px;padding-top:10px;border-top:1px dashed var(--border-subtle)}.export-chosen{align-self:flex-end;padding-bottom:9px;color:var(--muted);font-size:12px}.export-results{margin-top:8px;max-width:680px}.export-hit{display:block;width:100%;margin-bottom:4px;padding:6px 10px;text-align:left;border:1px solid var(--border);border-radius:5px;background:var(--surface-subtle);color:var(--text);font-size:13px;cursor:pointer}.export-hit:hover{border-color:var(--blue);background:var(--blue-bg)}.export-empty{color:var(--muted);font-size:12px;font-style:italic}
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
      ${includeArchived ? num(archivedSpaceKeys.size()) + ' archived spaces' : 'archived spaces off'}
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

<div class="instance">
  <div><strong>Instance:</strong> ${esc(instanceSiteTitle ?: Cfp.NA)}</div>
  <div><strong>Base URL:</strong> <span class="mono">${esc(instanceBaseUrl ?: Cfp.NA)}</span></div>
  <div><strong>Confluence:</strong> ${esc(instanceVersion ?: Cfp.NA)} (build ${esc(instanceBuild ?: Cfp.NA)})</div>
  <div><strong>Options:</strong> <span class="mono">includeSystem=${includeSystem} includeDisabled=${includeDisabled} includeArchived=${includeArchived} includeModules=${includeModules} scanUsage=${scanUsage} scanAliases=${scanAliases} scanBudgetMs=${scanBudgetMs}</span></div>
</div>

<div class="summary-grid">
  <div class="summary-card"><div class="summary-value">${num(apps.size())}</div><div class="summary-label">Apps in report${disabledApps > 0 ? ' (' + num(disabledApps) + ' disabled)' : ''}</div></div>
  <div class="summary-card"><div class="summary-value">${num(appsWithCurrentFootprint)}</div><div class="summary-label">Apps with current footprint</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalProvidedMacros)}</div><div class="summary-label">Provided app macros</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalCurrentUsedMacros)}</div><div class="summary-label">Used app macros \u00B7 current</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalCurrentAssociations)}${currentTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="summary-label">Macro associations \u00B7 current</div></div>
  <div class="summary-card"><div class="summary-value">${includeArchived ? num(totalArchivedAssociations) + (archivedTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : '') : Cfp.NA}</div><div class="summary-label">Macro associations \u00B7 archived</div></div>
  <div class="summary-card"><div class="summary-value">${num(totalBlueprints + totalTemplates)}</div><div class="summary-label">Blueprints / templates</div></div>
  <div class="summary-card"><div class="summary-value">${num(userMacros.size())}</div><div class="summary-label">Native user macros</div></div>
</div>

<div class="notice">
  <strong>Measurement model:</strong>
  Current macro usage only includes content in spaces with <span class="mono">SpaceStatus.CURRENT</span>.
  Archived dependencies are separated. Blueprint, template, custom-content, UI, REST, listener and job modules are inventory signals unless a dedicated resolver exists.
</div>
""")

    if (!decommissionCandidates.isEmpty()) {
        html.append("""<div class="notice">
  <strong>Decommission candidates (${num(decommissionCandidates.size())})</strong>
  <div style="margin-top:6px">
    Included in this report, not system-provided, and carrying no detectable configuration or data footprint.
    That is a starting point for a conversation, not a verdict: UI-only, REST-only or runtime-only
    functionality leaves no trace here, and this report does not measure usage.
  </div>
  <ul>
""")
        for (AppFootprint candidate : decommissionCandidates) {
            html.append("<li>" + esc(candidate.displayName) + " <span class=\"mono\">" +
                esc(candidate.pluginKey) + "</span> \u00B7 " + esc(num(candidate.enabledModuleCount)) +
                " enabled modules" + (candidate.enabled ? "" : " \u00B7 <span class=\"badge badge-disabled\">DISABLED</span>") +
                "</li>")
        }
        html.append("</ul></div>")
    }

    if (Cfp.diagBoxShown(macrosSkippedByBudget, totalDiagnostics)) {
        html.append("""<div class="${Cfp.diagClass(macrosSkippedByBudget, totalReadErrors)}"><strong>Measurement notes</strong><ul>""")
        if (macrosSkippedByBudget > 0) {
            html.append("<li>" + esc(num(macrosSkippedByBudget)) + " macro scan(s) were not measured because the scan budget of " + esc(num(scanBudgetMs)) + " ms was exhausted. They show <span class=\"warn\">n/m</span>, not zero.</li>")
        }
        if (totalReadErrors > 0) {
            html.append("<li>" + esc(num(totalReadErrors)) + " suppressed read error(s) were recorded. Affected apps/macros carry diagnostics in their detail sections.</li>")
        }
        for (String entry : Cfp.readErrorsOf(globalDiagnostics, globalObservations)) {
            html.append("<li class=\"mono\">" + esc(entry) + "</li>")
        }
        if (totalObservations > 0) {
            html.append("<li>" + esc(num(totalObservations)) + " observation(s) were recorded. Nothing failed and nothing was suppressed: these are statements this report makes on purpose.</li>")
        }
        for (String entry : globalObservations) {
            html.append("<li class=\"mono\">" + esc(entry) + "</li>")
        }
        html.append("</ul></div>")
    }

    /* Directly above the export, because that is the only thing this path serves.
     * On request the full table; otherwise one line and only when something
     * refused; and nothing at all in the normal case. */
    if (diagRequested) {
        html.append(SelfCheck.html(readPath))
    } else if (readPathLine != null) {
        html.append("<div class=\"" + Cfp.DIAG_WARN + "\"><strong>Read path self-check</strong>" +
            "<div style=\"margin-top:6px\">" + esc(readPathLine) +
            " <a href=\"" + linkDiag + "\">Run the full self-check</a></div></div>")
    }

    html.append("""
<div class="export-card">
  <div class="section-title">Export to Confluence</div>
  <div class="subtitle">
    Writes an Executive Summary of this report into a Confluence page. A repeat run updates the same page.
    The <strong>Decision</strong> column stays untouched: it is read back from the existing page and carried over.
    If that read fails, nothing is written at all. Nothing is looked up until the button below is pressed.
  </div>
  <div class="export-grid">
    <button id="exportOpen" class="button" type="button" onclick="openExport()">Export to Confluence</button>
  </div>
  <div id="exportSettings" class="export-settings hidden">
    <div id="exportSpaceStage">
      <div class="export-grid">
        <label class="export-field">Space - search by name or key
          <input id="exportSpaceQuery" class="wide" type="search" autocomplete="off"
                 placeholder="Type at least ${PageExport.MIN_SEARCH_CHARS} characters..." oninput="searchSpaces()"
                 onkeydown="pickFirstHit(event,'exportSpaceResults')">
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
                 onkeydown="pickFirstHit(event,'exportParentResults')">
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

    html.append("""
<div class="legend">
  <span class="badge badge-critical">CRITICAL ${criticalApps}</span>
  <span class="badge badge-high">HIGH ${highApps}</span>
  <span class="badge badge-medium">MEDIUM ${mediumApps}</span>
  <span class="badge badge-low">LOW ${lowApps}</span>
  <span class="badge badge-archived">LEGACY ONLY ${legacyOnlyApps}</span>
  <span class="badge badge-review">REVIEW REQUIRED ${reviewApps}</span>
  <span class="badge badge-none">NO DETECTABLE FOOTPRINT ${noFootprintApps}</span>
  <span class="badge badge-none">NOT SCANNED ${notScannedApps}</span>
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
    <option value="NOT_SCANNED">Not scanned</option>
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
            html.append('<span class="badge badge-disabled">DISABLED' + (app.state != null ? ' \u00B7 ' + esc(app.state) : '') + '</span>')
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
        /* OP-1066. Four of these six figures are counted from macro usage, and macro
         * usage is only ever asked about once per known macro name. Where no name
         * was known, they are not zero but unmeasured, and printing a 0 is exactly
         * what let an app carrying seventeen runtime-defined macros read as having
         * no footprint at all. */
        boolean macroFiguresUnasked = app.macroEnumerationNarrowed()
        String unasked = '<span class="warn" title="Not measured: no macro name was known to search for">' +
            Cfp.NA + '</span>'

        html.append("""</ul>
</div>

<div class="metric-group-title">Current Footprint</div>
<div class="metrics">
  <div class="metric"><div class="metric-value">${num(app.enabledModuleCount)}</div><div class="metric-label">Enabled Extension Modules</div></div>
  <div class="metric"><div class="metric-value">${macroFiguresUnasked ? unasked : num(app.macros.size())}</div><div class="metric-label">Provided Macros (${num(app.enabledMacroCount)} enabled)</div></div>
  <div class="metric"><div class="metric-value">${macroFiguresUnasked ? unasked : num(app.currentUsedMacroCount)}</div><div class="metric-label">Used Macros</div></div>
  <div class="metric"><div class="metric-value">${macroFiguresUnasked ? unasked : num(app.currentUniqueContentCount) + (app.currentUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : '')}</div><div class="metric-label">Unique Current Content</div></div>
  <div class="metric"><div class="metric-value">${macroFiguresUnasked ? unasked : num(app.currentAssociations) + (app.currentUsagePartial ? '<span class="warn" title="Partial / lower bound">*</span>' : '')}</div><div class="metric-label">Current Macro-Content Associations</div></div>
  <div class="metric"><div class="metric-value">${macroFiguresUnasked ? unasked : num(app.currentSpaceCount)}</div><div class="metric-label">Current Spaces</div></div>
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
  <div class="coverage"><strong>Coverage:</strong> Macro footprint is measured from the Confluence content index, and the index is queried once per macro name that is known before the scan. Names come from two sources: the plugin module descriptors of each app, and the instance-wide macro catalogue, which is what makes a macro visible that an app instantiates at runtime instead of declaring as a module. An app whose macro names could be established from neither source is reported as not measured, never as zero. Blueprint/template/custom-content modules are inventoried but do not receive a generic usage count. UI, REST, servlet, job and listener modules are capability signals only.</div>
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
            html.append('<div class="section"><div class="section-title">Diagnostics</div><details><summary>' +
                esc(num(app.diagnosticCount - app.observationCount)) + ' suppressed read error(s), ' +
                esc(num(app.observationCount)) + ' observation(s)</summary><ul>')
            for (String note : Cfp.readErrorsOf(app.diagnostics, app.observations)) {
                html.append('<li class="mono">' + esc(note) + '</li>')
            }
            for (MacroFootprint macro : app.macros) {
                for (String note : macro.diagnostics) {
                    html.append('<li class="mono">' + esc(macro.macroName) + ': ' + esc(note) + '</li>')
                }
            }
            /* Marked as such: nothing here failed and nothing was suppressed. */
            for (String note : app.observations) {
                html.append('<li class="mono">observation: ' + esc(note) + '</li>')
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
    <div class="metric"><div class="metric-value">${num(currentUsedUserMacros)}</div><div class="metric-label">Used \u00B7 Current</div></div>
    <div class="metric"><div class="metric-value">${num(currentUserMacroAssociations)}${userMacroTotalsPartial ? '<span class="warn" title="Partial / lower bound">*</span>' : ''}</div><div class="metric-label">Current Associations</div></div>
    <div class="metric"><div class="metric-value archived-value">${includeArchived ? num(archivedUsedUserMacros) : Cfp.NA}</div><div class="metric-label">Used \u00B7 Archived</div></div>
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
    <li>Provided Macros are discovered through <span class="mono">MacroMetadataSource</span> on the app's own module descriptors, including modern XHTML macros. A disabled app/module can still have content references and therefore a measurable footprint.</li>
    <li>Macros that an app builds at runtime out of its own storage declare no module of their own and are invisible to that walk. They are picked up from the instance-wide macro catalogue instead, attributed only to the app the catalogue itself names as owner, and marked <span class="mono">catalog</span> in the macro table. Where the catalogue could not be reached, the macro figures of an affected app read as not measured rather than as zero, and the app is held at Review required.</li>
    <li>Native User Macros are read from <span class="mono">UserMacroLibrary</span>. Confluence may hide a user macro from that library when a plugin macro with the same name takes precedence.</li>
    <li>Blueprint, template and custom-content module counts are capability/inventory signals. Their actual generated/persisted object counts require dedicated resolvers.</li>
    <li>Impact is a local assessment heuristic configured in this script; it is not an Atlassian classification.</li>
    <li>"No detectable footprint" does not mean "unused": UI-only, REST-only, background-service and proprietary app data can exist without a generic footprint signal.</li>
    <li>This report is read-only, performs no writes and makes no outbound network call.</li>
  </ul>
  Report version ${Cfp.VERSION} &nbsp;&middot;&nbsp; execution time ${num(System.currentTimeMillis() - started)} ms
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
/* The export is staged: nothing above ran a lookup, so every stage below asks the
   POST branch of this same endpoint for exactly what it needs, and no further. */
var exportSpaceList=[];
var exportPageSeq=0;
var exportPageTimer=null;
function el(id){return document.getElementById(id);}
function say(cssClass,text){
  var node=el('exportStatus');
  node.className='export-status '+cssClass;
  node.textContent=text;
  return node;
}
function exportPost(payload){
  return fetch(window.location.pathname,{
    method:'POST',
    credentials:'same-origin',
    headers:{'Content-Type':'application/json','X-Atlassian-Token':'no-check'},
    body:JSON.stringify(payload)
  }).then(function(response){
    return response.json().then(function(parsed){return {ok:response.ok,status:response.status,body:parsed};});
  });
}
function hit(label,onPick){
  var button=document.createElement('button');
  button.type='button';button.className='export-hit';button.textContent=label;button.onclick=onPick;
  return button;
}
function emptyNote(text){
  var note=document.createElement('div');
  note.className='export-empty';note.textContent=text;
  return note;
}
/* Enter picks the first hit. The results are buttons in document order, so the
   first one in the box is the first match. Enter with no hit does nothing, the
   default is always suppressed so Enter can never submit or reload the page, and
   picking a hit with the mouse keeps working unchanged. The parent field no
   longer falls back to a Find button on Enter, because there is no Find button:
   typing is what runs the search. */
function pickFirstHit(event,boxId){
  if(event.key!=='Enter'){return;}
  event.preventDefault();
  var first=el(boxId).querySelector('.export-hit');
  if(first){first.click();}
}

/* Stage 1. The first lookup of the whole report: the spaces of this instance.
   There is no target to pick - this endpoint writes into its own instance. */
function openExport(){
  el('exportOpen').disabled=true;
  el('exportSettings').classList.remove('hidden');
  say('muted','Reading the spaces of this instance...');
  exportPost({action:'spaces'}).then(function(result){
    var body=result.body||{};
    if(!result.ok||body.ok!==true){
      el('exportSettings').classList.add('hidden');
      el('exportOpen').disabled=false;
      say('bad',body.error||'The space list could not be read.');
      return;
    }
    exportSpaceList=body.spaces||[];
    /* A cap that cut the list is announced with the ordering it cut by. A list
       silently shortened to its first N entries reads exactly like a complete
       one, and the space that is missing is the one nobody thinks to look for. */
    var spaceNote=String(exportSpaceList.length)+' current space(s). Type at least ${PageExport.MIN_SEARCH_CHARS}'+
      ' characters to search by name or key.';
    if(body.truncated===true){
      spaceNote=spaceNote+' This list was cut at '+String(body.cap)+' spaces, taken by '+
        String(body.order)+'. A space past the cut can still be exported by naming its key.';
      say('warn',spaceNote);
      return;
    }
    say('muted',spaceNote);
  }).catch(function(error){
    el('exportSettings').classList.add('hidden');
    el('exportOpen').disabled=false;
    say('bad','The space list could not be read: '+error);
  });
}

/* Stage 2. Search, not a dropdown: only matches are ever put into the page. */
function searchSpaces(){
  var query=el('exportSpaceQuery').value.trim().toLowerCase();
  var box=el('exportSpaceResults');
  box.innerHTML='';
  if(query.length<${PageExport.MIN_SEARCH_CHARS}){return;}
  var shown=0;
  for(var i=0;i<exportSpaceList.length&&shown<${PageExport.SEARCH_LIMIT};i++){
    var space=exportSpaceList[i];
    if(space.name.toLowerCase().indexOf(query)<0&&space.key.toLowerCase().indexOf(query)<0){continue;}
    box.appendChild(hit(space.name+'  ('+space.key+')',chooseSpace(space)));
    shown++;
  }
  if(shown===0){box.appendChild(emptyNote('No current space matches "'+query+'".'));}
}
function chooseSpace(space){
  return function(){
    el('exportSpace').value=space.key;
    el('exportSpaceQuery').value=space.name;
    el('exportSpaceResults').innerHTML='';
    el('exportSpaceChosen').textContent='Space: '+space.name+' ('+space.key+')';
    /* A parent search that is still running belongs to the previous space, so it
       is discarded here as well - otherwise its answer would drop a list of
       foreign pages into the field of the space just picked. */
    if(exportPageTimer){window.clearTimeout(exportPageTimer);}
    exportPageSeq++;
    el('exportParent').value='';
    el('exportParentQuery').value='';
    el('exportParentResults').innerHTML='';
    el('exportParentChosen').textContent='No parent page: the page is created at the top level of the space.';
    el('exportPageStage').classList.remove('hidden');
    say('muted','Space '+space.key+' selected. Pick a parent page or leave it empty, then generate.');
  };
}

/* Stage 3. The parent field has no button: typing is what starts the search,
   after a short idle pause rather than on every keystroke. The list that comes
   back STAYS until an entry is picked or the field falls below the minimum - a
   list that disappears while it is being read cannot confirm anything, which is
   what made the previous version unusable. Out-of-order answers are dropped, so
   a slow answer to an older term never replaces the list of the current one. */
function parentTyped(){
  /* Editing the term drops the picked parent, so a stale id can never travel
     with a title the administrator has since changed. What travels then is the
     title, and the generating run adopts or creates that page. */
  el('exportParent').value='';
  var query=el('exportParentQuery').value.trim();
  el('exportParentChosen').textContent=query
    ?'Parent page "'+query+'": pick it below if it is listed, otherwise it is created when the page is generated.'
    :'No parent page: the page is created at the top level of the space.';
  if(exportPageTimer){window.clearTimeout(exportPageTimer);}
  if(query.length<${PageExport.MIN_SEARCH_CHARS}){
    /* Bumping the sequence here discards an answer that is still in flight, so
       an empty field never fills back up on its own. */
    exportPageSeq++;
    el('exportParentResults').innerHTML='';
    return;
  }
  exportPageTimer=window.setTimeout(searchParents,${PageExport.SEARCH_IDLE_MS});
}
function searchParents(){
  var query=el('exportParentQuery').value.trim();
  var box=el('exportParentResults');
  if(query.length<${PageExport.MIN_SEARCH_CHARS}){box.innerHTML='';return;}
  var seq=++exportPageSeq;
  exportPost({action:'pages',spaceKey:el('exportSpace').value,query:query}).then(function(result){
    if(seq!==exportPageSeq){return;}
    var body=result.body||{};
    box.innerHTML='';
    if(!result.ok||body.ok!==true){box.appendChild(emptyNote(body.error||'The page search failed.'));return;}
    var pages=body.pages||[];
    if(pages.length===0){box.appendChild(emptyNote('Not found - will be created'));return;}
    for(var i=0;i<pages.length;i++){box.appendChild(hit(pages[i].title+'  #'+pages[i].id,chooseParent(pages[i])));}
    if(body.truncated===true){
      box.appendChild(emptyNote('More pages match than are listed here. Type more of the title to narrow it down.'));
    }
  }).catch(function(error){
    if(seq===exportPageSeq){box.innerHTML='';box.appendChild(emptyNote('The page search failed: '+error));}
  });
}
function chooseParent(page){
  return function(){
    el('exportParent').value=page.id;
    el('exportParentQuery').value=page.title;
    el('exportParentResults').innerHTML='';
    el('exportParentChosen').textContent='Parent page: '+page.title+' (id '+page.id+')';
  };
}

/* Stage 4. The write. */
function exportToConfluence(){
  var button=el('exportRun');
  function fail(text){say('bad',text);}
  var payload;
  try{payload=JSON.parse(el('exportPayload').value);}
  catch(error){fail('Export payload could not be read: '+error);return;}
  payload.spaceKey=el('exportSpace').value;
  /* Either the id of a page that was picked, or the title that was typed and
     never picked - never both. The server refuses a request that carries two
     parent instructions, so the choice is made here and only here. */
  payload.parentPageId=el('exportParent').value.trim();
  payload.parentTitle=payload.parentPageId?'':el('exportParentQuery').value.trim();
  payload.title=el('exportTitle').value.trim();
  if(!payload.spaceKey){fail('Select a space first.');return;}
  if(!payload.title){fail('Enter a page title first.');return;}
  button.disabled=true;say('muted','Writing page...');
  exportPost(payload).then(function(result){
    button.disabled=false;
    var body=result.body||{};
    if(!result.ok||body.ok!==true){
      fail('Nothing was written ('+result.status+'): '+(body.error||'unknown error'));
      return;
    }
    /* Found and created are reported apart. An administrator who reads "found"
       believes the parent was already there and stops looking for the page this
       run has just made. */
    var parent='';
    if(body.parentAction==='created'){
      parent=' Parent page created: "'+body.parentTitle+'" (id '+body.parentPageId+').';
    }else if(body.parentAction==='found'){
      parent=' Parent page found: "'+body.parentTitle+'" (id '+body.parentPageId+').';
    }
    /* A parent that was named and not applied is said plainly, and the line stops
       reading as a plain success. A silent mismatch is the worst outcome here: the
       run looks like it worked and the report is not where it was put. The three
       states are compared as strings on purpose - "unknown" is not a failure and
       must not be reported as one. */
    var tone='good';
    if(body.parentApplied==='false'){
      tone='bad';
      parent+=' PARENT NOT APPLIED. '+(body.parentAppliedReason||'The page was not moved under the parent page.');
    }else if(body.parentApplied==='unknown'){
      tone='warn';
      parent+=' PARENT NOT CONFIRMED. '+(body.parentAppliedReason||'The position could not be read back.');
    }
    var status=say(tone,'Page '+body.action+': "'+body.title+'" in '+body.spaceKey+' (version '+body.pageVersion+'). '+
      'Decision read: '+body.decisionRead+', carried over: '+body.decisionsCarried+' of '+body.decisionsRead+', '+
      'without a matching app: '+body.orphanedDecisions+'.'+parent);
    if(body.pageUrl){
      var link=document.createElement('a');
      link.href=body.pageUrl;link.target='_blank';link.rel='noopener';link.textContent=' Open the page';
      status.appendChild(link);
    }
  }).catch(function(error){
    button.disabled=false;
    fail('Request failed, nothing was written: '+error);
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


/* =============================================================================
 * Endpoint - Confluence page export (POST)
 * ========================================================================== */

/* Same endpoint name as the report with a different httpMethod. The Adaptavist
 * documentation states that several closures with the same name and different
 * verbs may live in one file, so the report page can POST to its own URL without
 * knowing the REST base path.
 *
 * CSRF - UNVERIFIED. The Custom REST Endpoint documentation does not say whether
 * these endpoints sit behind the Confluence XSRF filter, so the report page sends
 * X-Atlassian-Token: no-check, which is required if the filter applies and
 * harmless if it does not. Reading that header back would need the three-argument
 * HttpServletRequest closure form, and the servlet package this ScriptRunner
 * version passes on Confluence 10 (javax or jakarta) is not documented either, so
 * no header check is attempted here. What IS enforced on the server: the
 * confluence-administrators gate, and the rule that only a page carrying the
 * export marker is ever updated - a forged request can neither replace a foreign
 * page nor drop a decision. TO CONFIRM before relying on more than that: whether
 * the XSRF filter covers ScriptRunner endpoints, and which HttpServletRequest
 * type is passed, so an explicit header check can be added. */
appFootprint(
    httpMethod: "POST",
    groups: ["confluence-administrators"]
) { MultivaluedMap queryParams, String body ->

    long started = System.currentTimeMillis()

    def refuse = { int status, String stage, String message ->
        Map<String, Object> payload = new LinkedHashMap<String, Object>()
        payload.put("ok", Boolean.FALSE)
        payload.put("written", Boolean.FALSE)
        payload.put("stage", stage)
        payload.put("error", message)
        payload.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Response.status(status)
            .entity(JsonOutput.prettyPrint(JsonOutput.toJson(payload)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    if (body == null || body.trim().isEmpty()) {
        return refuse(400, "request", "The request body is empty. The export payload is expected as JSON.")
    }
    if (body.length() > PageExport.MAX_PAYLOAD_CHARS) {
        return refuse(413, "request", "The export payload exceeds " + String.valueOf(PageExport.MAX_PAYLOAD_CHARS) + " characters.")
    }

    Object parsed = null
    try {
        parsed = new JsonSlurper().parseText(body)
    } catch (Exception error) {
        return refuse(400, "request", "The request body is not valid JSON: " + error.getClass().getSimpleName())
    }
    if (!(parsed instanceof Map)) {
        return refuse(400, "request", "The request body must be a JSON object.")
    }

    /* The payload is the report model the GET branch serialised for this run, so
     * the page shows exactly the figures the administrator saw. It travels through
     * the browser, which means an administrator could tamper with it - the same
     * administrator who may edit any page anyway. Everything is escaped on the way
     * into storage format, and the decision carry-over below is unaffected by it:
     * decisions come from the existing page and are read on the server. */
    Map<String, Object> request = PageExport.copyMap((Map<?, ?>) parsed)

    /* ---- Staged lookups ---------------------------------------------------- */

    /* Rendering the report reads nothing. Everything the export form needs arrives
     * here on demand, one stage per request, discriminated by "action":
     * spaces -> pages -> write. A body without an action is the write, so the write
     * path below keeps the shape and the order it always had. There is no target
     * stage: this endpoint writes into its own instance. */
    String requestedAction = PageExport.str(request, "action", "write").toLowerCase(Locale.ROOT)

    def answer = { Map<String, Object> data ->
        data.put("executionMs", Long.valueOf(System.currentTimeMillis() - started))
        return Response
            .ok(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    if (requestedAction == "spaces") {
        /* No Confluence service is resolved in this stage at all. The one it used to
         * resolve is a Spring AOP proxy the ScriptRunner chaining classloader cannot
         * see, which is what made this stage refuse on every instance it was opened
         * on. The list comes off the SPACES table now, through the SAL read-only
         * executor; SpaceCatalog above holds the statement and every decision. */
        Map<String, Object> executor = Db.factory()
        Object executorFactory = executor.get("factory")
        if (executorFactory == null) {
            /* Db.factory never hands back nothing without naming a reason, so the
             * reason is printed rather than replaced by a marker. */
            return refuse(500, "spaces", String.valueOf(executor.get("failure")) + " " +
                SpaceCatalog.NOT_EMPTY)
        }

        Map<String, Object> listed = null
        try {
            listed = (Map<String, Object>) Db.withConnection(executorFactory) { Connection connection ->
                return Db.spaceRows(connection)
            }
        } catch (Throwable error) {
            return refuse(500, "spaces", "The space list could not be read (" + Db.why(error) + "). " +
                SpaceCatalog.NOT_EMPTY)
        }
        if (listed == null) {
            /* The executor returned without handing back a result. That is not an
             * empty instance either, and saying so is the difference between an
             * answer and a guess. */
            return refuse(500, "spaces", "The read-only executor returned no result at all, so no " +
                "space was read. " + SpaceCatalog.NOT_EMPTY)
        }
        if (listed.get("ok") != Boolean.TRUE) {
            return refuse(500, "spaces", String.valueOf(listed.get("error")))
        }

        List<Map<String, Object>> spaceRows = (List<Map<String, Object>>) listed.get("spaces")
        if (spaceRows.isEmpty()) {
            return refuse(500, "spaces", "The space inventory answered but named no current space, so no space can be picked.")
        }

        Map<String, Object> spacePayload = new LinkedHashMap<String, Object>()
        spacePayload.put("ok", Boolean.TRUE)
        spacePayload.put("action", "spaces")
        spacePayload.put("spaces", spaceRows)
        /* The cap travels WITH the rows, together with the ordering it would cut by,
         * so the browser can announce a cut without knowing either. A cap nobody is
         * told about is how a 5038-space instance silently becomes 2000 spaces. */
        spacePayload.put("truncated", listed.get("truncated"))
        spacePayload.put("cap", listed.get("cap"))
        spacePayload.put("order", listed.get("order"))
        return answer(spacePayload)
    }

    if (requestedAction == "pages") {
        String searchSpace = PageExport.str(request, "spaceKey", "")
        String searchTitle = PageExport.str(request, "query", "").trim()
        if (searchSpace.isEmpty()) {
            return refuse(400, "pages", "No space was selected, so there is nothing to look in.")
        }
        if (searchTitle.length() < PageExport.MIN_SEARCH_CHARS) {
            return refuse(400, "pages", "Type at least " + String.valueOf(PageExport.MIN_SEARCH_CHARS) +
                " characters of the page title.")
        }

        PageService pageLookup = ComponentLocator.getComponent(PageService.class)
        SearchManager searchLookup = ComponentLocator.getComponent(SearchManager.class)
        if (pageLookup == null || searchLookup == null) {
            return refuse(500, "pages", "A component needed for the page search could not be resolved (" +
                (pageLookup == null ? "PageService" : "SearchManager") + "), so the parent page could not be " +
                "looked up. That is a failed lookup, not a space without that page.")
        }

        /* The lookup is no longer exact-only. It stays exact FIRST - the exact hit
         * is still offered on top - and then adds whole-word title matches from
         * the content index, with a trailing star on the last word. Typing
         * "footprint" now finds "Confluence App Footprint - Executive Summary",
         * which the exact match never could. Details in Analyzer.searchPagesByTitle. */
        Map<String, Object> found = Analyzer.searchPagesByTitle(searchLookup, pageLookup, searchSpace, searchTitle,
            PageExport.SEARCH_LIMIT)
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

    PageManager pageManager = ComponentLocator.getComponent(PageManager.class)
    PageService pageService = ComponentLocator.getComponent(PageService.class)
    SpaceService spaceService = ComponentLocator.getComponent(SpaceService.class)

    if (pageManager == null || pageService == null || spaceService == null) {
        return refuse(500, "validate", "A required Confluence component could not be resolved.")
    }

    Space space = null
    try {
        space = spaceService.getKeySpaceLocator(spaceKey).getSpace()
    } catch (Exception error) {
        return refuse(500, "validate", "The space \"" + spaceKey + "\" could not be read: " + error.getClass().getSimpleName())
    }
    if (space == null) {
        return refuse(400, "validate", "There is no space with the key \"" + spaceKey + "\".")
    }

    /* Three outcomes, kept apart in the response: no parent, a parent that was
     * found, and a parent this run created. Creating is never reported as
     * finding - an administrator who reads "found" believes the page was already
     * there and stops looking for the one that was just made. */
    Page parentPage = null
    String parentAction = "none"

    if (!parentRaw.isEmpty()) {
        long parentId = 0L
        try {
            parentId = Long.parseLong(parentRaw)
        } catch (NumberFormatException ignored) {
            return refuse(400, "validate", "The parent page ID \"" + parentRaw + "\" is not a number.")
        }
        try {
            parentPage = pageService.getIdPageLocator(parentId).getPage()
        } catch (Exception error) {
            return refuse(500, "validate", "The parent page could not be read: " + error.getClass().getSimpleName())
        }
        if (parentPage == null) {
            return refuse(400, "validate", "There is no page with the ID " + parentRaw + ".")
        }
        if (!spaceKey.equalsIgnoreCase(String.valueOf(parentPage.getSpaceKey()))) {
            return refuse(400, "validate", "The parent page " + parentRaw + " sits in space \"" +
                String.valueOf(parentPage.getSpaceKey()) + "\", not in \"" + spaceKey + "\".")
        }
        parentAction = "found"
    }

    /* ---- Decision read ----------------------------------------------------- */

    /* The exact locator returns the persistence Page used by the established
     * decision parser and write path. */
    Page existingPage = null
    try {
        existingPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, title).getPage()
    } catch (Exception error) {
        return refuse(409, "read", "The existing page could not be read (" + error.getClass().getSimpleName() +
            "). Nothing is written, so no decision can be lost.")
    }

    DecisionRead read = new DecisionRead()
    if (existingPage != null) {
        String existingStorage = null
        try {
            existingStorage = existingPage.getBodyAsString()
        } catch (Exception error) {
            return refuse(409, "read", "The body of the existing page could not be read (" + error.getClass().getSimpleName() +
                "). Nothing is written, so no decision can be lost.")
        }
        read = PageExport.parseDecisions(existingStorage)
        read.pageId = existingPage.getIdAsString()
        read.pageVersion = existingPage.getVersion()
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
        return Response.status(409)
            .entity(JsonOutput.prettyPrint(JsonOutput.toJson(refusal)))
            .type("application/json; charset=UTF-8")
            .build()
    }

    /* ---- Parent page from a typed title ------------------------------------ */

    /* There is no Create button. A title that was typed and never picked is
     * resolved here, in the generating request, which is the only moment at which
     * the answer is still current. It sits AFTER the fail-closed decision read on
     * purpose: a run that is about to be refused with a 409 must not leave a
     * container page behind that nothing was ever filed under.
     *
     * The exact title is re-checked immediately before the create, not only in the
     * search the browser ran earlier. That covers the page somebody else created
     * in between and the administrator who saw a hit, did not click it and
     * generated anyway. Neither produces a second page with the same title. A
     * failed read stays a failed read and never degrades into "no such page",
     * which would be answered by creating a duplicate. */
    if (parentPage == null && !parentTitleRaw.isEmpty()) {
        try {
            parentPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, parentTitleRaw).getPage()
        } catch (Exception error) {
            return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" could not be looked up in \"" +
                spaceKey + "\" (" + PageExport.errorDetail(error) + "). That is a failed read, not a space without " +
                "that page, so nothing was created and nothing is written.")
        }

        if (parentPage != null) {
            parentAction = "found"
        } else {
            /* Written through PageManager.saveContentEntity with a SaveContext,
             * the same path the report page below uses. ContentService is a Spring
             * AOP proxy and fails under the ChainingClassLoader; that is measured
             * and is not tried again here. */
            try {
                Page container = new Page()
                container.setVersion(1)
                container.setSpace(space)
                container.setTitle(parentTitleRaw)
                container.setBodyContent(new BodyContent(container, PageExport.PARENT_BODY, BodyType.XHTML))
                container.setCreator(AuthenticatedUserThreadLocal.get())
                pageManager.saveContentEntity(container, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)
                parentPage = pageService.getTitleAndSpaceKeyPageLocator(spaceKey, parentTitleRaw).getPage()
            } catch (Exception error) {
                return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" could not be created in \"" +
                    spaceKey + "\" (" + PageExport.errorDetail(error) + "). Nothing is written: a report filed at the " +
                    "top level of the space instead would sit where nobody looks for it.")
            }
            if (parentPage == null) {
                return refuse(500, "parent", "The parent page \"" + parentTitleRaw + "\" is not readable after the " +
                    "save, so the report has no confirmed place to go. Nothing is written.")
            }
            parentAction = "created"
        }
    }

    /* ---- Write ------------------------------------------------------------- */

    /* The base URL is read from this instance, never taken from the payload the
     * browser sent back. An administrator could edit the payload; they cannot edit
     * what GlobalSettingsManager reports. It is resolved before rendering so the page
     * always names the instance it describes, and it costs nothing at runtime:
     * Settings.getBaseUrl() is a local read, no network call. */
    String instanceBaseUrl = null
    String instanceSiteTitle = null
    try {
        GlobalSettingsManager reportSettings = ComponentLocator.getComponent(GlobalSettingsManager.class)
        if (reportSettings != null) {
            String rawBaseUrl = reportSettings.getGlobalSettings().getBaseUrl()
            if (rawBaseUrl != null && !rawBaseUrl.trim().isEmpty()) {
                instanceBaseUrl = rawBaseUrl.trim()
            }
            String rawTitle = reportSettings.getGlobalSettings().getSiteTitle()
            if (rawTitle != null && !rawTitle.trim().isEmpty()) {
                instanceSiteTitle = rawTitle.trim()
            }
        }
    } catch (Exception ignored) {
        instanceBaseUrl = null
    }

    /* Version and build each on their own, so one unavailable value costs one row
     * and not the whole block. An unread value shows as not available, never as a
     * guess and never as an empty string that reads like a fact. */
    String instanceVersion = null
    try {
        instanceVersion = GeneralUtil.getVersionNumber()
    } catch (Throwable ignored) {
        instanceVersion = null
    }
    String instanceBuild = null
    try {
        instanceBuild = String.valueOf(GeneralUtil.getBuildNumber())
    } catch (Throwable ignored) {
        instanceBuild = null
    }

    Object reportNode = request.get("report")
    if (reportNode instanceof Map) {
        Map<String, Object> reportMap = (Map<String, Object>) reportNode
        if (instanceBaseUrl != null) {
            reportMap.put("baseUrl", instanceBaseUrl)
        }
        if (instanceSiteTitle != null) {
            reportMap.put("siteTitle", instanceSiteTitle)
        }
        if (instanceVersion != null) {
            reportMap.put("instanceVersion", instanceVersion)
        }
        if (instanceBuild != null) {
            reportMap.put("instanceBuild", instanceBuild)
        }
    }

    ExportOutcome outcome = PageExport.render(request, read, numberLocale)

    String action = existingPage == null ? "created" : "updated"
    int writtenVersion = existingPage == null ? 1 : read.pageVersion + 1
    String pageId = read.pageId

    /* The parent named in this run, and what this run does about the position of
     * an existing page. Both are decided before the write so the branches below
     * only carry it out. */
    String requestedParentId = parentPage == null ? null : parentPage.getIdAsString()
    String moveDecision = PageExport.MOVE_NOT_REQUESTED
    String moveError = null
    boolean parentReadBackOk = false
    String actualParentId = null

    try {
        if (existingPage == null) {
            Page fresh = new Page()
            fresh.setVersion(1)
            fresh.setSpace(space)
            fresh.setTitle(title)
            fresh.setBodyContent(new BodyContent(fresh, outcome.storage, BodyType.XHTML))
            fresh.setCreator(AuthenticatedUserThreadLocal.get())
            if (parentPage != null) {
                /* Ancestors run from the root of the space downwards, so the parent
                 * is appended last. The create path carries the parent in the entity
                 * itself; the update path below moves an existing page instead. */
                moveDecision = PageExport.MOVE_REQUESTED
                fresh.setParentPage(parentPage)
                parentPage.addChild(fresh)
                List<Page> ancestors = new ArrayList<Page>()
                List<Page> parentAncestors = parentPage.getAncestors()
                if (parentAncestors != null) {
                    ancestors.addAll(parentAncestors)
                }
                ancestors.add(parentPage)
                fresh.setAncestors(ancestors)
            }
            pageManager.saveContentEntity(fresh, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)
        } else {
            /* saveContentEntity(obj, origObj, ctx) is the documented history path:
             * "we need to pass in the modified as well as the original version of
             * the object". The fetched entity carries the modification, so its
             * pre-modification state is taken first and handed over as the
             * original. The body is all this save carries: the position is a
             * separate operation and is handled right after it. */
            Page original = (Page) existingPage.clone()
            existingPage.setBodyAsString(outcome.storage)
            pageManager.saveContentEntity(existingPage, original, DefaultSaveContext.SUPPRESS_NOTIFICATIONS)

            /* A parent named in this run is applied to a page that already exists,
             * not only to one this run creates. Without this the administrator who
             * typed a new parent title got the parent page created and the report
             * left where it was, with a response that said the parent was there.
             *
             * A run that names no parent still does not touch the position, which
             * is the case the old create-only guard was really protecting: a page
             * an administrator moved by hand stays moved.
             *
             * movePageAsChild(Page, Page) is the move operation on PageManager and
             * owns the ancestor list; it is not hand-rolled here. Verified against
             * confluence-10.2.10.jar with javap, alongside movePageToTopLevel,
             * movePageBefore, movePageAfter and moveChildrenToNewParent. */
            Page currentParent = null
            try {
                currentParent = existingPage.getParent()
            } catch (Exception ignored) {
                currentParent = null
            }
            moveDecision = PageExport.moveDecision(requestedParentId,
                currentParent == null ? null : currentParent.getIdAsString())
            if (PageExport.MOVE_REQUESTED.equals(moveDecision)) {
                try {
                    pageManager.movePageAsChild(existingPage, parentPage)
                } catch (Exception error) {
                    /* The report is written at this point. A failed move costs the
                     * position and is reported as such below; it never costs the
                     * report, and it is never swallowed either. */
                    moveError = PageExport.errorDetail(error)
                }
            }
        }

        /* Read back rather than trusting the save. The id and the version that go
         * into the response are the ones the page actually carries afterwards. */
        Page stored = pageService.getTitleAndSpaceKeyPageLocator(space.getKey(), title).getPage()
        if (stored == null) {
            return refuse(500, "write", "The page could not be written: it is not readable after the save.")
        }
        pageId = stored.getIdAsString()
        writtenVersion = stored.getVersion()

        /* The position is read back too. movePageAsChild returning without throwing
         * is a report about itself, not a measurement of the tree, and the create
         * path setting an ancestor list on an entity is no different. What goes
         * into the response is the chain the page actually carries afterwards.
         *
         * A chain that cannot be read leaves parentReadBackOk false, which the
         * verdict below turns into "unknown" - never into a move that worked and
         * never into one that failed. */
        try {
            List<Page> storedAncestors = stored.getAncestors()
            List<String> ancestorIds = null
            if (storedAncestors != null) {
                ancestorIds = new ArrayList<String>()
                for (Page ancestor : storedAncestors) {
                    ancestorIds.add(ancestor == null ? null : ancestor.getIdAsString())
                }
            }
            Map<String, Object> chain = PageExport.innermostAncestor(ancestorIds)
            parentReadBackOk = chain.get("measured") == Boolean.TRUE
            actualParentId = chain.get("parentId") == null ? null : chain.get("parentId").toString()
        } catch (Exception ignored) {
            parentReadBackOk = false
            actualParentId = null
        }
    } catch (Exception error) {
        return refuse(500, "write", "The page could not be written: " + PageExport.errorDetail(error))
    }

    /* Browse URL of the written page, built from this instance's own base URL and
     * the page id that was read back after the save. GlobalSettingsManager.getGlobalSettings()
     * hands out Settings and Settings.getBaseUrl() is the configured base URL of this
     * instance; no network call is involved. A base URL that cannot be read costs the
     * link and says so - it never costs the write, which has already happened. */
    String pageUrl = null
    String pageUrlPrefix = null
    try {
        GlobalSettingsManager settingsManager = ComponentLocator.getComponent(GlobalSettingsManager.class)
        String baseUrl = settingsManager == null ? null : settingsManager.getGlobalSettings().getBaseUrl()
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            String prefix = baseUrl.trim()
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1)
            }
            /* The prefix is kept because the parent page needs the same link and
             * a second read of the settings would answer the same thing twice. */
            pageUrlPrefix = prefix
            pageUrl = prefix + "/pages/viewpage.action?pageId=" + pageId
        }
    } catch (Exception ignored) {
        pageUrl = null
        pageUrlPrefix = null
    }
    if (pageUrl == null) {
        outcome.warnings.add("The page was written, but the base URL of this instance could not be read, so the " +
            "result carries no link to it.")
    }

    /* The measured verdict on the position. It is computed from the read-back, not
     * from the fact that a move was attempted, and a run that named no parent gets
     * a null rather than a claim it never made. */
    Map<String, Object> parentVerdict = PageExport.parentOutcome(requestedParentId, parentReadBackOk,
        actualParentId, moveError)
    if (parentVerdict.get("reason") != null) {
        outcome.warnings.add(parentVerdict.get("reason").toString())
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>()
    response.put("ok", Boolean.TRUE)
    response.put("written", Boolean.TRUE)
    response.put("action", action)
    response.put("spaceKey", spaceKey)
    response.put("title", title)
    response.put("pageId", pageId)
    response.put("pageVersion", Integer.valueOf(writtenVersion))
    response.put("pageUrl", pageUrl)
    response.put("parentPageId", parentPage == null ? null : parentPage.getIdAsString())
    response.put("parentAction", parentAction)
    response.put("parentTitle", parentPage == null ? null : parentPage.getTitle())
    response.put("parentPageUrl", parentPage == null || pageUrlPrefix == null
        ? null
        : pageUrlPrefix + "/pages/viewpage.action?pageId=" + parentPage.getIdAsString())
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

    return Response
        .ok(JsonOutput.prettyPrint(JsonOutput.toJson(response)))
        .type("application/json; charset=UTF-8")
        .build()
}
