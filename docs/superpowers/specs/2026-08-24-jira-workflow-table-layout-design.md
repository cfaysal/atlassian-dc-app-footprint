# Jira Workflow Table Layout Design

## Problem

The Jira Workflow Footprint table renders ten columns. Four verbose, globally non-wrapping headers force the table beyond the app-card width and require horizontal scrolling. The equivalent Confluence table fits with archived data enabled because its headers are shorter.

## Approved design

- Keep every workflow column and every measured value.
- Shorten only the four redundant reach headers to `Active Projects`, `Archived Projects`, `Active Issues`, and `Archived Issues`.
- Give only this table the `workflow-table` class.
- Apply `table-layout: fixed`, normal header wrapping, and `overflow-wrap: anywhere` to that class.
- Do not change global table styling, the data model, archive behavior, JSON, CSV, or other report tables.

## Acceptance criteria

- The Workflow Footprint table has no content-driven minimum width.
- Long headers, workflow names, and Project lists wrap inside their cells.
- All ten columns remain visible when archived evidence is enabled.
- Other Jira tables retain their existing layout.
- Jira offline tests and the Groovy parse check remain green.
