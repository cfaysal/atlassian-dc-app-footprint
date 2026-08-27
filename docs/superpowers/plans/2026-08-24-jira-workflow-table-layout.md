# Jira Workflow Table Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep both ten-column Jira footprint tables inside their app cards without removing columns or changing measured data.

**Architecture:** Add a shared CSS contract scoped to the Workflow and Custom Field footprint tables and compact their verbose measurement headers. The existing HTML tables remain the rendering mechanism; no data or endpoint behavior changes.

**Tech Stack:** Groovy ScriptRunner endpoint, embedded HTML/CSS, Groovy offline source-contract tests.

---

### Task 1: Add a failing layout contract

**Files:**
- Test: `jira/tests/jiraDCappFootprint.tests.groovy`

- [ ] **Step 1: Write the failing test**

Assert that the endpoint contains exactly two `class="footprint-table"` tables in the correct sections, fixed-layout and wrapping rules scoped to `.footprint-table`, and compact headers for both tables.

- [ ] **Step 2: Run the test to verify it fails**

Run: the Jira offline suite, as described in `jira/tests/README.md`

Expected: the new layout-contract assertions fail while the existing assertions remain green.

### Task 2: Implement the scoped table layout

**Files:**
- Modify: `jira/jiraDCappFootprint.groovy:4249-4257`
- Modify: `jira/jiraDCappFootprint.groovy:4801-4814`

- [ ] **Step 1: Add the table-specific CSS**

```css
.footprint-table { table-layout: fixed; }
.footprint-table th { white-space: normal; line-height: 1.25; overflow-wrap: anywhere; }
.footprint-table td { overflow-wrap: anywhere; }
```

- [ ] **Step 2: Apply the class and compact the headers**

Use `<table class="footprint-table">` for both wide tables. Keep the compact Workflow headers and replace the four verbose Custom Field headers with `Issues · Active`, `Issues · Archived`, `Screen Reach · Active`, and `Screen Reach · Archived`, retaining their full wording in `title` attributes.

- [ ] **Step 3: Run the Jira suite**

Run: the Jira offline suite, as described in `jira/tests/README.md`

Expected: all Jira assertions pass.

- [ ] **Step 4: Run the parse check and diff check**

Run: the parse check, as described in `jira/tests/README.md`

Run: `git diff --check`

Expected: both scripts parse and the diff has no whitespace errors.
