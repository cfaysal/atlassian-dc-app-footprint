# Jira App Footprint: administrator guide

This guide covers [jiraDCappFootprint.groovy](../jira/jiraDCappFootprint.groovy). It measures installed apps' Jira Data Center configuration and data reach to support migration, renewal, and removal reviews. A scan does not uninstall or change an app.

## Install and open

You need Jira Data Center, licensed ScriptRunner, and membership in `jira-administrators`. The script is neutral to the ScriptRunner 8/9 `javax` and 10+ `jakarta` REST namespaces. Put the Groovy file in the ScriptRunner script root. In **Administration > ScriptRunner > REST Endpoints**, create a **Custom endpoint** from the file and save it. Use a file, not an inline script, because the file exceeds the serialized inline limit.

~~~text
https://<jira-base-url>/rest/scriptrunner/latest/custom/appFootprint
~~~

The GET scan and POST Confluence export are restricted to `jira-administrators`.

## Scan and navigate

1. Open the URL for the default HTML scan. The header states instance, generation time, and active options. The scan can take time on a large instance.
2. Use **Drafts**, **Modules**, **Archived**, **System apps**, **Disabled apps**, **Issue counts**, and **Project reach** to rerun with those settings. **JSON** and **CSV** download the selected scan settings.
3. Search app, vendor, plugin key, or extension type. Filter by impact level, **Detected footprint only**, or **With diagnostics only**. These filters hide cards in the browser; they do not change the scan.
4. Expand an app card to inspect custom fields, screen placements, workflow references and extension points, modules, and diagnostics. Compare current and archived evidence before deciding about an app.

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `format=html\|json\|csv` | `html` | Report format. |
| `includeSystem` | `false` | Include system-provided apps. |
| `includeDisabled` | `true` | Include installed disabled apps. |
| `includeDrafts` | `false` | Inspect draft workflows too. |
| `includeModules` | `false` | Emit full module details per app. |
| `includeArchived` | `false` | Measure archived projects and work items separately. |
| `includeReach` | `true` | Measure project/work-item reach through screens and workflows. |
| `issueCounts` | `true` | Count work items with values in app-provided custom fields. |
| `issueBudgetMs` | `120000` | Shared time budget in milliseconds for issue counting and the screen-scheme index used by reach. `0` is unlimited. |
| `numbers=de\|en` | `de` | Thousands separator style in rendered output. |

Example: `?includeArchived=true&format=csv`. The **Archived** button enables a second, potentially costly measurement. If you disable `issueCounts` or `includeReach`, the corresponding counts are not measured; do not cite them as zero.

## Read the result

**Key figures** and the app cards distinguish installed modules, app-provided custom fields, work items with field values, screen placements, workflow references, workflow extension points, and reached projects/work items. A workflow **reference** is a text occurrence in a persisted workflow descriptor. An **extension point** is a configured post function, condition, validator, or pre function on a specific transition, including position. A dormant workflow module is capability installed but not configured.

The field count “Issues With Value” comes from Jira's custom-field count and can be a refreshed snapshot. With archived measurement enabled, the current/archive split combines that total with live archived reads, so the split may drift and needs review when defaults are involved. A screen or workflow reach count describes configuration reach, not observed user activity.

Impact is the highest measured share of relevant instance denominators: **CRITICAL** at 50% or more, **HIGH** at 20%, **MEDIUM** at 5%, and **LOW** above zero. **LEGACY_ONLY** means archived evidence without detected current reach. **REVIEW_REQUIRED** means an incomplete or omitted measurement prevents a confident zero. **NO_DETECTABLE_FOOTPRINT** requires a complete measured zero. A decommission candidate is a starting point for investigation, not permission to uninstall; UI-only or runtime behavior can evade this scan.

An asterisk marks a partial lower bound. `n/m` means not measured, and `err` means a failed measurement; neither is zero. Read each app's diagnostics and the options line before quoting counts. An archived scan that was skipped or partial cannot establish no archived dependency.

JSON includes report/instance/options, summary, app details, impact dimensions, and diagnostics. CSV is comma-separated with one row per app; it includes identity, impact, modules, field associations, workflow and extension counts, reach states, and diagnostics. The impact-dimensions cell is structured JSON inside CSV. Save the options and timestamp with either export.

## Optional Confluence page export

Press **Export to Confluence**. Choose a configured Confluence application link, search and select the destination space, optionally select a parent page, enter the page title, then press **Generate Confluence Page**. Opening the report makes no Confluence request; this staged export is the only write and contacts only the selected linked Confluence instance. It creates or updates its own marker-protected executive-summary page and preserves the **Decision** column. If the prior decisions cannot be read, it refuses the write. Decisions for apps absent from a later scan move to **Decisions Without a Matching App**. Verify the returned page after export.

## Troubleshooting and handling

- **403:** use a member of `jira-administrators` and check the endpoint group gate.
- **Slow or partial result:** inspect `issueBudgetMs` and measurement notes. Rerun with a larger budget during an appropriate window only if full counts are needed.
- **No count or no footprint:** distinguish off, `n/m`, `err`, partial, and measured zero before drawing a conclusion.
- **Unexpected workflow attribution:** inspect descriptor/module class evidence and diagnostics. A text reference is not the same as an extension point.
- **Export refused:** check application link, target permissions, selected space/page, and page marker. Do not bypass a failed Decision read.

Reports can contain app inventory, configuration links, project keys, and counts. Handle downloads and exported pages as administrator data.
