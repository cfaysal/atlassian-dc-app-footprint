# Confluence App Footprint: administrator guide

This guide covers [confluenceDCappFootprint.groovy](../confluence/confluenceDCappFootprint.groovy). It measures installed Confluence Data Center app capabilities and macro usage to support migration, renewal, and removal reviews. A scan does not uninstall or change an app.

## Install and open

You need Confluence Data Center 10, ScriptRunner 10 or newer, and membership in `confluence-administrators`. Put the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from the file and save it. Install from a file because the large script exceeds the inline serialized limit.

~~~text
https://<confluence-base-url>/rest/scriptrunner/latest/custom/appFootprint
~~~

The GET scan and POST page export are restricted to `confluence-administrators`.

## Scan and navigate

1. Open the URL for the default HTML report. The header shows instance, build, generation time, and active scan options.
2. Use **Archived**, **Modules**, **System apps**, and **Disabled apps** to rerun with those settings. Use **JSON**, **CSV Apps**, **CSV Macros**, or **CSV Modules** for the current settings.
3. Search by app, vendor, plugin key, macro, or capability. Filter by impact level, **Current footprint only**, or **Diagnostics only**. These browser filters do not rescan.
4. Inspect each app card's extension capabilities, macro footprint, module list, and diagnostics. The separate **Native Confluence User Macros** section is local configuration, not a Marketplace app footprint.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `format=html\|json\|csv` | `html` | Report format. |
| `level=app\|macro\|module` | `app` | CSV row type; ignored for HTML and JSON. |
| `includeSystem` | `false` | Include system-provided apps. |
| `includeDisabled` | `true` | Include installed disabled apps. |
| `includeArchived` | `false` | Measure archived spaces separately. |
| `includeModules` | `false` | Include module details in HTML and JSON. |
| `scanUsage` | `true` | Measure macro references in content. `false` inventories provided macros only. |
| `scanAliases` | `false` | Also resolve macro aliases during usage scan. |
| `scanBudgetMs` | `120000` | Usage-scan budget in milliseconds; `0` is unlimited. |
| `appKey` | none | Restrict the report to one plugin key. |
| `numbers=de\|en` | `de` | Thousands separator style. |
| `diag` | `false` | Run and display the export read-path self-check. |

Example: `?includeArchived=true&format=csv&level=macro`. A restricted `appKey` report is useful for investigation, but it is not an instance-wide app comparison.

## Read the result

**Key figures** count apps, apps with current footprint, provided and used app macros, current and archived macro associations, blueprints/templates, and native user macros. **Macro associations** count references, while **unique content** counts distinct pages or blog posts. A macro may create several associations in one content item. Current usage covers content in current spaces; archived spaces are separate and require `includeArchived=true`. Blueprints, templates, custom content, UI, REST, listeners, and jobs are inventory signals unless a dedicated usage resolver exists.

Each app card distinguishes available modules from measured macro usage and provides read diagnostics. A native user macro is your Confluence configuration, so it is shown separately rather than attributed to an app. A provided macro that is not found in content can still support runtime or future use.

Impact uses the highest measured current share: **CRITICAL** at 50% or more, **HIGH** at 20%, **MEDIUM** at 5%, and **LOW** above zero. **LEGACY_ONLY** means archived evidence with no detected current footprint. **REVIEW_REQUIRED** means gaps prevent a confident zero. **NO_DETECTABLE_FOOTPRINT** requires complete measured zero. **NOT_SCANNED** means usage was skipped. The decommission-candidate list is an investigation queue, not an uninstall instruction.

An asterisk indicates a partial lower bound. `n/m` means not measured because a scan was disabled or budgeted; it is not zero. Read **Measurement notes**, app diagnostics, and the options line before quoting a count. `scanAliases=false` can leave alias usage unresolved. An omitted or incomplete archived scan cannot prove no archived dependency.

JSON contains report/options, summary, app and macro details, impact dimensions, and diagnostics. CSV is comma-separated:
- `level=app`: one row per app with impact, modules, capability counts, current and archived usage states, and diagnostics.
- `level=macro`: one row per app-provided or native user macro with usage state, current/archived content and space counts, aliases, and diagnostics.
- `level=module`: one row per app module with category, descriptor, keys, class, and enabled state.

Save options and generation time with exported data. CSV module rows are inventory, not proof of active usage.

## Optional Confluence page export

Press **Export to Confluence**, search and select a destination space, optionally select a parent page, enter the title, then press **Generate Confluence Page**. The page is written on the same Confluence instance only after this action. It is marker-protected and preserves the existing **Decision** column on regeneration. A failed Decision read prevents the write. Missing apps' decisions move to **Decisions Without a Matching App**. Check the returned page, destination, and carried decisions.

## Troubleshooting and handling

- **403:** use a member of `confluence-administrators` and check the endpoint group gate.
- **Partial usage:** inspect `scanBudgetMs`, measurement notes, and app diagnostics. Increase the budget only when a longer production read is acceptable.
- **No macro usage:** distinguish `scanUsage=false`, budget exhaustion, alias settings, read errors, and measured zero.
- **Archived values absent:** enable `includeArchived=true` and verify the archived scan's completeness.
- **Export control unavailable or refused:** use `diag=true` for the read-path self-check, then check destination permissions, parent selection, marker, and Decision read.

The GET scan makes no outbound network call. Reports can contain app names, content counts, space identities, and configuration details; handle downloads and exported pages as administrator data.
