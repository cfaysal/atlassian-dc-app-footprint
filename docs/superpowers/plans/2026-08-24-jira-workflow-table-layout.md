# Jira Workflow Table Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Jira Workflow Footprint table inside its app card without removing columns or changing measured data.

**Architecture:** Add a table-specific CSS contract and compact the four redundant reach headers. The existing HTML table remains the rendering mechanism; no data or endpoint behavior changes.

**Tech Stack:** Groovy ScriptRunner endpoint, embedded HTML/CSS, Groovy offline source-contract tests.

---

### Task 1: Add a failing layout contract

**Files:**
- Test: `jira/tests/jiraDCappFootprint.tests.groovy`

- [ ] **Step 1: Write the failing test**

Assert that the endpoint contains `class="workflow-table"`, a fixed-layout table rule, wrapping rules scoped to `.workflow-table`, and the four compact headers. Assert that the four verbose headers are absent.

- [ ] **Step 2: Run the test to verify it fails**

Run: `bash.exe D:/CFcon-DEV/.runtime-cache/op962/run-offline-tests.sh jira`

Expected: the new layout-contract assertions fail while the existing assertions remain green.

### Task 2: Implement the scoped table layout

**Files:**
- Modify: `jira/jiraDCappFootprint.groovy:4249-4257`
- Modify: `jira/jiraDCappFootprint.groovy:4801-4814`

- [ ] **Step 1: Add the table-specific CSS**

```css
.workflow-table { table-layout: fixed; }
.workflow-table th { white-space: normal; line-height: 1.25; overflow-wrap: anywhere; }
.workflow-table td { overflow-wrap: anywhere; }
```

- [ ] **Step 2: Apply the class and compact the headers**

Use `<table class="workflow-table">` and replace the four verbose reach headers with `Active Projects`, `Archived Projects`, `Active Issues`, and `Archived Issues`.

- [ ] **Step 3: Run the Jira suite**

Run: `bash.exe D:/CFcon-DEV/.runtime-cache/op962/run-offline-tests.sh jira`

Expected: all Jira assertions pass.

- [ ] **Step 4: Run the parse check and diff check**

Run: `bash.exe D:/CFcon-DEV/.runtime-cache/op962/run-offline-tests.sh parse`

Run: `git diff --check`

Expected: both scripts parse and the diff has no whitespace errors.

