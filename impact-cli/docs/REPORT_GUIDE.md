# How to read the impact analysis report

A single `analyze --output both` run produces three files in the same directory as `--out`:

| File | For | Format |
|---|---|---|
| `impact-report.html` | Humans — open in a browser | Single-file HTML with embedded CSS |
| `impact-report.json` | CI / tooling / scripted post-processing | JSON tree mirroring the report |
| `generated-testcases.md` | QA — copy rows into your test-management system | Markdown tables, one section per area |

This guide walks the HTML report top-to-bottom and explains what every section means.
The same data is in the JSON and Markdown — just rendered differently.

---

## The header

```
Impact Report — patch:C:\changes\fix-789.patch
Generated 2026-05-14T13:35:21
Overall risk: HIGH         Changed symbols: 395
Entry points reached: 298  Forward reach: 11,761
```

| Field | Meaning |
|---|---|
| **Overall risk** | `HIGH` / `MEDIUM` / `LOW`. The maximum risk of any single changed symbol — see [risk scoring](#risk-scoring) below. |
| **Changed symbols** | Total number of distinct AST symbols (methods + constructors + classes) that the patch touches |
| **Entry points reached** | Distinct entry-point classes (servlets, schedulers, task handlers, …) reached by walking the call graph backward from changed methods |
| **Forward reach** | Distinct methods downstream of any changed method (depth limited by `--depth`, default 6) |

Use this as the **30-second summary**. If risk is LOW and reach is small the change is
narrow; if risk is HIGH or reach is in the thousands you're looking at a wide change
that needs broad QA.

---

## 1. Impact by Layer

```
| Layer                   | Count          | Sample                                              |
| ----------------------- | -------------- | --------------------------------------------------- |
| Java                    | 390 / 5 / 11761| (methods changed / classes changed / forward reach) |
| Entry points            | 298            | AddSiteCollectionAdmin, GrantPermission, …          |
| REST URLs reached       | 19             | /api/orders/create, /api/users/login, …             |
| DB tables               | 5 (W=3 R=2)   | Orders, OrderItems, OrderEvents                      |
| DB columns              | 34             | (across the tables above)                            |
| JS / Ember files        | 0              | _(none)_                                             |
| HTML pages              | 0              | _(none)_                                             |
| C# files                | 2              | OrderClient, OrderSyncWorker                         |
| PowerShell scripts      | 0              | _(none)_                                             |
| Task types              | 9              | GrantPermission, CheckPermission, …                  |
| JGroups msgs            | 10             | DIST_TASK_ASSIGNMENT, DIST_HEARTBEAT, …              |
| Polyglot files in patch | 15             | js=1 hbs=1 cs=1 xml=6 properties=5 other=1           |
```

This is the **executive cross-component summary** — one row per "layer" of the system.
If a row says `0` for your project's main UI tech (e.g. JS) but the change is supposedly
UI-facing, that's a red flag worth investigating.

---

## 2. APIs Affected

The first detail section. Every REST URL the patch can affect.

```
| URL                                 | Feature                        | Source         | Owner               | JS callers           | C# callers | HTML refs | Reached by | Risk   |
| ----------------------------------- | ------------------------------ | -------------- | ------------------- | -------------------- | ---------- | --------- | ---------- | ------ |
| servlet:GrantPermission             | Action: **Grant Permission**   | java-reached   | GrantPermission     | —                    | —          | —         | 1 methods  | HIGH   |
| /api/orders/create                  | Action: **create**             | java-reached   | OrderController     | order-form           | —          | —         | 3 methods  | HIGH   |
| /api/orders/sync                    | Action: **sync**               | xml-declared   | OrderController     | —                    | OrderSync  | —         | 0 methods  | MEDIUM |
| external:sharepoint:/_api/web/…     | ExternalApi: **SharePoint CSOM** | c#-touches   | —                   | —                    | OrderSync  | —         | 0 methods  | MEDIUM |
```

### Columns

* **URL** — the REST URL. SPMP-style synthetic servlet URLs use `servlet:Name`. Real URLs start with `/api/` or `/RestAPI/`. External (non-SPMP) URLs are prefixed `external:sharepoint:` or `external:graph:`.
* **Feature** — the user-facing name a non-developer recognises. Derived from:
  * `:HANDLES :TaskType` edge on the owner class → `TaskType: Grant Permission`
  * `:Servlet` label → `Action: <class simple name in English>`
  * `*ReportGenerator` / `*DataCollector` / `*Report` naming → `Report: <feature name>`
  * `:Scheduler` / `:Job` label → `Scheduler: …` / `Job: …`
  * `external:` URL prefix → `ExternalApi: SharePoint CSOM` / `Microsoft Graph`
* **Source** — how this URL got onto the list:
  * `java-reached` — the call graph traced a changed Java method to a class that exposes this URL
  * `xml-declared` — the URL was added or changed in a REST-config XML hunk
  * `c#-touches` — a patched C# file contains a string literal for this URL
  * `both` / `xml+c#` / etc. — combinations
* **Owner** — the Java class (or owning system) that handles the URL
* **JS callers / C# callers / HTML refs** — code in those languages that already calls this URL today
* **Reached by** — how many distinct changed Java methods reach this URL via the call graph
* **Risk** — see [risk scoring](#risk-scoring) below

### What to look at

* HIGH-risk rows first — those are the URLs you definitely need to test.
* Rows where **JS callers ≠ —** — the UI hits this endpoint, so a non-trivial regression test is needed.
* Rows where the **source is `xml-declared`** — these are new URLs introduced by the patch. Verify they're wired correctly.

---

## 3. Database Tables Affected

```
| Table                | Feature                                          | Source         | Changed columns                  | Writers                          | Readers              | Risk   |
| -------------------- | ------------------------------------------------ | -------------- | -------------------------------- | -------------------------------- | -------------------- | ------ |
| Orders               | Action: **create**, Action: **cancel**           | both           | STATUS, CANCELLED_REASON (+1 more) | 4 (OrderDao.insert(), …)       | 3 (OrderDao.find()…) | HIGH   |
| OrderItems           | —                                                | schema-changed | QUANTITY, UNIT_PRICE             | —                                | —                    | MEDIUM |
```

### Columns

* **Table** — the SQL table name (case-sensitive as ingested)
* **Feature** — which user-facing operations write/read this table (derived by walking from entry-point owners that reach the writers/readers)
* **Source** — `schema-changed` (column added/altered in `data-dictionary.xml` hunk), `java-reached` (a changed Java method writes/reads it), or `both`
* **Changed columns** — for `schema-changed`, the list of column names introduced/altered in the patch (capped at 6 visible; "+N more" suffix)
* **Writers / Readers** — count of distinct Java methods + a sample of the most relevant ones, formatted `Class.method()`
* **Risk** — promoted to HIGH if any reaching Java method is HIGH

### What to look at

* `schema-changed` rows — these involve a DB migration. Verify your environment runs the migration cleanly.
* HIGH-risk writers — the change can corrupt or alter data. Pick representative data, exercise the writers, verify rows in the table afterwards.

---

## 4. Schedules Affected

```
| Owner                          | Feature                              | Kind        | Source        | Trigger methods | Task types        | DB writes        | DB reads | Reached by | Risk   |
| ------------------------------ | ------------------------------------ | ----------- | ------------- | --------------- | ----------------- | ---------------- | -------- | ---------- | ------ |
| GrantPermissionTaskHandler     | TaskType: **Grant Permission**       | TaskHandler | patch-added   | —               | GrantPermission   | —                | —        | 0 methods  | MEDIUM |
| NightlyReportScheduler         | Scheduler: **Nightly Report**        | Scheduler   | reached       | run, execute    | —                 | ReportRunHistory | —        | 1 methods  | HIGH   |
```

### Columns

* **Owner** — the Java class for the schedule
* **Feature** — TaskType / Scheduler / Job display name
* **Kind** — `Scheduler` (Quartz / cron / framework-driven) / `Job` (one-shot) / `TaskHandler` (registered via TaskRegistry)
* **Source**:
  * `patch-added` — the patch introduces this schedule
  * `patch-modified` — the patch changes the schedule's class body
  * `reached` — the patch changes code that this schedule runs
* **Trigger methods** — methods marked `:EntryPoint` on this class
* **Task types** — for TaskHandlers, the `TaskType` ids it registers for
* **DB writes / reads** — tables the schedule's methods touch
* **Reached by** — count of changed methods that the schedule's class calls into

### What to look at

* `patch-added` — these are **new background activity** the patch introduces. Verify they're registered and don't run on systems that shouldn't have them yet.
* `reached` with HIGH risk — your nightly/scheduled jobs run different code now. Watch your job dashboards / cron logs.

---

## 5. UI Components Affected

```
| File                                                | Feature                       | Lang | Role               | Source                    | REST URLs called  | Uses / Used by              | Risk   |
| --------------------------------------------------- | ----------------------------- | ---- | ------------------ | ------------------------- | ----------------- | --------------------------- | ------ |
| components/order-form.js                            | Action: **create**            | JS   | Component          | calls-affected-api        | /api/orders/create | —                          | MEDIUM |
| templates/components/order-form.hbs                 | —                             | HBS  | ComponentTemplate  | uses-affected-component   | —                 | uses: components/order-form.js | MEDIUM |
| components/order-form.js                            | —                             | JS   | Component          | patched                   | —                 | used by: templates/components/orders/list.hbs | MEDIUM |
```

### Columns

* **File** — Ember JS / HBS template path under `source/ember/app/`
* **Feature** — features the URLs this file calls belong to
* **Lang** — `JS` or `HBS`
* **Role** — `Component` / `Route` / `Controller` / `Service` / `ComponentTemplate` / `RouteTemplate` / …
* **Source**:
  * `patched` — the file is in the diff
  * `calls-affected-api` — the file already calls a URL that the patch reaches
  * `uses-affected-component` — an HBS template that renders a JS component affected by the patch
  * `renders-template-of-affected` — JS↔HBS path-convention pair
* **REST URLs called** — for `calls-affected-api`, the URL list
* **Uses / Used by** — HBS templates this JS file is used by, or JS components this HBS template uses

### What to look at

* Each `patched` UI file — your QA needs to verify the component still renders + behaves correctly.
* Each `calls-affected-api` row — the UI screen that triggers the URL needs an end-to-end smoke test.

---

## 6. Polyglot Changes in Patch

```
| Language   | Role            | File                             | Hunks | Change | Risk   | Downstream                                                            |
| ---------- | --------------- | -------------------------------- | ----- | ------ | ------ | --------------------------------------------------------------------- |
| XML        | DataDictionary  | conf/data-dictionary.xml         | 1     | MODIFY | MEDIUM | tables Orders, OrderItems; columns Orders.STATUS, … (+3 more)         |
| XML        | RestApi         | conf/RestAPIs.xml                | 1     | MODIFY | MEDIUM | declares /api/orders/sync; owner OrderController; JS callers order-form |
| JS         | Component       | source/ember/app/components/order-form.js | 1 | ADD    | MEDIUM | symbols (4): didInsertElement, save, onError, validateForm           |
| HBS        | ComponentTemplate | source/ember/app/templates/components/order-form.hbs | 1 | ADD | MEDIUM | owner js:components/order-form.js                                |
| CS         |                 | source/c_sharp/OrderClient/OrderSync.cs | 9 | MODIFY | LOW    | symbols (6): OrderSync (class), GetPending, UpdateStatus, …          |
| PROPERTIES | Properties      | resources/messages_en_US.properties | 4 | MODIFY | LOW    | _(file-level only)_                                                  |
```

Every **non-Java** file touched by the patch with its graph-resolved downstream effects:
* For `XML/DataDictionary` — extracted table + column names
* For `XML/RestApi` — extracted URL declarations and their JS/C# callers
* For `JS` and `CS` — function/method-level symbols via tree-sitter
* For others — file-level only

---

## 7. Generated Test Cases

Plain-English test cases grouped by **area** (DIST / MGMT / REPT / ADM / CFG / DB / JS / HBS / CS / REST / MISC).

```
## DIST — Distributed Tasks (3 test cases)

| ID            | Title                                      | Steps                                                                                                              | Expected                                                                  |
| ------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------- |
| GEN-DIST-001  | Verify Distributed Report Worker Runnable  | 1. Trigger the Distributed Report Worker Runnable feature via the UI or REST API.<br>2. Wait for completion.<br>3. Observe the result. | No errors in the server log. New rows in: OrderEvents, ReportRunHistory. |
```

Each row is a self-contained test you can hand to a QA tester. Same column shape as
typical `TestCases.md` files — copy directly into your test-management system.

---

## 8. Symbol Detail (developer view)

The deepest table — one row per `ChangedSymbol` with its forward reach, backward reach
to entry points, and DB table writes/reads.

This is the **debug / explain** view. If a test case mentions an unexpected entry point,
this is where you trace why.

---

## Risk scoring

Three levels: **HIGH / MEDIUM / LOW**. Computed per-changed-symbol then rolled up.

| Factor | Effect |
|---|---|
| > 5 entry points reach this symbol | HIGH |
| 1–5 entry points | MEDIUM |
| 0 entry points (leaf utility code) | LOW |
| Owner package contains `clustering.distributed` / `management.distributed` / `reports.onclick` | promoted to MEDIUM minimum |
| Symbol is in a `Servlet` / `TaskHandler` / `Scheduler` class | promoted to MEDIUM minimum |
| Symbol DELETED | LOW (graph-wise no fan-out — surfaces in a separate warning panel) |

Polyglot files inherit the risk of the Java owner they map to, escalated to HIGH if
that owner is HIGH.

The **overall risk** of the report is the max of any individual symbol.

The `--fail-on` flag uses this for CI gating: `--fail-on MEDIUM` exits 2 if any
symbol is MEDIUM or HIGH; `--fail-on HIGH` only fails on HIGH.

---

## Tips for non-developer readers

If you're handed an impact report and don't write code:

1. **Read only the Feature column.** The "URL", "Owner Class FQN", and "Java methods"
   columns are debug aids — ignore them. The Feature column tells you what user-facing
   thing the change touches.
2. **Use risk to prioritise.** HIGH first, then MEDIUM. LOW rows are noise unless you
   have time to verify everything.
3. **The Generated Test Cases section is the actionable output.** Copy each row into
   your test plan. The Steps are written for a tester, not a developer.
4. **If you see `—` in a Feature column**, the tool couldn't classify the owner as a
   user-facing concept (usually means the class is a utility / data-access / helper,
   not something a user invokes directly). Cross-reference via the Schedules and APIs
   tables to find the actual user surface.
5. **Polyglot Changes in Patch** is the "did you forget to test the UI?" guard rail.
   If your change is "purely backend" but this section lists JS/HBS files, that means
   somebody also touched the UI in this patch.

For a worked example see [URL_DETECTION_EXAMPLE.md](URL_DETECTION_EXAMPLE.md).
