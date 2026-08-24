# Jira Wide Footprint Table Layout Design

## Problem

The Jira Workflow Footprint and Custom Field Footprint tables each render ten columns. Verbose, globally non-wrapping headers force both tables beyond the app-card width and require horizontal scrolling. The equivalent Confluence table fits with archived data enabled because its headers are shorter.

## Approved design

- Keep every workflow column and every measured value.
- Shorten the four redundant Workflow reach headers to `Active Projects`, `Archived Projects`, `Active Issues`, and `Archived Issues`.
- Shorten the four verbose Custom Field measurement headers to `Issues · Active`, `Issues · Archived`, `Screen Reach · Active`, and `Screen Reach · Archived`; retain the full wording in `title` attributes.
- Give exactly these two wide tables the shared `footprint-table` class.
- Apply `table-layout: fixed`, normal header wrapping, and `overflow-wrap: anywhere` to that class.
- Do not change global table styling, the data model, archive behavior, JSON, CSV, or other report tables.

## Acceptance criteria

- Both wide footprint tables have no content-driven minimum width.
- Long headers, field types, workflow names, and Project lists wrap inside their cells.
- All ten columns remain visible when archived evidence is enabled.
- Other Jira tables retain their existing layout.
- Jira offline tests and the Groovy parse check remain green.
