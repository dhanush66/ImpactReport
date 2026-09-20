# Boundary Destination Classes by Module

This document is the **source of truth** for which classes/methods the impact-analysis
boundary resolvers target. Each entry is verified by reading the destination's method
body (not just the class name) and confirming 3+ unrelated callers actually invoke it.

> Update this file whenever a destination changes, or when a new boundary domain is
> added. Every entry has the form: **destination FQN → method → why it was chosen
> over alternatives**. Resolver implementations should mirror this list exactly.

Last verified: 2026-05-26 against `adsm-ADMP_8050_OPEN_ISSUE_FIXES_BRANCH` (ADMP patches `1660081c13...4feea1efe7`, `758cdf37d5...7e22fcaf73` — Issue 13032, AND `9b3f4f5752...10d02b2e92` — Issue 13216).
Last code-sync: 2026-05-26 — §8c2-g Layer D attribute-level narrowing shipped via `:HANDLES_ATTRIBUTE` edges. `MacroAttributeResolver` (new BoundaryResolver) detects `ldapName.equals("X")` / `equalsIgnoreCase("X")` patterns inside NotificationMacro classes and emits `(Method)-[:HANDLES_ATTRIBUTE {attribute, block_start_line, block_end_line}]->(Class)` edges. At analyze time, Layer D's Cypher overlaps patch hunk lines against these block ranges — if overlap hits specific attributes, `MacroKeyRegistry.keysForAttributes()` narrows to only matching placeholders; otherwise falls back to all keys. Key fix: the `:HANDLES_ATTRIBUTE` edge lives on a phantom Method node (simple param types from `ResolverUtils.methodFqn`) while `:CONTAINS` targets the canonical node (fully-qualified params); the query uses `(:Method)-[h:HANDLES_ATTRIBUTE]->(cls)` (any Method → same class) rather than `(m)-[h]->(cls)` so FQN mismatch doesn't break the join. Verified on `9b3f4f5752` patch (Issue 13216): parseMacrosAdmin hunk L172–290 overlaps 3 attribute blocks (`WfComments` L179–187, `daysToExpireAccount` L194–218, `groupType` L227–238) — but only `daysToExpireAccount` has a `macro-keys.json` entry, so report now shows CRITICAL / `%DaysToExpireAccount%` (was all 23 keys). 37 `:HANDLES_ATTRIBUTE` edges total across the ADMP source. Previous code-sync (same day): §8c2-g Layer D shipped + backward-reach notification detection. Two additions: (1) When a changed method belongs to a `:NotificationMacro` class, flagged as CRITICAL risk with all keys including new custom attribute `%DaysToExpireAccount%` (dbColumn: `ADSMUserAccountDetails.ACCOUNT_EXPIRY_DATE`). (2) `runAffectedNotifications` gains a backward-reach supplement: for changed methods inside a `:NotificationMacro` class, uses `:GATES_DISPATCH` edges to find upstream notification senders (those methods have `:SENDS_NOTIFICATION` edges) — also checks one-hop upstream callers of gating methods. This closes the thread-boundary gap where `NotificationTrigger.start()` → `run()` → `sendNotification()` → `parseMacroForAdmin()` is not traversable via `:CALLS` edges (JVM thread dispatch). Verified on `9b3f4f5752` patch (Issue 13216): `WFNotificationMacro.parseMacrosAdmin()` directly modified → report shows: notifications=3 (workflow/HIGH, other/HIGH, acc/HIGH with sample senders `WorkFlowAction.approveRequest`, `WFMgmtAPIcall.executeWFRequest`, `ACCUtil.processExpiredRequests`, etc.) + macros=1 (WFNotificationMacro/CRITICAL/23 keys including `%DaysToExpireAccount%`). Previous code-sync (same day): §8c2 per-key attribution shipped (`MacroKeyRegistry` + sibling-method narrowing). Layer C no longer reports all 22 keys as binary-gating; instead it extracts sibling method calls from the dispatch block at ingest time (persisted as `r.sibling_methods` on `:GATES_DISPATCH`), cross-references them against a status-writer → macro-key lookup table at analyze time, and reports only the keys whose backing DB write is a co-resident sibling. For the 758cdf37d5 patch: `modifyRequestWorkflow(...)` is detected in the L2490-L2582 block → maps to `workstatus` + `status` → report shows `%WorkflowStatus%`, `%Status%` (was 22 keys pre-fix). Three-tier fallback: (1) sibling-call → status-writer table, (2) patched-method `:WRITES_TABLE` → per-class dbColumn matching + global table-write Condition 4 lookup, (3) all keys (only when neither tier resolves). `macro-keys.json` resource file added with comprehensive 6-class mapping (keys, placeholders, dbColumns, dbTables) plus `_statusWriterTable` entries and `tableWriteEntries`. `AffectedMacro` record gains `affectedMacroKeys` field; `report.ftl` renders new "Affected Placeholders" column with "(sibling-write narrowed)" or "(all — binary gating)" annotation. Seven implementation deltas total (previous six + this one). Previous code-sync (same day, earlier): §8c2 Layer C now ships end-to-end (see "✅ Shipped 2026-05-26" subsection at end of §8c2). On the 758cdf37d5 patch the analyzer surfaces `macros=1` with `WFNotificationMacro` HIGH-risk and sample `approveRequest (gates L2490-L2582)` — up from `macros=0` pre-fix. Six implementation deltas vs the original spec sketch are recorded inline: block-start anchors at the macro variable's declaration line (not `.init()`), concrete-type detection prefers `ObjectCreationExpr` initializer, `Macro`-suffix gating in `isMacroType()`, writer matches `:Class` (not `:NotificationMacro` — labels apply post-flush), `gatesDispatch` retained in master batch (not drained), and analyze-side hunk-rows use reconciled graph-form FQNs + `HtmlReportRenderer` now wires `macrosAffected` into the FreeMarker model. Previously this code-sync line described the §8c2 spec addition — §8c2 (Layer C — control-flow-gated notification dispatch) was added after the `758cdf37d5` patch surfaced a gap in Layer A/B. That patch is a null-guard for `fcBulkExecuteFormBean` access at `WorkFlowAction.approveRequest` L2518-L2521, **downstream of macro-init L2498 and upstream of `trigger.start()` L2584 in the same enclosing `if (modifyRequestWorkflow(...))` block**. Pure data-flow (Layer-B) misses it: patched lines write to `templateCategoryID`/`isMgmtModification`, neither of which the macro reads. The dependency is control-flow — pre-fix the NPE truncated the block before the dispatcher; post-fix the dispatcher runs. Layer C codifies the four-condition detection (co-resident block, AST-position-between, NPE-risk-altering change, sibling DB write feeding a known macro key) plus a status-writer → macro-key lookup table (`WorkFlow.modifyRequestWorkflow` → `workstatus`/`WFStatus`, `WorkFlow.modifyRequestStatus` → `status`, `ADSMRequestDetails` writes → `subject`/`description`, etc.). New edge type: `:GATES_DISPATCH` (distinct from `:POPULATES_MACRO`). The affected macro key for patch 2 is `admp.workflow.notification.macros.workstatus` (renders the CANCELED/REJECTED label written by the sibling `WorkFlow.modifyRequestWorkflow` call at L2486). Plus every other key the rejection notification template references (binary effect — entire notification was dropped pre-fix). Previous code-sync (2026-05-25): D7 NotificationMacroResolver shipped end-to-end implementing §8. New resolver tags 6 `:NotificationMacro` classes (5 Pattern A interface impls + 1 Pattern B `SingleNotifyMacro`) + 6 `:MacroInit` methods (5 × `init(java.util.Hashtable)` + 1 × 8-arg constructor). Pattern A walks `:IMPLEMENTS` + `:EXTENDS` chain with simple-name phantom-FQN fallback to catch SymbolSolver failures that resolve cross-package parents to phantom FQNs (verified on `SendNotificationTaskMacro` → `java.util.AutomationNotificationMacro` broken case). `SliceExecutor.runAffectedMacros` aggregator added; `report.ftl` gains "Notification Macros Affected" section. For the ADMP `1660081c13` patch: literal control-flow forward-reach returns `macros=0` (the patch writes audit rows that macros LATER read in a separate request — exactly the data-flow gap documented in §8h; §8e's predicted 4-macro reach depends on the read-set/write-set intersection still listed as out-of-scope). Same-day earlier edits to §8: added §8c (Layer-A vs Layer-B macro-key granularity) and §8d (cross-class macro × key matrix). Documents the 6-class `NotificationMacro` family + structural `SingleNotifyMacro`, distinguishes them from non-notification `*Macro` classes (CustomActionMacro, WFAssigneeMacro family, O365AutoReplyMacro, orchestration `Macro`), identifies the 4 reached macros (WFNotificationMacro, AutomationNotificationMacro, SendNotificationTaskMacro, SingleNotifyMacro) vs. the 2 not reached (ScheduleReportNotificationMacro, standalone MgmtNotificationMacro), and — crucially — names the **specific template-variable keys** affected (Layer-B: per-user iteration over `auditObjectIdList` → username, OBJECT_GUID, all LDAP attribs, Manager, password, ouName, status, folder_list, memberOf_dn, recipient lists, HTML table body) vs. unaffected (Layer-A: request/automation metadata — requestid, requestor, createtime, subject, description, workstatus, status, modifiername, comments, reviewer/approver/executor, expire.time, executelink, actiontile, actioname, automation_name_macro, action_time, domainName). Conclusion in one line: every Layer-A key is unaffected; every Layer-B key is affected, because the patch acts on `auditObjectIdList` (the per-object iteration domain), not on request-metadata columns. Boundary destination is the concrete `init(Hashtable)` override (or constructor for SingleNotifyMacro), labelled `:MacroInit` on the method and `:NotificationMacro` on the owning class; edges stamped with `macro_kind = concrete simple name` for per-flow precision. Previous code-sync: 2026-05-24 — task #98 complete: ConstantIndex post-ingest cleanup. FieldNode gains `constantValue` (literal value from `static final` declarations); Neo4jWriter persists as `f.constant_value`; `runConstantIndexCleanup()` runs at end of ingest reverse-mapping numeric `:Permission` / `:AuditCategory` ids to symbolic names by joining against `:Field` constants in domain-preferred `*ActionConstants` / `*PermissionConstants` / `*AuditConstants` classes. Second-pass fallback to any `*Constants` class when no domain-preferred match exists. Conservative single-candidate disambiguation. Previous: task #113 — RequestParamResolver chained fluent JSON-bag access. All D1–D6 + #98 + #108 + #110 + #112 + #113 + #114 ship end-to-end on the ADMP `758cdf37d5` patch with zero regression.
Previous code-sync (2026-05-23): NotificationAuditResolver Pattern B (workflow/ACC) added; RequestParamResolver negation guard added.

---

## Quick reference

| Domain | Edge type | Destination(s) — full FQN.method |
|---|---|---|
| **REST/Servlet URL exposure** | `:EXPOSES` (Class→RestEndpoint) with `target_method_simple_name` property | URLs with explicit `apimethod` attr in `WEB-INF/security/security*.xml` (SecurityXmlResolver — only `security-api-v2.xml` qualifies), `product_package/conf/adsf/ADSProductAPIs.xml` (RestApiXmlResolver), or HttpServlet subclass FQNs (ServletResolver) — see §7. Security XMLs without `apimethod` emit `:RestEndpoint` nodes only (no EXPOSES edges). |
| **URL parameter values** | `:READS_PARAM` (Method→RequestParam) with `value` + `block_start_line` + `block_end_line` | `request.getParameter("X")` reads, `.equals("Y")` / `case "Y"` branch values, JSONObject-bag `.getString("field")` accessors — see §7c (RequestParamResolver) |
| Notification dispatch (management + automation) | `:SENDS_NOTIFICATION` | `com.adventnet.sym.adsm.common.server.admin.notification.MgmtNotificationListener.triggerNotification` |
| Notification dispatch (workflow & ACC) | `:SENDS_NOTIFICATION` | `com.adventnet.sym.adsm.common.server.admin.notification.NotificationTrigger` constructor + `.start()` |
| Notification macro data-population | `:POPULATES_MACRO` (Method→NotificationMacro) + `:NotificationMacro` label on class + `:MacroInit` label on method | concrete `init(Hashtable)` (or constructor) on every class implementing `com.adventnet.sym.adsm.common.server.admin.notification.NotificationMacro` (also `SingleNotifyMacro` constructor, which is structurally a macro but doesn't implement the interface) — see §8 |
| System schedule **registration** | `:SCHEDULES` | `com.adventnet.sym.adsm.common.server.util.SchedulerHandler.createScheduler` |
| Scheduled-task **execution** entry — `taskengine` framework | `:ScheduledEntryPoint` label on a `:Method` + `:TaskEngineTask` on the owning `:Class` | every `executeTask(TaskContext)` on a class implementing `com.adventnet.taskengine.Task` (see §2b for 22 verified concrete classes; covers 4 of 5 user-defined kinds in §2c) |
| Scheduled-task **execution** entry — `delayedtask` framework | `:ScheduledEntryPoint` label on a `:Method` + `:DelayedTask` on the owning `:Class` | every `executeTask(ArrayList, Hashtable, DataObject)` on a class extending `com.adventnet.sym.adsm.common.server.delayedtask.AbstractDelayedTask` (see §2c kind #5 — covers Exchange/M365 deferred actions during user creation) |
| User-created schedule | `:USER_SCHEDULES` | `com.adventnet.sym.adsm.common.webclient.util.SchedulerInputsUtil.addSchedulerDetails` (also: `SchedulerHandler.createScheduler`) |
| Permission / auth check | `:REQUIRES_PERMISSION` | `com.adventnet.sym.adsm.common.server.helpdesk.ADMPAuthObject.getActionList` (call site: `auth.getActionList().contains(<constant>)`) |
| Admin audit write | `:WRITES_AUDIT` | `com.adventnet.sym.adsm.common.server.audit.AdminAuditUtil.saveAuditDetails` |
| Orchestration execution | `:TRIGGERS_ORCHESTRATION` | `com.adventnet.sym.adsm.common.server.automation.orchestration.OrchestrationTrigger` constructor + `.start()` |

---

## 1. Notification dispatch — **two distinct destinations**

ADSM does **NOT** have a single universal notification dispatcher. The product has three user-facing notification flows, and they split across TWO dispatch paths:

| Flow | User-visible trigger | Dispatch path |
|---|---|---|
| **Management Notification** | Notification profile criteria matches a management action; template selected on the profile | `MgmtNotificationListener.triggerNotification` |
| **Automation Notification** | Automation policy completes; template chosen on the policy | `MgmtNotificationListener.triggerNotification` (when `AutomationUtil.isNotifyEnabledAutomation()` is true) |
| **Workflow & ACC Notification** | Assigning-rule criteria match on a workflow/ACC request | `NotificationTrigger` constructor + `.start()` — bypasses `MgmtNotificationListener` entirely |

### 1a. `MgmtNotificationListener.triggerNotification` — covers flows 1 & 3

**FQN:** `com.adventnet.sym.adsm.common.server.admin.notification.MgmtNotificationListener`
**Method:** `triggerNotification(DataObject auditDataObj, ArrayList<Long> auditObjectIdList, ...)` — multiple overloads, all lines 41-141.

**What it does (lines 177-194 and 208-242):**
```java
// Management path: profile criteria matched
if (NotificationProfileHandler.getProfileList(...).hasMatches()) {
    NotificationTrigger trigger = new NotificationTrigger(...);
    trigger.setNotificationTemplates(associatedTemplates);
    trigger.start();                                // line 242
}

// Automation path: policy has templates enabled
if (AutomationUtil.isNotifyEnabledAutomation(automationId)) {
    AutomationNotificationMacro macro = new AutomationNotificationMacro();
    macro.init(auditDO);
    NotificationTrigger trigger = new NotificationTrigger(...);
    trigger.start();                                // line 194
}
```

**Why this method (not its callers / not its callees):**
- The `NotificationTrigger.start()` line below it is the actual dispatch, but every management/automation caller routes through `MgmtNotificationListener` first to apply profile-criteria filtering and template selection — making *this* the right semantic edge destination.
- `NotificationProfileHandler` is queried by this method, not vice versa — so it's a callee, not the entry point.
- `AutomationNotificationMacro.init` is data-population, same as `WFNotificationMacro.init` — not dispatch.

**Verified callers (unrelated subsystems):** user-management completion, group-management completion, GPO operations, automation `ScheduledAutomationTask`, attribute-listener chains.

### 1b. `NotificationTrigger` (constructor + `.start()`) — covers flow 2

**FQN:** `com.adventnet.sym.adsm.common.server.admin.notification.NotificationTrigger`
**Pattern:** `ObjectCreationExpr` → `setNotificationTemplates(...)` → `.start()` chained on the same local variable.

**Workflow/ACC bypass evidence (`WorkFlowAction.java:4614-4636`):**
```java
for (Long notificationTemplateId : assignedNotificationTemplateIds) {
    JSONObject jsonObject = NotificationTemplateHandler.getTemplateDetails(notificationTemplateId, rb);  // L4614
    NotificationTemplate notificationTemplate = new NotificationTemplate(notificationTemplateId, jsonObject);
    associatedTemplates.add(notificationTemplate);
}
// ... build WFNotificationMacro inline (L4619)
NotificationTrigger trigger = new NotificationTrigger(actionIdList, domainName, rb, loginId, macro, ...);  // L4634
trigger.setNotificationTemplates(associatedTemplates);
trigger.start();                                    // L4636 — direct dispatch, NO MgmtNotificationListener
```

**Why this is a separate edge (not subsumed by `MgmtNotificationListener`):**
- The workflow / ACC code path picks templates via the **assigning-rule engine**, not the notification-profile engine. There's no `NotificationProfileHandler.getProfileList(...)` filter in this path.
- A patch that touches the workflow notification logic would NOT surface through a `MgmtNotificationListener`-only resolver — that's exactly the case in `WorkFlowAction.approveRequest` (the `cancel` branch around L2480-2500 builds a `WFNotificationMacro` for assigning-rule-based dispatch).
- Patches to the automation/management path conversely will NOT surface through a `NotificationTrigger`-only resolver because those callers go through the listener first.

**Also dispatches via this path:**
- `WFRuleExecutor` (assigning-rule firing on workflow transitions)
- ACC commit handlers (`ChannelMembershipCommitAction`, `MailboxCommitAction`, etc.)
- Workflow status-escalation handlers

### Resolver implication

The `NotificationResolver` must recognize **both** call patterns:

```
Pattern A — :SENDS_NOTIFICATION (Management / Automation flow)
  MethodCallExpr where the resolved method = MgmtNotificationListener.triggerNotification(*)

Pattern B — :SENDS_NOTIFICATION (Workflow / ACC flow)
  ObjectCreationExpr of NotificationTrigger
  AND on the same local var: a chained ".start()" call later in the method body
```

A single resolver scanning both patterns emits the same edge type (`:SENDS_NOTIFICATION`) so report queries don't need to know which flow. The edge's id is derived from:
- For Pattern A: the `categoryId` / `actionId` constant if it resolves to a `static final` on a `*Constants` class; otherwise empty.
- For Pattern B: the `assignedNotificationTemplateIds` source if it's a `static final`; otherwise empty (the templates come from runtime DB lookups in most workflow paths).

### Out-of-scope / honest gaps

- **`SendNotificationTaskMacro`** — used for async automation deferral. Not yet verified whether it ever fires WITHOUT going through `MgmtNotificationListener` first. If a future patch report shows missing automation-side notifications, revisit this class.
- **Direct `MailUtil.sendMail(...)` calls** — some legacy paths skip the macro/trigger system entirely and post directly to the mail queue. These are not "notifications" in the SPMP notification-profile sense, so we don't model them as `:SENDS_NOTIFICATION`. Consider a separate `:SENDS_EMAIL` edge if a patch lands that touches mail-queue-direct code.

---

## 2. Schedule registration (system-level)

**FQN:** `com.adventnet.sym.adsm.common.server.util.SchedulerHandler`
**Method:** `createScheduler(String scheduleName, String scheduleType, int hours, int minutes, int day, int date, String taskName, Hashtable customProp)` — lines 190-287.

**What it does (lines 259-279):**
```java
schDo = CommonUtil.getPersistence().add(schDo);          // persist SCHEDULE table row
updateTaskInputTable(ScheduleName, taskName, schDo);     // wire to TaskEngine PERIODIC/CALENDAR config
```

**Why this method, not the engine that runs the tasks (`wengine/Scheduler`):**
- The wengine's `Scheduler` is the runtime dispatcher of fired schedules — it consumes the `SCHEDULE` table that this method writes to. Wrong direction for our edge: we want "what code creates schedules", not "what code reads them".
- `ScheduledTask` is a data holder (extends `AbstractDelayedTask`).
- `DelayedTaskManager` / `TaskExecutor` operate on `java.util.Timer` for one-shot delayed work — different concern, not persistent scheduling.

**Verified callers:** AD-sync bootstrap (`ADSyncSchedulerBootstrap`), DB-backup scheduler init, audit-archive scheduler init, the user-created path (see §3).

---

## 2b. Scheduled-task **execution** entry points (predefined system schedules)

This is the **other half** of scheduling: when a registered schedule fires, which method runs?

**Contract:** the external `com.adventnet.taskengine` library invokes `executeTask(TaskContext)` polymorphically on the concrete Task subclass bound to each schedule. The binding is held in DB tables — not in Java code — via `TASKENGINE_TASK (TASK_ID, TASK_NAME, CLASS_NAME)` joined to `SCHEDULED_TASK (SCHEDULE_ID, TASK_ID)`.

**Destination pattern:** concrete `:Method` nodes whose:
- `simple_name = "executeTask"`
- `owner_fqn` is a class implementing `com.adventnet.taskengine.Task` (directly or transitively)
- One parameter typed `com.adventnet.taskengine.TaskContext`

**Verified concrete task classes — 15 in `wengine/` alone** (not exhaustive — the registry is DB-driven, so any future Task implementation anywhere in the source tree also qualifies):

| FQN | Tables written | Semantic purpose | Bound schedule(s) — notable |
|---|---|---|---|
| `…wengine.ADUpdateTask` | `USER_GENERAL_DETAILS`, `COMPUTER_GENERAL_DETAILS`, `GROUP_GENERAL_DETAILS_TABLE` … | AD sync — `DomainHandler.scheduleUpdate(domain)` per configured domain | Daily + every-10-minute (same class, different cadence) |
| `…wengine.AuditArchiveTask` | `ADSMAuditDetails`, `ADSMAuditObjs`, `ADSMAuditAttribs`, WF audit tables (40+), archive tables | Audit archival | Audit archive schedule |
| `…wengine.DailyReportTask` | `ADSMDashboardReportCache`, `ADSMSignificantReportsCache` | Branches on `SCHEDULE_NAME`: dashboard refresh (`runDailyReport`) **or** significant reports (`runSignificantReports`) | **Daily dashboard refresh** + significant-reports |
| `…wengine.CleanUpTask` | `ADSMScheduleHistory`, `ADSMAdvScheduleHistory`, `ADSMNotificationHistory`, `FcBulkUser*` (60+ tables) | Weekly cleanup — sequential `CleanUpUtil.*` calls | Weekly cleanup |
| `…wengine.DBBackUpTask` | (none directly — invokes external backup process) | DB backup — calls `DBUtil.executeDBBackupOperation` | DB backup |
| `…wengine.WeeklyReportSyncTask` | `USER_GENERAL_DETAILS`, `COMPUTER_GENERAL_DETAILS`, GApps cache tables | Weekly full sync + PSO update + GApps deleted-user sync | Weekly full sync |
| `…wengine.SelectedReportTask` | Report output tables (varies per selected report) | User-scheduled custom reports — `ReportHandler.runScheduledReports` | User-scheduled reports |
| `…wengine.HDTAuditReportTask` | `ADSMAuditReportCache` | HDT audit report — `ReportHandler.runAuditReports` | HDT audit report |
| `…wengine.DynamicGroupsPeriodicTask` | (none directly — publishes attribute-update event) | Dynamic-group refresh — `AttributeUpdatePublisher.notifyObservers()` | Dynamic-group periodic |
| `…wengine.DynamicGroupsUpdateTask` | Dynamic-group membership tables | Dynamic-group periodic re-evaluation | Dynamic-group update |
| `…wengine.OrchestrationScheduledTask` | `ADSIMExecution`, `ADSIMExecutionAudit`, `ADSMOrchestrationExecutionAudit` | Time-delayed orchestration template resume | Orchestration scheduled |
| `…wengine.DiskSpaceAnalysisTask` | Disk-monitoring tables | Disk-space monitoring & alerts | Disk-space monitor |
| `…wengine.LicenseExpiryTask` | License-related tables | License-expiry warning trigger | License-expiry check |
| `…wengine.MailboxExportTask` | Mailbox-export status tables | Periodic Exchange mailbox export | Mailbox export |
| `…wengine.PatchUpdaterRestartTask` | (none — invokes restart) | Patch-installer restart coordinator | Patch-updater restart |
| `…wengine.ScheduledACCTask` | ACC campaign tables | Scheduled access-certification campaigns | ACC campaigns |
| `…wengine.ScheduledAutomationTask` | Automation-policy execution tables | Time-triggered automation policy execution | Automation policies |
| `…wengine.SystemInsightsMailTriggerTask` | (sends mail) | System-insights periodic mail | System insights |
| `…wengine.TimeBasedAutomationTask` | Automation tables | Time-window-gated automation triggers | Time-based automation |
| `…wengine.TimeBasedExecutionTask` | Execution tables | General time-window task executor | Time-based execution |
| `…wengine.WFSLATask` | `WFRequestSLA` (reads) + status tables (writes) | Workflow SLA tick — checks expiry, fires notifications | Workflow SLA |
| `…wengine.WorkFlowTask` | Workflow tables | Workflow background processing | Workflow tick |

**Dashboard refresh confirmation:** `DailyReportTask.executeTask` (lines 36–60) branches on `SCHEDULE_NAME`. The else-branch — `DashboardReportUtil.runDailyReport()` — is the dashboard data refresh. So a patch reaching any method on the `runDailyReport` forward path surfaces `DailyReportTask` as affected, and QA knows to verify the dashboard panels after merging.

**Important:** the table above is illustrative, NOT a hard-coded list. The resolver detects Task implementations structurally, so any new task class added to ADSM (in any package, not just `wengine/`) is automatically captured. Worth re-scanning periodically to spot any new ones in `audit/`, `adsync/`, `o365/`, `reports/`, etc.

**Why the destination is the concrete `executeTask` override (NOT the `Task` interface's method):**
- Polymorphic dispatch: the taskengine framework `CLASS.forName(CLASS_NAME).newInstance().executeTask(ctx)` — it always lands on the subclass override at runtime.
- Pointing edges at `Task.executeTask` (interface) would make every patch to any table touched by any scheduled task falsely "reach" every other scheduled task — uniform inheritance collapses all distinction.
- Each concrete `executeTask` has a distinct DB-write fingerprint (see table above). The edge points at the precise dispatcher whose body we want to test.

**Resolver implementation (`ScheduledTaskResolver`):**
```
For each ClassOrInterfaceDeclaration in the AST:
  Check if it implements com.adventnet.taskengine.Task (directly or via :IMPLEMENTS chain).
  If yes, find its MethodDeclaration where:
    name == "executeTask"
    AND single parameter type ends with "TaskContext"
  Tag that method with extra label :ScheduledEntryPoint.
  Tag the owning class with extra label :TaskEngineTask (distinct from existing :Scheduler).
```

The :ScheduledEntryPoint label on the method node means impact analysis can:
- **Backward reach**: from any patched method, walk `:CALLS|:OVERRIDES*` backward and any hit on a `:ScheduledEntryPoint` method = an affected scheduled task.
- **Surface the schedule-name**: the schedule-name (e.g. "AD Update Schedule", "Daily Midnight Report") lives in the DB-side `SCHEDULE.SCHEDULE_NAME` column, which the impact tool doesn't (and can't) read statically — surface the **task-class simple name** instead and let QA map it.

**Honest gaps:**
- **Schedule-name → Task-class mapping is DB-resident.** Without parsing `TASKENGINE_TASK` rows (which live in the running product DB), we can only surface task-class FQNs, not the user-friendly schedule names. The user mentioned "daily midnight schedule" / "10-minute schedule" — the static analyzer surfaces the *implementation* (`ADUpdateTask`, `DailyReportTask`) and QA cross-references to the schedule-name via the admin UI.
- **The dispatch site (`task.executeTask(ctx)`) is inside the external `com.adventnet.taskengine` JAR.** No source visible. We infer the contract from the interface signature and the consistent shape of all 10 concrete subclasses.
- **`SchedulerUtil.java:70-115`** holds i18n resource keys like `admp.reports.sched_reports.sch_view.scheduler_name.daily_report_scheduler` — these are display-name lookups but the linkage to task-class is still DB-resident.
- **Every-10-minute schedule** likely reuses `ADUpdateTask` with a PERIODIC trigger (10-min cadence in `TASK_INPUT`), but the DB binding wasn't directly observable in source.

---

## 2c. User-defined schedule kinds — execution mapping

User-defined schedules in ADMP come in **five distinct product kinds**. Four reuse the §2b `executeTask(TaskContext)` framework; one uses a **separate `AbstractDelayedTask` framework** with its own dispatch contract.

| User-defined kind | Execution entry point (FQN.method) | Framework | What it does |
|---|---|---|---|
| 1. Scheduled reports (standard / advanced / custom, in Reports tab AND M365 tab) | `wengine.SelectedReportTask.executeTask(TaskContext)` | `taskengine.Task` — same as §2b | Calls `ReportHandler.runScheduledReports(scheduleId, ...)` (line 37) |
| 2. Audit report schedule | `wengine.HDTAuditReportTask.executeTask(TaskContext)` | `taskengine.Task` — same as §2b | Calls `ReportHandler.runAuditReports(...)` (line 73) |
| 3. ACC (Access Certification) schedule | `wengine.ScheduledACCTask.executeTask(TaskContext)` | `taskengine.Task` — same as §2b | Creates workflow request per campaign via `wfRequest.createRequest(...)` (lines 54-89) |
| 4. Automation (user-defined) | THREE cooperating classes (see below) | `taskengine.Task` — same as §2b | Time-gated automation execution |
| 5. **Delayed management action** (Exchange/M365 during user creation) | `delayedtask.AbstractDelayedTask` subclass `.executeTask(ArrayList, Hashtable, DataObject)` invoked from `.run()` | **DIFFERENT** — `Runnable`-based, polled from queue | Deferred per-object management actions |

### Kind #4 — automation: three cooperating Task classes, not duplicates

The three Task implementations under `wengine/` that the agent ambiguity-flagged are NOT alternatives — they form a pipeline:

| Class | Role | Body evidence |
|---|---|---|
| `ScheduledAutomationTask` | The **entry** — fires when the automation's cron-time hits. Creates a workflow request via `ScheduledRequest` → `wfRequest.createRequest(...)`. May either execute instant tasks immediately or schedule time-based follow-ups. | Line 92, ScheduledAutomationTask.java |
| `TimeBasedAutomationTask` | The **time-window executor** — handles tasks that the automation declared as "execute within a future window". Calls `wfViewRequest.executeWFTaskSet(...)`. | Lines 52-135, TimeBasedAutomationTask.java |
| `TimeBasedExecutionTask` | The **fallback / custom-view executor** — similar pattern, used for non-automation time-window contexts (e.g., custom-view-driven scheduled execution). | Lines 31-72, TimeBasedExecutionTask.java |

**For impact-analysis purposes**: all three carry `:ScheduledEntryPoint` (because all three implement `Task` + have `executeTask(TaskContext)`). A patch reaching any of them surfaces as "automation execution affected." Refining further (which automation kind) would need DB introspection — out of scope for static analysis.

### Kind #5 — delayed management actions: separate framework

This is what I'd missed. Exchange / M365 deferred actions during user creation do **NOT** use `com.adventnet.taskengine.Task`. They use a custom queue-poller framework:

**Framework:**
```
com.adventnet.sym.adsm.common.server.delayedtask.DelayedTask       (interface, extends Runnable)
   ↑
com.adventnet.sym.adsm.common.server.delayedtask.AbstractDelayedTask (abstract)
   ↑
concrete subclasses (see below)
```

**Dispatch contract:**
```java
// AbstractDelayedTask (the base):
public abstract class AbstractDelayedTask implements DelayedTask {
    public void run() {                                       // ← Runnable.run, the actual dispatch
        // queue / dependency / delay checks
        executeTask(auditPropList, audit_info, auditDataObj); // ← abstract, subclass-overridden
    }
    protected abstract void executeTask(
        ArrayList auditPropList, Hashtable audit_info, DataObject auditDataObj
    );
}

// Concrete subclass (e.g. O365BackgroundTask):
@Override
protected void executeTask(ArrayList auditPropList, Hashtable audit_info, DataObject auditDataObj) {
    // perform the deferred M365 operation
}
```

**Queue + poller:**
- Pending tasks live in `DELAYED_TASK_QUEUE` (DB table — durable across restarts).
- `BGTaskUtil.getExecutorInstance()` returns a `ThreadPoolExecutor` (5 core threads) that consumes the queue.
- Each task evaluates `isConditionSatisfied()` (custom delay/dependency check) before its `executeTask` body runs.

**Verified concrete subclasses (representative sample, not exhaustive):**

| FQN | Purpose |
|---|---|
| `…delayedtask.ExchOnlineTask` | Exchange Online mailbox provision after user creation |
| `…delayedtask.O365BackgroundTask` | Generic M365 background dispatch |
| `…delayedtask.O365UserGeneralAttributeTask` | M365 user attribute updates |
| `…delayedtask.MSTeamsTask` | MS Teams operations (channel mgmt, etc.) |
| `…delayedtask.ExchOnlineRoomMbxTask` | Exchange Online room-mailbox creation |
| `…delayedtask.MemberOfTask` | Group membership-update deferral |
| `…delayedtask.BumMemberOfTask` | Bulk update member-of |
| `…delayedtask.FileServerPermissionMgmtTask` | File-server permission delegation |
| `…delayedtask.MailboxExportTask` | Mailbox export (distinct from `wengine.MailboxExportTask` despite the name) |

**Resolver implication — TWO patterns, both emit `:ScheduledEntryPoint`:**

```
Pattern A — taskengine.Task entry (covers §2b + kinds 1-4 in §2c)
  Class implements com.adventnet.taskengine.Task (directly or transitively).
  Find MethodDeclaration where name=="executeTask" AND single param ends with "TaskContext".
  Tag method :ScheduledEntryPoint, owning class :TaskEngineTask.

Pattern B — AbstractDelayedTask entry (covers §2c kind #5)
  Class extends com.adventnet.sym.adsm.common.server.delayedtask.AbstractDelayedTask
  (directly or via :EXTENDS chain).
  Find MethodDeclaration where name=="executeTask" AND params == (ArrayList, Hashtable, DataObject).
  Tag method :ScheduledEntryPoint, owning class :DelayedTask.
```

Both patterns produce the same `:ScheduledEntryPoint` label so backward-reach queries from a patched method are framework-agnostic. The `:TaskEngineTask` vs `:DelayedTask` class label lets the report distinguish them when displaying the affected-schedule row (e.g., "scheduled report" vs "deferred M365 action").

### Why this matters for impact analysis (the question you actually asked)

User-created schedules are affected by a patch **when their `executeTask` method (or anything it transitively calls) is in the forward reach of the patched code**. The destination of the `:SCHEDULES_VIA` semantic edge is therefore the concrete `executeTask` override on the Task / DelayedTask subclass — exactly the method whose body the static analyzer can read. The graph encoding is:

```cypher
// Given a patched method, list every affected user-scheduled execution entry
MATCH (patched:Method {fqn: $patchedFqn})
MATCH (sched:Method:ScheduledEntryPoint)
WHERE EXISTS{(sched)-[:CALLS|OVERRIDES*1..10]->(patched)}
OPTIONAL MATCH (sched)<-[:CONTAINS]-(c:Class)
RETURN
   c.fqn      AS taskClass,
   sched.fqn  AS entryMethod,
   CASE
     WHEN 'TaskEngineTask' IN labels(c) THEN 'taskengine.Task'
     WHEN 'DelayedTask'    IN labels(c) THEN 'AbstractDelayedTask'
     ELSE 'unknown'
   END        AS framework,
   labels(c)  AS classKinds
ORDER BY framework, taskClass
```

The report renderer maps each row to a human-readable schedule-kind:
- `SelectedReportTask` + `TaskEngineTask` → "user-scheduled report"
- `HDTAuditReportTask` + `TaskEngineTask` → "audit report schedule"
- `ScheduledACCTask` + `TaskEngineTask` → "ACC schedule"
- `ScheduledAutomationTask` / `TimeBasedAutomationTask` / `TimeBasedExecutionTask` + `TaskEngineTask` → "automation execution"
- `ExchOnlineTask` / `O365*Task` / `MSTeamsTask` + `DelayedTask` → "delayed management action (Exchange/M365)"
- Any other `*Task` + `TaskEngineTask` → predefined system schedule (see §2b)

### Honest gap — user-creation → enqueue trigger not statically traced

The user said: *"during user creation, if exchange-related M365 fields values are set, delayed management action will be scheduled."* The infrastructure for enqueueing is clear (`BGTaskUtil.addToQueue(DelayedTask)`, `PersistenceBlockingQueue`), but the precise call site in the user-creation flow where Exchange-field detection triggers the enqueue is **not visible in pure-Java source**. It's likely either:
- In a webclient action under `webclient/usermanagement/` (worth a targeted search if you want to model the `:ENQUEUES_DELAYED_TASK` edge);
- Or in a C# module (`source/c_sharp/src/o365management.cs` etc.) that crosses the JNI boundary and enqueues from the .NET side — which the Java analyzer cannot see.

For impact analysis, this gap matters only for the **forward** direction (a patch in user-creation code → which delayed tasks get enqueued). The **backward** direction (a patch in `ExchOnlineTask.executeTask` → user-creation flows that trigger it) still works via OVERRIDES/CALLS resolution, since the call into the queue (`addToQueue(new ExchOnlineTask(...))`) IS captured as a regular `:CALLS` edge.

---

## 3. User-created scheduled job

This is a **two-edge pattern** because the user-creation path is structurally distinct from the system-bootstrap path.

**Web entry:** `com.adventnet.sym.adsm.common.webclient.reports.SchedulerAction.addScheduler(...)` — Struts action lines 1082-1098.

**Two dispatched calls in sequence:**
1. `SchedulerHandler.createScheduler(...)` — same as §2, persists the schedule record.
2. `SchedulerInputsUtil.addSchedulerDetails(...)` — writes user-input config (report id, AD-sync filter, etc.) into the `ADSMSchedulerInputDetails` DB table.

**Why model separately from §2:**
- Different blast radius: changing `createScheduler` affects BOTH system and user schedules; changing `SchedulerInputsUtil` affects only user-created ones.
- Different DB coupling: `ADSMSchedulerInputDetails` is user-input only.
- Different QA reproduction: user-created schedules are reachable via the admin UI; system schedules require a product restart.

**Resolver implication:**
- A method calling `SchedulerHandler.createScheduler` → `:SCHEDULES` (covers both #2 and #3).
- A method calling `SchedulerInputsUtil.addSchedulerDetails` → `:USER_SCHEDULES` (uniquely #3).

✅ **Shipped 2026-05-24 in `OrchestrationCallResolver`.** Detection: scope-text equals `SchedulerInputsUtil` / `schedulerInputsUtil` / ends with `.SchedulerInputsUtil`. Method name `addSchedulerDetails`. The 1st argument (`scheduleId`) is extracted via the same priority hierarchy as D3/D4/D5; however, the verified signature is `addSchedulerDetails(Long scheduleId, Long loginId, HttpServletRequest request, boolean isUpdate, String time)` (SchedulerInputsUtil.java:515) — `scheduleId` is almost always a runtime `Long` variable, so the resolver falls back to `<unspecified>` as the edge id. The structural fact (this method writes to `ADSMSchedulerInputDetails`) is still captured. The destination uses the existing `:ScheduledTask` node (same `task_class_fqn` key as D2 `:SCHEDULES`) — the edge type `:USER_SCHEDULES` distinguishes user-configured schedules from system registrations, and a single schedule can carry both edge types if both registration and user-input config happen for the same task. Verified on ADMP: 3 in-memory emissions → 2 persistent edges (Neo4j MERGE collapses duplicate caller emissions). Concrete pairs: `(SchedulerAction.saveReport → <unspecified>)`, `(AdvancedSchedulerAction.save → <unspecified>)` — exactly the two Struts entry points for user-created report scheduling in ADMP (standard + advanced/custom).

---

## 4. User-delegation / auth check

**FQN:** `com.adventnet.sym.adsm.common.server.helpdesk.ADMPAuthObject`
**Methods:** `getActionList()` and `getActionList(String domainName)` — lines 92-150.

**The actual call-site pattern other code uses:**
```java
ADMPAuthObject auth = (ADMPAuthObject) session.getAttribute(...);
if (!auth.getActionList().contains(ActionConstants.WORKFLOW_REJECT)) {
    // permission denied
}
```

**Why this method:**
- `actionList` (HashSet<Long>) is populated at login (lines 76-81) by joining `HelpDeskRole` → permitted actions.
- The `.contains(<constant>)` check IS the enforcement point — every action-gated flow does this.
- Sibling class: `RestAPIAuthObject` for REST-API authorization. Same pattern, different session origin.

**Why not the alternatives:**
- `DelegationUtil` — constants + helpers, not the check itself.
- `HelpDeskRoleHandler` — manages role definitions (CRUD); the check reads the result via the auth object.

**Resolver implication:** the edge's destination `:Permission` node is keyed on the constant simple-name (e.g. `WORKFLOW_REJECT`), not the method. Resolver should detect `<authObject>.getActionList().contains(<const>)` and stamp the `FieldAccessExpr`'s name.

✅ **Shipped 2026-05-24 in `SecurityResolver` Pattern C.** Detection: find a `MethodCallExpr` named `contains` whose scope is itself a `MethodCallExpr` named `getActionList` (both no-arg and 1-arg domain-name overloads accepted). The `contains` argument is extracted in priority order: `FieldAccessExpr.getNameAsString()` → `NameExpr.getNameAsString()` → `StringLiteralExpr.getValue()` → `LongLiteralExpr.getValue()` / `IntegerLiteralExpr.getValue()`. Verified on the ADMP repo: 28 ADMP-specific `:REQUIRES_PERMISSION` edges added on top of the existing 20 from Pattern A (annotations) + Pattern B (AccessChecker scopes). Sample concrete pairs: `(loadLayout → CUSTOMIZATION_ACTION_ID)`, `(loadDomains → CUSTOM_REPORT_MGMT_ACTION_ID)`, `(loadUserDetails → DASHBOARD_ACTION_ID)`, `(isO365AccDelegated → OFFICE365_TAB_ACTION_ID)`, `(isRmpTabDelegated → RMP_TAB_ACTION_ID)`. Numeric-literal IDs (e.g. `1914L`) also surface as edges keyed on the numeric text — less informative but better than dropping the edge. Per-call-site de-dup via `(fromFqn, permId)` Set so a method that calls the chain twice emits one edge. The new branch is in `SecurityResolver.visit()` and runs BEFORE the legacy Pattern B keyword-scope check; on a match it `continue`s past the Pattern B branch to avoid double-counting.

---

## 5. Admin audit write

**FQN:** `com.adventnet.sym.adsm.common.server.audit.AdminAuditUtil`
**Method:** `saveAuditDetails(String technicianName, Long categoryId, String objectName, Integer actionName, Long technicianLoginId, Long objectId, HashMap auditDetails)` — lines 60-99.

**What it does (lines 63-99):**
```java
Row row = new Row(ADMIN_AUDIT_DETAILS_TABLE);
row.set("AUDIT_TIME", System.currentTimeMillis());
row.set("TECHNICIAN_NAME", technicianName);
row.set("CATEGORY_ID", categoryId);
// ... additional fields
CommonUtil.getPersistence().add(dataObj);                  // direct INSERT into ADSMAdminAuditDetails
```

**Why this method, not `AdminAuditHandler`:**
- `AdminAuditHandler` is a *listener* that responds to attribute-update events and then calls `AdminAuditUtil.saveAuditDetails`. Wrong layer.
- `NotificationHistoryAuditUtil` is a separate concern (notification-delivery history, not admin audit).
- Scheduled audit tasks (in the `audit/` package) are *consumers* of the audit log, not writers.

**Resolver implication:** the destination `:AuditCategory` node is keyed on the `categoryId` argument's symbolic name (e.g. `AuditCategoryConstants.WORKFLOW_OPERATIONS`). If `categoryId` is a literal Long, fall back to the numeric value.

✅ **Shipped 2026-05-24 in `NotificationAuditResolver` N3 Pattern A.** Detection: scope-text equals `AdminAuditUtil` (static), `adminAuditUtil` (camelCase instance), or ends with `.AdminAuditUtil` (qualified). Method name `saveAuditDetails`. The 2nd argument (`categoryId`) is extracted in priority order: `FieldAccessExpr` → `NameExpr` → `StringLiteralExpr` → `LongLiteralExpr` / `IntegerLiteralExpr`. The new branch runs BEFORE the legacy Pattern B (keyword-scoped `*Audit*.log/add/record` matchers) and `continue`s past it on a match. Verified on ADMP: 80 ADMP-specific `:WRITES_AUDIT` edges added on top of the 66 from Pattern B. Sample concrete pairs: `(fillADDomainAuditDetails → ACTIVE_DIRECTORY_DOMAIN_CATEGORY_ID)`, `(fillAutomationAuditDetails → AUTOMATION_CATEGORY_ID)`, `(deleteCampaignScheduler → CAMPAIGN_CATEGORY_ID)`, `(fillCustomReportDetails → CUSTOM_REPORT_CATEGORY_ID)`, `(updateAutomationStatus → AUTOMATION_CATEGORY_ID)`. Note: the `WorkFlowAction.approveRequest` patch's call chain does NOT reach `saveAuditDetails` even at depth 10 — workflow audit is event-driven via the `AdminAuditHandler` *listener*, which the static call graph can't trace. This is the documented "wrong layer" pattern; affected reports for management/automation/ACC patches will surface audit categories correctly.

---

## 6. Orchestration execution

**FQN:** `com.adventnet.sym.adsm.common.server.automation.orchestration.OrchestrationTrigger` (extends `Thread`)
**Pattern:** Constructor + `setActionId(Long)` + `.start()` chained on the same local variable.

**What it does (lines 45-75):** parses orchestration profile/template list, walks `IMDataTemplate` entries, dispatches each `IMDBlock` execution.

**Why this class, not `OrchestrationHandler`:**
- `OrchestrationHandler` is CRUD on orchestration profiles/templates (load, save, list).
- `OrchestrationTrigger` is the execution dispatcher invoked when automation/workflow fires an orchestration block.
- `IMDBlock` / `IMDataTemplate` are atomic execution primitives invoked by the Trigger's `run()` — too low-level for the edge destination.

**Verified callers:** `ScheduledAutomationTask`, workflow rule executors, manual orchestration trigger from the admin UI.

✅ **Shipped 2026-05-24 in `OrchestrationCallResolver`.** Detection: find an `ObjectCreationExpr` whose type's simple name is `OrchestrationTrigger`, then verify a reachable `.start()` invocation — either inline-chained (`new OrchestrationTrigger(...).setActionId(X).start()`) or via local-var binding (`OrchestrationTrigger t = new ...; t.start();`). The actionId is extracted from the `.setActionId(<const>)` call in the same chain or local scope, using the same priority hierarchy as D3/D4: `FieldAccessExpr` → `NameExpr` → `StringLiteralExpr` → `LongLiteralExpr` / `IntegerLiteralExpr`. When the actionId is a runtime expression, the edge still emits with profile id `<unspecified>` so the trigger is captured as a structural impact even if the report can't name the profile. New graph types: `:OrchestrationProfile` node (key `id`) + `:TRIGGERS_ORCHESTRATION` edge. Verified on ADMP: 2 trigger sites detected — `HDTAuditUtil.initiateOrchestrationTrigger` (HDT audit-driven orchestration) and `MemberOfTask.updateAuditEntries` (delayed-task driven). Both have `<unspecified>` actionId because the setActionId() arg is a runtime variable in both call sites. New branch runs BEFORE the existing O4 `*Handler` catch-all in the same `ObjectCreationExpr` loop and `continue`s on match so OrchestrationTrigger is not also classified as a generic handler instantiation.

**Note:** the SliceExecutor doesn't yet aggregate `:TRIGGERS_ORCHESTRATION` into the report's affected-by-layer / per-domain sections — that's task #108 (Final: report.ftl wiring). The edges and nodes are in the graph and queryable directly via Cypher; the report integration is a follow-up.

---

## 7. URL / REST-endpoint detection — **four-source aggregation**

Unlike the other six domains, URL exposure in ADSM is **NOT** a single Java-method dispatch — there's no `RouteRegistry.register(url, handler)` call to match. URLs live in **XML config files** plus a **naming convention** for servlets. The impact tool reads all four sources and merges them onto the same `:RestEndpoint` node, keyed by URL string.

| Source | What it parses | Resolver |
|---|---|---|
| Security XML (Struts/WAF) | `<url path="...">` elements inside `WEB-INF/security/security*.xml` — only `apimethod`-bearing URLs get EXPOSES edges | `SecurityXmlResolver` |
| REST-API XML (AdventNet) | `<ADSProductAPIs API_URL="..." SERVLET_CLASS_NAME="..." MTCALL_VALUE="...">` rows in `product_package/conf/adsf/ADSProductAPIs.xml` | `RestApiXmlResolver` |
| web.xml (Tomcat descriptor) | `<servlet>` + `<servlet-mapping>` pairs in `resources/tomcat/web_header.xml` + `web_footer.xml` — non-wildcard URL patterns with explicit servlet-class FQN | `WebXmlResolver` |
| Java AST (legacy servlets) | Subclasses of `javax.servlet.http.HttpServlet` (one class = one URL by FQN convention) | `ServletResolver` |

A URL appearing in two sources merges to one `:RestEndpoint {url: "..."}` node via `MERGE` — both sources contribute `:EXPOSES` edges from their respective Java owner classes.

### 7a. SecurityXmlResolver — security-policy URLs

**Source:** every `WEB-INF/security/security*.xml` file under each repo root (auto-discovered; or explicit via `--security-xml-root`).

#### File classification (ADSM)

| File | URLs | Has `apimethod`? | Purpose | EXPOSES edges? |
|------|------|-----------------|---------|----------------|
| **security-api-v2.xml** | 51 | **YES — all 51** | v2 REST API routing + validation | **YES** — explicit FQN mapping |
| security.xml | 1177 | No | URL param validation, throttles, CSRF | NO — validation only |
| security-restapi.xml | 146 | No | URL param validation for `/RestAPI/*` | NO — validation only |
| security-meone-include-filter.xml | 110 | No | Inclusion/filter rules for framework | NO — validation only |
| security-meonefw-override.xml | 25 | No | Framework overrides | NO — validation only |
| security-customer.xml | 0 | — | Empty (customer extensibility) | NO |
| security-mmp-override.xml | 0 | — | Empty (MMP overrides) | NO |

**Rule:** Only `<url>` elements with an explicit `apimethod` attribute produce `:EXPOSES` edges.
All other URLs emit `:RestEndpoint` nodes only (for URL discoverability in Cypher queries) —
**zero `:EXPOSES` edges** because these files define URL parameter validation / throttle policy,
NOT URL→method routing. The heuristic last-segment matching (previously used for URLs without
`apimethod`) is **excluded** — it produced false positives (e.g., URL segment `execute` matching
12 unrelated `execute()` methods across the codebase).

**What gets parsed:**
```xml
<!-- apimethod present → EXPOSES edge created -->
<url path="/api/v2/orchestrations/(\d+)/execute" method="post"
     apimethod="com.adventnet.sym.adsm.common.webclient.api.v2.OrchestrationServiceAPI.executeOrchestration">
    ...
</url>

<!-- apimethod absent → RestEndpoint node only, NO EXPOSES edge -->
<url path="/api/json/workflow/allRequests/approveRequest" method="post" csrf="true">
    <param name="params" type="JSONObject" template="PROCESS_REQUEST" max-len="-1"/>
</url>
```

**Resolution (explicit `apimethod` attribute):**
Format is `com.package.ClassName.methodName`. The resolver splits on the last `.` to get
`classFqn` + `methodName`, then emits an `:EXPOSES` edge with `target_method_simple_name`.

**Emits per URL:**
- `:RestEndpoint {url: "..."}` node (deduped) — **always**, regardless of `apimethod` presence.
- `:EXPOSES` edge — **only when `apimethod` is present** — from the declared class FQN to
  the endpoint, stamped with `target_method_simple_name = methodName`.

### 7b. RestApiXmlResolver — explicit API mappings

**Source:** `product_package/conf/adsf/ADSProductAPIs.xml` (case-insensitive filename match — SPMP uses uppercase trailing S, ADSM uses lowercase).

**What gets parsed:**
```xml
<ADSProductAPIs API_URL="/RestAPI/WC/Workflow" SERVLET_CLASS_NAME="...WorkFlowAction" API_NAME="..." MTCALL_VALUE="approveRequest"/>
```

**Why this resolver coexists with SecurityXmlResolver:** the `<ADSProductAPIs>` rows carry the Java class FQN explicitly (`SERVLET_CLASS_NAME`), so no path-segment heuristic is needed. Multiple rows can share the same `API_URL` with different `MTCALL_VALUE`s — they become distinct endpoints keyed `URL?mtCall` and each `:EXPOSES` edge carries `target_method_simple_name = mtCall`.

**Emits per row:**
- `:RestEndpoint {url: "<API_URL>" or "<API_URL>?<MTCALL_VALUE>"}` node.
- `:EXPOSES` edge from `SERVLET_CLASS_NAME` to that endpoint with `target_method_simple_name = MTCALL_VALUE` (empty when MTCALL_VALUE is missing → class-granularity).

### 7c. ServletResolver — HttpServlet subclasses

**Source:** Java AST scan. Any class extending `javax.servlet.http.HttpServlet`.

**What gets emitted:**
- Class tagged with extra label `:Servlet`.
- `doGet` / `doPost` methods tagged `:EntryPoint`.
- A stub URL `"servlet:<ClassSimpleName>"` is created with a `:EXPOSES` edge — empty `target_method_simple_name` (class-granularity is correct here; HttpServlet subclasses are 1-class-1-URL by convention).

**Why we keep the stub:** when a patch touches an HttpServlet subclass whose real URL ISN'T mapped via either XML source, the stub keeps QA pointing at "something is exposed by this class" — better than silently dropping the boundary.

### The `target_method_simple_name` property — the precision lever

Without method-granularity, dispatcher-style classes like `WorkFlowAction` (which fronts ~48 distinct URLs via Struts URL-last-segment routing) would cause every patch on ANY method of the class to flag ALL 48 URLs as affected. That's exactly the false-positive cascade that produced `APIs=44` on the original ADMP `758cdf37d5` report.

**Edge property:**
- Empty string ≡ legacy class-granularity (servlet, REST-XML row without MTCALL_VALUE) — every URL the class exposes IS affected by any change in the class.
- Non-empty ≡ dispatcher granularity (security XML last-segment, REST-XML row with MTCALL_VALUE) — only the URL whose target equals the patched method's simple name is affected.

**Analyze-side filter** (`SliceExecutor.runAffectedApis` Step 1b, lines ~409-447):
```cypher
UNWIND $rows AS row
MATCH (c:Class {fqn: row.owner})-[e:EXPOSES]->(re:RestEndpoint)
WHERE coalesce(e.target_method_simple_name, '') = ''
   OR e.target_method_simple_name = row.simple
RETURN row.owner AS owner, re.url AS url
```
`row.simple` is the patched method's simple name (e.g. `approveRequest`). The `coalesce='' = ''` branch preserves servlet semantics; the equality branch filters dispatcher edges to the patched method only.

**Same filter is applied to `LayerImpact.restEndpointsReached`** (lines ~1236-1280) using `reachedSimplesByClass` (a per-class set of method simple-names from the patched + forward-reach + entry-point owners). Both sections of the report now agree.

### 7d. RequestParamResolver — parameter-value precision INSIDE a URL

URL-level matching is necessary but not sufficient. The same URL may route to a dispatcher method whose body branches on a request parameter — for example `/api/json/workflow/allRequests/approveRequest` routes to `WorkFlowAction.approveRequest()` which switches on `actionName ∈ {apply, cancel, reject}`. A patch in the `cancel` branch should surface `actionName=cancel` only, not `apply` or `reject`.

**Edge:** `(:Method)-[:READS_PARAM {value, block_start_line, block_end_line}]->(:RequestParam {name})`.

**Three call patterns captured** (all in `RequestParamResolver`):
1. **Bare reads:** `request.getParameter("foo")` → edge with empty `value`, block = whole method body.
2. **Inline comparisons:** `request.getParameter("foo").equals("bar")` → edge with `value="bar"`, block = enclosing `IfStmt`'s **then-body** range (NOT the whole if-else chain — see "negation gotcha" below).
3. **Local-var-bound comparisons:**
   ```java
   String action = request.getParameter("actionName");
   if (action.equalsIgnoreCase("cancel")) { ... }   // L2480-L2700 = block range
   ```
   Local-var binding tracked per-method via `Map<String,String> localVarToParam`.
4. **`switch (foo) { case "Y": ... }`** — selector matched against the var map; each case label gets its own block range = the `SwitchEntry` range.
5. **JSON-bag accessor pattern** (SPMP-specific — most SPMP/ADMP params arrive as a `params` JSON blob, not as flat HTTP params):
   ```java
   JSONObject params = new JSONObject(request.getParameter("params"));
   String action = params.getString("actionName");                          // ← "actionName" captured
   ```
   `JSONObject` / `Map` / `HashMap` / `LinkedHashMap` / `Hashtable` / `ConcurrentHashMap` typed local variables are flagged as "bags"; `get`, `getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getJSONObject`, `getJSONArray`, plus their `opt*` variants are recognised. First string-literal arg becomes the param name.

6. ✅ **Chained fluent JSON-bag access** (shipped 2026-05-24, task #113). The b3 detection now recurses into the receiver via `isChainableBagScope`, accepting:
   - `ObjectCreationExpr` of a bag type — inline ctor chain: `new JSONObject(x).getString("foo")`.
   - `MethodCallExpr` whose name is in `{getJSONObject, getJSONArray, optJSONObject, optJSONArray, get, opt}` (bag-returning) and whose own scope is recursively chainable — N-deep chain: `bag.getJSONObject("a").getJSONObject("b").getString("c")`.
   - `EnclosedExpr` (parenthesised), `CastExpr` to a bag type.
   Intermediate field names (e.g. `"a"`, `"b"` above) are picked up by separate iterations of the b3 loop because each chained `MethodCallExpr` is in `findAll()` and has a recursively-chainable scope. The outer `if (!jsonBagVars.isEmpty())` guard is dropped so inline-ctor chains are detected in methods with no bag-var declarations. Verified on ADMP: read-count rose from 25864 → 26051 (+187 chained reads). Concrete new RequestParam nodes: `schedulerSelection` + `durationJson` (from `ACCAction.java:165`'s 2-deep chain), `ComponentVals` + `fcUpnSuffix` + `userTypedValue` (from `MGetNamingFormatCommitListener.java:76`'s 3-deep chain), `alphaFilterValue` (from `OrchestrationTemplateAction.java:400`'s inline-ctor chain).

**Annotation-driven bindings:** `@RequestParam("X")` / `@PathVariable("X")` / `@QueryParam("X")` / `@FormParam("X")` / `@HeaderParam("X")` / `@PathParam("X")` on Spring/JAX-RS method parameters also emit bare reads.

### Big-JSON coverage matrix (the "params" envelope pattern)

Most SPMP/ADMP REST URLs declare a single `params` JSON envelope in the security XML template (e.g. `PROCESS_REQUEST` has 6 keys; `createModifyRequest` nests `inputValue` → array of `multipleInput`). Coverage of nested access depends on whether intermediate local variables are typed JSONObject/Map/etc.:

| Pattern | Captured? | Notes |
|---|---|---|
| Single-level `params.getString("X")` | ✅ | Standard SPMP idiom |
| **Nested via intermediate var** — `JSONObject inner = params.getJSONObject("inner"); inner.getString("X");` | ✅ | Works because `inner` is JSONObject-typed → added to `jsonBagVars` |
| Iterated — `for (JSONObject item : ...) { item.getString("X"); }` | ✅ | Loop-var `item` is JSONObject-typed |
| **Chained fluent** — `new JSONObject(req.getParameter("params")).getJSONObject("inner").getString("X")` | ❌ | **Gap.** No intermediate variable → scope of `.getString("X")` is itself a `MethodCallExpr`, not a name in `jsonBagVars`. Captures only the outer "params" bare read. |
| `JSONObject` returned from a helper — `JSONObject doc = getRequestBody(req); doc.getString("X");` | ✅ | `doc` is JSONObject-typed regardless of init |
| Top-level **`JSONArray params = new JSONArray(...)`** | ❌ | `JSONArray` not in bag-type allowlist. Field reads inside the array elements via JSONObject-typed loop var ARE captured (Gap mitigated when loop has intermediate var). |
| Dynamic keys — `params.get(runtimeKey)` | ❌ | Runtime key not statically available. Unfixable without dataflow. Acceptable miss. |
| Hash-based check — `if (params.has("X")) { ... }` | ✅ for the read | Subsequent `getString("X")` captured. `has()` itself doesn't emit a branch value (correct — it's presence not equality). |
| Gson / Jackson typed-bean parse | Partial | The outer `request.getParameter("params")` is captured. Field reads on the resulting bean (`form.getActionName()`) miss — the JSON keys aren't visible in the parse-result accessors without resolving the bean class's `@JsonProperty` annotations. |

### Recommended resolver fix — chained-scope walking

The chained-fluent gap is fixable with one helper in `RequestParamResolver`. When a `.getString("X")` call's scope is itself a `MethodCallExpr`, walk up the chain:

```java
// In the existing JSON-bag-accessor loop, replace the strict scope-var-name check
// with a chain-aware "is this scope reachable from a bag root?" walk.

private static boolean isJsonBagChainRoot(Expression scope, Set<String> jsonBagVars) {
    while (scope instanceof MethodCallExpr inner) {
        if (!JSON_GET_METHODS.contains(inner.getNameAsString())) return false;
        scope = inner.getScope().orElse(null);
    }
    if (scope instanceof NameExpr ne) {
        return jsonBagVars.contains(ne.getNameAsString());
    }
    if (scope instanceof ObjectCreationExpr oce) {
        String t = oce.getTypeAsString();
        return "JSONObject".equals(t) || "JSONArray".equals(t);   // new JSONObject(req.getParameter("X"))
    }
    return false;
}
```

The chain walk lets `.getJSONObject("inner").getString("name")` resolve to bag-root `new JSONObject(...)` or to a `jsonBagVars` variable at the chain's tail. While walking, emit a `:READS_PARAM` edge for **every** intermediate `.getJSONObject("X")` / `.getJSONArray("X")` call too — so a single chained expression with three JSON-key reads emits three edges instead of zero.

Effort: ~30 lines of code in the existing resolver, no schema changes. Pairs cleanly with the existing negation-gotcha fix (task #110-style — already on the backlog under a different ID).

### Honest caveat — block-range precision for nested JSON

Even after the chain-walk fix, the `:READS_PARAM` block range stays the **enclosing if/switch** for branch-value detection (`.equals("Y")` patterns) — not the JSON nesting depth. A patched line inside an `if (item.getString("type").equals("user"))` correctly attributes `type=user` to the if-body range. There's no semantic "JSON nesting" filter — and there shouldn't be; the block range tracks where the patched LINE sits, which is what hunk-overlap filtering needs.

**Analyze-side filter** (used inside the URL aggregation in `SliceExecutor`):
```cypher
WHERE pe.value <> ''
  AND ANY(h IN $hunks
          WHERE coalesce(pe.block_start_line, 0)              <= h.start
            AND coalesce(pe.block_end_line, 9223372036854775807) >= h.end)
```
Only edges whose block range CONTAINS the patch's hunk lines survive — that's what gives the report the precision to say *"actionName=cancel"* without flagging `apply` / `reject`.

### Resolver implications

The URL detection is **complete** (all three XML/AST sources exist and are wired into ingest). The method-granularity property (`target_method_simple_name`) and the param-value granularity (`:READS_PARAM` block range) are both shipped. No new resolver work is needed for URL detection itself.

What IS still pending and worth knowing:
- ✅ **Negation gotcha in RequestParamResolver — FIXED 2026-05-23.** Previously the resolver's `enclosingBranchBlock` ignored `UnaryExpr(LOGICAL_COMPLEMENT)`, so `if (!action.equalsIgnoreCase("apply"))` was incorrectly attributed to `value="apply"`. Symptom: the ADMP `758cdf37d5` report showed `actionName=apply` as a false positive (the patched code at L2490 is gated by `if (!action.equalsIgnoreCase("apply"))` at line 2312). The fix is in `RequestParamResolver.isInsideNegation(Node)` — walks ancestor chain from the equality call up to the enclosing `IfStmt`, counts `UnaryExpr.Operator.LOGICAL_COMPLEMENT` occurrences, returns true on odd parity. All four IfStmt-driven emission sites (inline `.equals()`, JSON-bag inline `.equals()`, local-var-bound regular form, local-var-bound `"Y".equals(foo)` reversed form) call this guard before emitting the value-edge. Switch-statement entries are not affected (you can't negate a switch selector).
- **Multi-hunk patches** correctly emit one ChangedSymbol per hunk; the analyze filter ORs across all hunks (so a patch touching the apply branch AND the cancel branch flags both).
- **OR-condition siblings** are intentionally NOT skipped — `if (x.equals("apply") || x.equals("cancel"))` emits value-edges for both, because the then-body genuinely fires for either value. The block-contains-hunk filter is an over-approximation that's correct here.

### 7e. WebXmlResolver — Tomcat web.xml servlet mappings

**Source:** `resources/tomcat/web_header.xml` + `resources/tomcat/web_footer.xml` under each repo root (concatenated at build time to form the deployed `web.xml`).

**File structure:**
- `web_header.xml` — filters, context-params, servlet definitions (21 servlets, 0 servlet-mappings)
- `web_footer.xml` — additional servlet definitions + all servlet-mappings (105 mappings)

**What gets parsed:**
```xml
<servlet>
    <servlet-name>com.manageengine.rmp.oumanager.GetOU</servlet-name>
    <servlet-class>com.manageengine.rmp.oumanager.GetOU</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>com.manageengine.rmp.oumanager.GetOU</servlet-name>
    <url-pattern>/GetOU</url-pattern>
</servlet-mapping>
```

**Resolution:** Standard `web.xml` semantics — join servlet-name → servlet-class and servlet-name → url-pattern to get class → URL.

**What is EXCLUDED (wildcard/dispatcher patterns):**
- `/api/v2/*` → `ADMPRestAPIServlet` (handled by `SecurityXmlResolver` via `apimethod`)
- `/api/*` → `ADSMServletAPIAction` (generic API dispatcher)
- `/RestAPI/*`, `/MobileAPI/*`, `/ADMPAPI/*` → Struts `ActionServlet` (handled by `RestApiXmlResolver` via `ADSProductAPIs.xml`)
- `*.do` → Struts `ActionServlet` (handled by `RestApiXmlResolver`)

**What IS emitted (non-wildcard specific URLs):**
- RMP module URLs: `/GetOU`, `/recyclebin`, `/Rollback`, `/BackupNow`, etc. → `com.manageengine.rmp.*` classes
- Framework servlet URLs: `/adsAuthenticator`, `/servlet/HSKeyAuthenticator`, `/fos/statuscheck`, etc.
- Specific `FWServletAPI` mappings: `/RestAPI/ConfigureTrust`, `/CVCAction.do`, `/ForceLoginChangePassword.do`

**Emits per non-wildcard mapping:**
- `:RestEndpoint {url: "/GetOU"}` node (deduped via URL key).
- `:EXPOSES` edge from servlet class FQN → endpoint (class-granularity, empty `target_method_simple_name`).

**Relationship to other resolvers:**
- `Servlet-Forward-Config.xml` is NOT ingested — it only defines JSP view-dispatch (URL → JSP forward page), not URL → Java class routing.
- `web_header.xml` provides filter declarations — these define URL interception, not routing. Filters are not emitted as EXPOSES.
- Wildcard patterns from `web_footer.xml` are skipped because their concrete URL→method routing is already captured by `RestApiXmlResolver` (via `ADSProductAPIs.xml`) and `SecurityXmlResolver` (via `apimethod`).

### Out-of-scope / honest gaps

- **Security XMLs without `apimethod`** — these 1,458 URLs (across `security.xml`, `security-restapi.xml`, `security-meone-include-filter.xml`, `security-meonefw-override.xml`) only emit `:RestEndpoint` nodes for discoverability. Their URL→method routing is handled by `ADSProductAPIs.xml` (RestApiXmlResolver), NOT by these validation files. The old heuristic (path-last-segment → method name match) is excluded because it produced false positives (e.g. URL segment `execute` matched 12 unrelated `execute()` methods). URLs exclusively declared in these validation-only files and NOT in `ADSProductAPIs.xml` remain dangling endpoints with no `:EXPOSES` edge.
- **Method overloading** (`approveRequest(req, resp)` vs `approveRequest(String)`) — both overloads get the same `:EXPOSES` target_method_simple_name match. Over-approximation. Future: filter by param-count signature on the edge.
- **Generated/copied XMLs in `build/`, `target/`, `node_modules/`** — walker excludes those path segments to prevent double-emission.
- **Per-repo attribution on `:RestEndpoint`** — the node has no `repoId` field. URL is the unique key. Acceptable: a URL declared in two ingested repos converges on one endpoint; both repos' classes contribute `:EXPOSES` edges with their own `target_method_simple_name`.
- **Resolver ordering** — SecurityXmlResolver and RestApiXmlResolver are independent; either can run first. The `:EXPOSES` MERGE on `(classFqn, url)` is keyed so duplicate edges from both sources collapse to one (with the `ON MATCH` clause preserving any pre-existing non-empty `target_method_simple_name`).

---

## 8. Notification macro data-population — `:NotificationMacro` family

Notification dispatch (§1) tells you *that* a notification fires; the **macro** tells you *which template variables* get filled in and *from which data sources*. A patch that changes the audit/request data feeding into a macro's `init(Hashtable)` (without touching the dispatcher itself) is invisible to the §1 edge — but the rendered notification content (subject, body, recipient list, per-object placeholders) changes. The macro boundary closes that gap.

### 8a. Why the boundary is the concrete `init(Hashtable)` override

The contract: every `NotificationMacro` implementation has an `init(Hashtable ids)` method that pulls a fixed set of keys out of the passed-in hashtable into instance fields. Subsequent `parseMacro*` calls read **only** from those fields plus DB queries keyed by those fields (notably `requestId`, `automationId`, `auditDO`). Everything the macro will ever output is determined by what `init()` captured.

- The **interface method** `NotificationMacro.init` is the wrong destination — pointing edges at it collapses all concrete subclasses (workflow vs. automation vs. report) into one indistinguishable node, exactly the false-positive cascade documented in §2b for `Task.executeTask`.
- The concrete override is what runtime dispatch lands on (`new <SubclassName>(); macro.init(ids);` is the universal call pattern — see §8d call sites).
- For `SingleNotifyMacro` (does **not** implement `NotificationMacro`), the constructor `SingleNotifyMacro(DataObject, Long, Long, Long, String, String, Long, HashMap)` is the equivalent data-ingestion point and gets the same `:MacroInit` treatment.

### 8b. Verified concrete macro classes — 6 in ADMP

| FQN | Module | Reads (from `ids` Hashtable unless noted) | Renders / populates | Selected at |
|---|---|---|---|---|
| `…server.admin.notification.MgmtNotificationMacro` | management (non-automation) | `auditDO`, `auditObjectIdList`, `objectId`, `reportId`, `viewColumnList`, `list`, `storageDir`, `actionId`, `audit_info`, `domainName`, `objectIdWithOU`, `isAutomation` | per-object mgmt-action template placeholders; reads `ADSMAuditDetails`/`ADSMAuditObjs`/`ADSMAuditObjProps` rows | `MgmtNotificationListener.triggerNotification` L237/L246 (MANAGEMENT branch) |
| `…server.automation.AutomationNotificationMacro` (extends `MgmtNotificationMacro`) | automation completion | adds `automationId`, `autoRaisedId`, `requestId`, `actionName`, `domainName`, `errorMessage`, `reqTaskId` on top of super.init | automation-name / subject / description / created-time / modifier / domain / failure-status macros (from `ADSMAUTOMATION`+`ADSMAUTOREQUESTS` join keyed by `automationId`+`autoRaisedId`) | `MgmtNotificationListener.triggerNotification` L180 (AUTOMATION branch) |
| `…server.automation.SendNotificationTaskMacro` (extends `AutomationNotificationMacro`) | automation "Send Notification" task in a workflow | adds `managerVsUsers`, `usersWithoutManagerList`, `objectIdList`, `managerDNList`, `userNmeVsObjectId`, `nameVsGuidMap`, `baseTableName`, `list`, `reportId`, `objNameList` | per-user / per-manager mail+SMS content for the workflow Send-Notification task | `CommitSendNotificationAction.java` L165, L199 |
| `…server.workflow.WFNotificationMacro` | workflow & ACC | `requestId`, `auditDO`, `domainName`, `isWorkflowExecution`, `storageDir`, `list`, `isWorkFlow`, `triggerId`, `adsmWorkFlowStatusID`, `wfCommentMailIds`, `audit_info` | request subject/description/comments/SLA/approval-history macros; resolves `baseTableName` via `WorkFlowUtil.getDetailsTableName(requestId)` then queries audit rows for the request | `WorkFlowAction` L2490, L2601, L3008, L4619; `WFMgmtAPIcall` L828/L884/L967; `ACCUtil` L336; `WorkFlow` L3081; `WFTaskAuditActivities` L515; `WFRequestExecutionSchedule` L303; `ScheduledACCTask` L182; `WFRuleExecutor` L257; `NotificationEscalation` L88; `StatusEscalation` L98; `PushNotificationTrigger` L87 |
| `…server.reports.ScheduleReportNotificationMacro` | scheduled reports | `scheduleId`, `auditId`, `ScheduleName`/`SCHEDULER_NAME`, `domainName`, `loginId`/`LOGIN_ID`, `RUN_NOW_LOGIN_ID`, `scheduleOwnerName`, `sharedTechniciansList`, `GENERATION_ID`, `isAdvancedScheduler`, `storageDir`, `isEmptyReport`, `emptyReports`, `fileList` | report-schedule subject / executor / owner / file-list macros | `ReportHandler.triggerTemplateNotification` L2090 |
| `…server.automation.SingleNotifyMacro` ⚠ not an interface impl | single-object automation notification | constructor params: `DataObject auditDO`, `requestId`, `loginId`, `autoRaisedId`, `baseTableName`, `columnName`, `automationId`, `HashMap requestDetails` | per-user (or per-M365-object) mail/SMS with manager-resolution and unique-id mapping | `NotificationTrigger.java` L452 (gated on `AutomationSingleNotify.getDataObjectForMacro`) |

**Important:** the table above is illustrative, NOT a hard-coded list. The resolver detects implementations structurally, so any future macro added under the `NotificationMacro` interface (or any new `*Macro` class instantiated immediately before a `NotificationTrigger`/`MgmtNotificationListener` call) is automatically captured.

### 8c. Macro-key granularity inside an affected class

A class being in forward reach (§8d) is necessary but not sufficient for QA. Each macro class populates a `requestDetails` / `mgmtProps` / `userProps` / `reportDetails` map with **dozens of distinct placeholder keys** (i18n strings like `admp.workflow.notification.macros.subject`). A patch usually affects only a **subset** of these keys — telling QA "WFNotificationMacro is affected" without naming the keys produces a re-test scope wider than reality.

The keys split into two layers by data source, and the layer determines whether the patch changes their rendered value:

**Layer A — request/automation-metadata keys** (one value per notification, sourced from `ADSMRequests` / `ADSMRequestDetails` / `ADSMAUTOMATION` / `AAAUSER` / `ADSMWorkFlowStatus`):

| Key (verbatim from source) | Populated from | Affected by this patch? |
|---|---|---|
| `admp.workflow.notification.macros.requestid` | `ADSMRequests.REQUEST_ID` (WFNotificationMacro.java:1330) | ❌ — request id is identity, not user-list-derived |
| `admp.workflow.notification.macros.requestor` (+ `_displayName`) | `AAAUSER.FULL_NAME` / `DISPLAY_NAME` (WFNotificationMacro.java:1340-1342) | ❌ — requestor is the technician who submitted the request, not the imported users |
| `admp.workflow.notification.macros.createtime` | `ADSMRequestDetails.CREATED_TIME` / `ADSMAUTOMATION.MODIFIED_TIME` (WF L1344, AutomationNM L88) | ❌ — timestamp is fixed at request creation, predates the filter |
| `admp.workflow.notification.macros.subject` | `ADSMRequestDetails.SUBJECT` / `ADSMAUTOMATION.SUBJECT` (WF L1345, AutomationNM L89) | ❌ — text taken from the automation/template definition |
| `admp.workflow.notification.macros.description` | `ADSMRequestDetails.DESCRIPTION` / `ADSMAUTOMATION.DESCRIPTION` (WF L1346, AutomationNM L90) | ❌ — same as subject |
| `admp.workflow.notification.macros.workstatus` (+ `WFStatus`) | `ADSMWorkFlowStatus.WORKFLOW_STATUS_NAME` (WFNotificationMacro.java:1349) | ❌ — workflow state, independent of user list |
| `admp.workflow.notification.macros.status` | `ADSMRequestStatus.STATUS_NAME` (WFNotificationMacro.java:1357) | ❌ — request-level status, not per-user |
| `admp.workflow.notification.macros.modifiername` (+ `_displayName`) | `ADSMWorkFlowApprovalDetails` / `TECHNICIAN_NAME` (WF L1067-1071, MgmtNM L1928, AutomationNM L95) | ❌ — technician identity, not affected by filtered user set |
| `admp.workflow.notification.macros.comments` | `ADSMWorkFlowComment.COMMENT` (WFNotificationMacro.java:1079-1081) | ❌ — technician comments on the workflow |
| `admp.workflow.notification.macros.reviewer` / `approver` / `executor` (+ `_displayName`) | `ADSMWorkFlowApprovalDetails` joined to `AAALOGIN` (WF L1087-1107) | ❌ — workflow-stage technicians |
| `admp.workflow.notification.macros.expire.time` | `WorkFlowUtil.getExpireTimeDetails` (WFNotificationMacro.java:1361) | ❌ — SLA expiry, independent of user list |
| `admp.workflow.notification.macros.executelink` / `request_link_macro` | `getRequestLink(rb)` (WFNotificationMacro.java:1358-1359) | ❌ — URL of the request |
| `admp.workflow.notification.macros.actiontile` / `actioname` | `ADSMAuditDetails.ACTION_CATEGORY` + `ACTION_NAME` (MgmtNM L1926-1927) | ❌ — action label, unchanged for the same automation |
| `admp.admin.notification_template.automation.automation_name_macro` | `ADSMAUTOMATION.AUTOMATION_NAME` (AutomationNM L80) | ❌ — automation name unchanged |
| `admp.admin.notification_template.automation.action_time` | `System.currentTimeMillis()` at dispatch (MgmtNM L1936) | ❌ — timestamp |
| `admp.admin.notification_template.automation.failure_status_macro` | `this.errorMessage` (AutomationNM L100) | ⚠ — possibly affected if filter empties the user list and `ErrorCode 17 / INSUFFICIENT_DETAILS_FOR_GUID` is propagated into the error message (see `RequestExecutionFlow.getGUIDListFromAllSource` L780-783 — same hunk's downstream). Edge case; flag for QA. |
| `client.computermanagement.domainName` | `this.domainName` (MgmtNM/AutomationNM) | ❌ — domain is per-request |

**Layer B — per-object (per-user) keys** (one value **per audit-obj row**, rendered by iterating `auditDO.getRows("ADSMAuditObjs")` and gating on `auditObjectIdList`):

The patch shrinks `auditObjectIdList` (via the upstream filter on `UNIQUE_ID` and OU criteria); every Layer-B key is therefore rendered against a **smaller / different** object set. Verified iteration sites: MgmtNotificationMacro.java:195, 218, 334, 666, 780, 940, 1347, 1600, 2308, 2635 — each gated by `this.auditObjectIdList.contains(objectId)`.

| Key (verbatim) | Populated from | Affected by this patch? |
|---|---|---|
| `jsp.login.user_name` | `PROP_VALUE` of the row's name column (MgmtNM L849) | ✅ — list of usernames in the notification body shrinks/differs |
| `OBJECT_GUID` | row's `OBJECT_GUID` (MgmtNM L853) | ✅ — set of GUIDs shrinks |
| Every LDAP-attribute placeholder declared in `customLdapNames` — `sAMAccountName`, `displayName`, `mail`, `givenName`, `sn`, `department`, `title`, `manager`, `userPrincipalName`, `userAccountControl`, `pwdLastSet`, `accountExpires`, `physicalDeliveryOfficeName`, `telephoneNumber`, `homeDrive`, `homeDirectory`, all custom attribs etc. | populated per-row by `fillPropsFromDetailsTable` from `<ObjectClass>BaseTable` and custom-attribute tables (MgmtNM L1816, L1838, L1898, L1911; WF L2034, L2381, L2662, L2793) | ✅ — list rendered for each placeholder shrinks; per-user values are different users |
| `admp.workflow.notification.macros.Manager` (per-user) | `ADHandler` lookup of manager DN per object (MgmtNM L871-872; WF L2013, L2021, L2124, L2437, L2457) | ✅ — manager list now reflects only filtered users |
| `ManagerName` (per-user, aggregated) | string-joined manager names (MgmtNM L1067, L1566) | ✅ — same |
| `admp.admin.notificationTemplate.mail_recipient.users_manager_macro` | O365 manager email per row (MgmtNM L874) | ✅ — M365 case |
| `admp.common.attrib_disp_name.password` | password column on user-creation actions (MgmtNM L851; WF L488) | ✅ — only present for the filtered-in created users |
| `admp.admin.notificationTemplate.message_content.macro.mgmt_status_macro` | per-row `STATUS` column from `ADSMAuditObjs` (MgmtNM L880, L882) | ✅ — status list reflects filtered set |
| `ouName` | `ouName` lookup per object (MgmtNM L852, L2224, L2282, L2316) | ✅ — OU list per user shrinks |
| `admp.workflow.notification.macros.folder_list_macro` | folder list per user (WF L839, L971) | ✅ — file-server folder-management scenarios |
| `memberOf_dn` | aggregated `memberOf` list per object (MgmtNM L3000, L3044, L3065) | ✅ — group-mgmt scenarios |
| HTML-table body of `parseMacroForAdminWithTable` (rendered into whatever template macro the caller substitutes it into) | iterates `auditObjectIdList` and emits one `<tr>` per object (MgmtNM L1092+) | ✅ — table has fewer/different rows |
| Recipient lists from `parseMacroUserNotificationMaildIds` / `parseMacroManagerNotificationMaildIds` / `parseMacroUserMobileNo` | `setUserMailIds` / `getManagerEmailIds` called for each object in `auditObjectIdList` (MgmtNM L235, L241; SendNotificationTaskMacro L102, L111, L328) | ✅ — fewer recipients, recipient set determined by filtered users |
| `admp.workflow.notification.macros.requestor` *in SendNotificationTaskMacro context* (set per-object as the affected user, not the technician) | per-object override (SendNotificationTaskMacro L184) | ✅ — per-user identity |

**The conclusion in one sentence:** every **Layer-A key is unaffected**; every **Layer-B key is affected** — because the patch acts on `auditObjectIdList` (the per-object iteration domain), not on request-metadata columns.

### 8c2. Layer C — control-flow-gated notification dispatch (the case Layer A/B miss)

Layer A and Layer B describe **what the macro renders when it runs**. They do not describe **whether the macro runs at all**. A patch can affect a notification — and a specific macro key — without touching the macro class or its data sources, by changing the control flow that gates the dispatch.

**Worked example — ADMP patch `758cdf37d5...7e22fcaf73`** (Issue 13032, reject-path NPE):

```java
// WorkFlowAction.java :2480-2587 (reject / cancel branch)
if (action.equalsIgnoreCase("cancel")                        //  L2480 — enclosing branch
        && WorkFlowClientUtil.isAllowToRejectRequeset(requestId)) {
    int wFStatusId = WorkFlowUtil.WORKFLOW_STATUS_CANCELED;
    wfviRequest.updateRejectRequestTaskStatus();
    if (WorkFlow.modifyRequestWorkflow(wfviRequest, loginId, comment,
            wFStatusId, WorkFlowUtil.WORKFLOW_REQUEST_STAUS_CANCELLED)) {    // L2486 — STATUS WRITE
        message = ...;
        NotificationMacro macro = new WFNotificationMacro();                  // L2490 — MacroInit
        Hashtable macroDetailsMap = new Hashtable();
        macroDetailsMap.put("requestId", requestId);
        macroDetailsMap.put("adsmWorkFlowStatusID",
                Long.valueOf(WorkFlowUtil.WORKFLOW_REQUEST_STAUS_CLOSED));
        macro.init(macroDetailsMap);                                          // L2498
        ...
        for (int i = 0; i < instantTaskSetList.getAdmpTaskList().size(); i++) {
            ...
            FcBulkExecuteFormBean fcBulkExecuteFormBean = request!=null       // L2518 — PATCHED
                ? ClientUtil.getBulkExecuteFormBean(request) : null;
            if (fcBulkExecuteFormBean != null) {                              // L2519 — PATCHED (added null guard)
                templateCategoryID = fcBulkExecuteFormBean.getTemplateCategoryId();
                isMgmtModification = fcBulkExecuteFormBean.getIsMgmtModification();
            }
            ...
        }
        NotificationTrigger trigger = new NotificationTrigger(..., macro,
            NOTIFICATION_TEMPLATE_MODULE_WORKFLOW, ...);                      // L2582
        trigger.setNotificationTemplates(associatedTemplates);
        trigger.start();                                                      // L2584 — SENDS_NOTIFICATION
    }
}
```

**Why Layer A/B miss this:**
- Forward **data**-reach from the patched line (L2518-L2521) flows into `templateCategoryID` and `isMgmtModification`, neither of which the macro reads. The macro's `init` already ran at L2498, **upstream** of the patch. Data-flow analysis says: macro is unaffected.
- Pre-fix runtime behavior: `request != null` but `ClientUtil.getBulkExecuteFormBean(request)` returns null (async/queued listener context — see FixDetails 13032). `fcBulkExecuteFormBean.getTemplateCategoryId()` throws NPE. The enclosing `try` swallows it. **Every statement after L2520 in the same `if (modifyRequestWorkflow(...))` block — including L2582-L2584 — never runs.** Notification is silently dropped.
- Post-fix: NPE no longer fires; the loop completes; `trigger.start()` at L2584 runs; the notification dispatches with the macro content WFNotificationMacro produces.
- **Affected macro key: `admp.workflow.notification.macros.workstatus`** (per WFNotificationMacro.java:1349 — reads `ADSMWorkFlowStatus.WORKFLOW_STATUS_NAME`, which `WorkFlow.modifyRequestWorkflow` at L2486 just wrote to `WORKFLOW_STATUS_CANCELED` / `_REJECTED`). Plus every other macro key in the same notification — but `workstatus` is the one that *changes value* in the same enclosing block as the patch, which makes it the highest-signal row in the affected-keys report.

### The Layer-C detection logic

A patched line is in the **dispatch-gating set** of a `:MacroInit` / `:SENDS_NOTIFICATION` call when **all four** hold:

1. **Co-resident**: walking AST parents from the patched line, you reach the same enclosing block (method body, `if` body, `try` body, `for`/`while` body) that contains the macro-init **and** the trigger.start() / triggerNotification call.
2. **AST-position-between**: the patched line is positioned between the macro-init and the trigger call. (Patched lines *before* macro-init are upstream — already covered by Layer-B data-reach. Patched lines *after* the trigger can't influence the dispatch.)
3. **Exception-throwing-capable in a way the surrounding `catch` would absorb silently** — i.e., the patch removes / adds an NPE risk, an `instanceof` guard, a null-safe operator, a try/catch widening, an explicit `throw`/swallow, or any other change that would have prevented the trigger from being reached. Pure stylistic refactors with identical semantics don't qualify.
4. **The notification's macro reads at least one column written by a sibling statement in the same enclosing block** — e.g., `WorkFlow.modifyRequestWorkflow(..., wFStatusId, ...)` writes `ADSMRequests.WORKFLOW_STATUS`, which `WFNotificationMacro` reads to render `workstatus`. This is what gives QA the **specific** macro key, not just "some notification".

Conditions 1-3 detect whether the macro+dispatch fires at all (binary effect). Condition 4 names the specific macro keys whose **rendered value differs** between pre/post-patch (because the sibling statement set the column to a new value that the macro now reads).

### Status-writer → macro-key mapping (Condition 4)

The boundary doc ships with a fixed table of "WHICH DB write feeds WHICH macro key", so the resolver can resolve Condition 4 to a specific affected key. Built from the macro source-line references already in §8c Layer A:

| Sibling-statement DB write (call shape) | Column written | Macro key affected | Source citation |
|---|---|---|---|
| `WorkFlow.modifyRequestWorkflow(req, login, comment, wfStatusId, requestStatusId)` | `ADSMRequests.WORKFLOW_STATUS`; `ADSMWorkFlowStatus.WORKFLOW_STATUS_NAME` (via join) | `admp.workflow.notification.macros.workstatus`; `WFStatus` | WFNotificationMacro.java:1348-1356 |
| `WorkFlow.modifyRequestStatus(...)` / `wfviRequest.updateRequestStatus(...)` | `ADSMRequestStatus.STATUS_NAME` | `admp.workflow.notification.macros.status` | WFNotificationMacro.java:1357 |
| `ADSMRequestDetails` insert/update of `SUBJECT` / `DESCRIPTION` | corresponding columns | `admp.workflow.notification.macros.subject`; `admp.workflow.notification.macros.description` | WFNotificationMacro.java:1345-1346 |
| `ADSMWorkFlowComment` insert (comment-add) | `COMMENT` | `admp.workflow.notification.macros.comments` | WFNotificationMacro.java:1079-1081 |
| `ADSMWorkFlowApprovalDetails` insert with technician identity + `nextWorkflowStatus` (reviewer/approver/executor stage) | `LOGIN_ID` + `WORKFLOW_STATUS` | `admp.workflow.notification.macros.modifiername` (+ `_displayName`), and one of `reviewer` / `approver` / `executor` (+ `_displayName`) depending on the `nextWorkflowStatus` value (`PRE_STATUS_REVIEWER` / `APPROVER` / `EXECUTOR`) | WFNotificationMacro.java:1067-1107 |
| `WorkFlowUtil.getExpireTimeDetails(...)` / SLA-write | `ADSMRequestSLA.EXPIRY_TIME` | `admp.workflow.notification.macros.expire.time` | WFNotificationMacro.java:1361 |
| `ADSMAuditDetails` insert (a new audit row for the action) — `ACTION_CATEGORY`, `ACTION_NAME`, `TECHNICIAN_NAME` | corresponding columns | `admp.workflow.notification.macros.actiontile`; `admp.workflow.notification.macros.actioname`; `admp.workflow.notification.macros.modifiername` | MgmtNotificationMacro.java:1926-1928 |
| `ADSMAUTOMATION` modify (the automation row's SUBJECT/DESCRIPTION/MODIFIED_TIME) | corresponding columns | `admp.workflow.notification.macros.subject`/`description`/`createtime`; `automation_name_macro` | AutomationNotificationMacro.java:80-90 |
| Any insert into `ADSMAuditObjs` for a new request object | row added | every Layer-B per-user key (because `auditObjectIdList` grows) | MgmtNotificationMacro iteration sites at L195/L218/L334/L666/L780/L940/L1347/L1600/L2308/L2635 |

**For the 758cdf37d5 patch specifically**, Conditions 1-4 resolve as:
- (1) Co-resident: ✅ patched lines (2518-2521) are inside the same `if (WorkFlow.modifyRequestWorkflow(...))` block as the macro-init (2490-2498) and the `trigger.start()` (2584).
- (2) AST-position-between: ✅ 2518 > 2498 (macro-init) and 2518 < 2584 (trigger).
- (3) NPE-risk change: ✅ patch adds null guard for `fcBulkExecuteFormBean`.
- (4) Sibling status-write: ✅ `WorkFlow.modifyRequestWorkflow(..., WORKFLOW_STATUS_CANCELED, WORKFLOW_REQUEST_STAUS_CANCELLED)` at L2486 writes `ADSMRequests.WORKFLOW_STATUS` → table maps to `admp.workflow.notification.macros.workstatus`.
- → **Affected macro key: `admp.workflow.notification.macros.workstatus`** (renders the just-written CANCELED / REJECTED label). Issue 13032 FixDetails confirms the QA-observable symptom: prior to the patch the entire reject-path notification was silently dropped; after the patch it dispatches with `%workstatus%` populated.

### Why my earlier Layer-A "unaffected" verdict was wrong for this patch

§8c's Layer-A table marks `workstatus` as ❌ ("workflow state, independent of user list"). That's correct *for the CSV-import patch* I analyzed first, because that patch doesn't sit in a branch that writes `WORKFLOW_STATUS`. It is **wrong** for any patch that sits in the cancel/reject branch — because the cancel/reject branch *does* write `WORKFLOW_STATUS` in a sibling statement to the macro+trigger.

**The general rule:** "Layer A is unaffected" is a per-patch *default*, not a universal verdict. For each Layer-A key, the default flips to ✅ when the patched line satisfies all four Layer-C conditions for the column that feeds the key. The status-writer → macro-key table above is the lookup the resolver uses to flip individual cells of Layer A on a per-patch basis.

### Resolver implication for Layer C

Add a third visit pass on top of the §8f Pattern A/B detection:

```
Pattern C — control-flow-gated notification dispatch
  For each :SENDS_NOTIFICATION edge:
    Locate the enclosing block (BlockStmt) containing both the trigger.start() / triggerNotification call
        and the immediately preceding ObjectCreationExpr + .init() chain for a :NotificationMacro class.
    For each ChangedSymbol (patched line) inside that block, between the macro-init position and the trigger position:
      Check the patch is "control-flow-altering" — heuristics:
        - Adds / removes a null check on a previously-unguarded dereference
        - Adds / removes a try / catch / throw / return
        - Adds / removes a guard expression in an if / ternary / && / ||
      If yes:
        For each sibling statement in the same block that is a known DB-write (status writer table above):
          Emit a :GATES_DISPATCH edge from the patched method to the :NotificationMacro class.
          Stamp the edge with `affected_macro_keys` = the column→key mapping.
        Also emit a generic `:GATES_DISPATCH` edge for the binary effect (notification fires-or-doesn't), with `affected_macro_keys = "*"` so the report still surfaces it even when no sibling DB write is identified.
```

The `:GATES_DISPATCH` edge is distinct from `:POPULATES_MACRO` — the latter says "this patch changes what the macro renders when it runs"; the former says "this patch changes whether the macro runs at all". Both need to surface in the affected-macros section of the report.

✅ **Shipped 2026-05-26 in `NotificationAuditResolver.detectDispatchBlock` + `SliceExecutor.runAffectedMacros` Layer C branch.** Hooked into both N1 Pattern A (`MgmtNotificationListener.triggerNotification`) and N1 Pattern B (`new NotificationTrigger().start()`) call sites — every notification trigger gets a block-scan for the preceding macro-init. Implementation deltas from the spec above:

- **Block start anchors at the macro variable's DECLARATION line, not the `.init()` call line.** The spec sketch says "starting at macro-init"; real ADMP code typically has a separate `new XxxMacro()` declaration that's 1-8 lines before `.init()`, and patches that populate the `Hashtable` argument BEFORE `.init()` consumes it still gate dispatch. The canonical 758cdf37d5 case proves this: patched lines L2493-L2497 sit BETWEEN `NotificationMacro macro = new WFNotificationMacro();` at L2490 and `macro.init(macroDetailsMap);` at L2498. Using the `.init()` line as block start would miss this entirely; the resolver now records the block as (L2490, L2582) using the `VariableDeclarator` range.
- **Concrete-type detection prefers the `ObjectCreationExpr` initializer over the declared type.** Code that writes `NotificationMacro macro = new WFNotificationMacro()` was originally recording "NotificationMacro" (the base interface) as the macro simple-name; every dispatch block collapsed onto the same generic interface node. The resolver now reads `vd.getInitializer()`; when the initializer is an `ObjectCreationExpr`, its type-name wins. Falls back to the declared type when the initializer isn't a `new` expression (e.g. macro injected as a method parameter).
- **`Macro`-suffix gating in `isMacroType()`.** Excludes the documented non-notification `*Macro` classes (§8h): `CustomActionMacro`, `O365AutoReplyMacro`, base orchestration `Macro`, `*Assignee*` (WFAssigneeMacro family). Pre-fix the gate accepted any `*Macro`-suffixed simple name, producing edges to unrelated classes; post-fix only the 5 verified `NotificationMacro` subclasses + structural `SingleNotifyMacro` qualify.

✅ **Writer-side write-time class lookup** (`Neo4jWriter.writeGatesDispatch`). The MERGE matches the destination by `(:Class {simple_name})` rather than `(:NotificationMacro {simple_name})`. The `:NotificationMacro` label is applied by `NotificationMacroResolver.afterAll` AFTER the streaming partial flushes have already written edges; gating the write on the label dropped all 44 detected edges from earlier flushes. The label filter is enforced at analyze time instead (`MATCH (caller)-[g:GATES_DISPATCH]->(cls:NotificationMacro)`), where the post-ingest graph has the labels applied.

✅ **`gatesDispatch` NOT drained in `ExtractionBatch.drainLeafCollections`.** Class nodes never drain in partial flushes (they only persist in the final `writeBatch`), so a drained `gatesDispatch` edge would fail its OPTIONAL MATCH against the class. Leaving the list in master ensures the writes happen after `writeClasses(...)` in the same final batch.

✅ **Analyze-side reconciliation: hunk-rows use graph-form FQN, not patch-form FQN.** The hunk resolver produces FQNs with SymbolSolver `?` placeholders for unresolved param types (`approveRequest(?,?)`); the graph stores source-text params (`approveRequest(HttpServletRequest,HttpServletResponse)`). `SliceExecutor.runAffectedMacros` Layer C path expands each `pre.symbols()` entry through `inputToGraphFqns` (the same reconciliation map every other §4.1 query uses) so the per-symbol `MATCH (caller:Method {fqn: row.fqn})` actually resolves. Hunk-line fallback: when `s.hunkStartLine() == 0` (no explicit hunk lines), falls back to the enclosing-symbol declaration range — matches the established `:READS_PARAM` filter pattern.

✅ **Renderer wiring: `HtmlReportRenderer` now maps `report.macrosAffected()` into the FreeMarker model.** Previously the FTL template referenced `macrosAffected` but the renderer never put it into the model, so the section always rendered as "Notification Macros Affected (0)" even when the analyzer reported a hit. Map keys exactly match the FTL field names (`macroClassFqn`, `macroSimpleName`, `changedSymbolsReaching`, `sampleEmittingMethods`, `risk`).

✅ **§8c2-f Per-key attribution via `MacroKeyRegistry` + sibling-method extraction (2026-05-26).** The original Layer C implementation reported ALL keys for the affected macro class ("binary gating" — entire notification was suppressed). This produced 22 keys for `WFNotificationMacro` when only `%WorkflowStatus%` and `%Status%` are actually affected by the 758cdf37d5 patch (the sibling `modifyRequestWorkflow(...)` call writes the workflow-status column that the macro reads). The fix has three components:

- **Sibling-method extraction at ingest time** (`NotificationAuditResolver.detectDispatchBlock`). After identifying the dispatch block (blockStart → triggerLine), the resolver scans all `MethodCallExpr` nodes within that line range, collects their simple names (excluding `init`/`start`), and passes them as `siblingMethods` in the `GatesDispatchEdge`. `Neo4jWriter.writeGatesDispatch` persists the list as `r.sibling_methods` (a string-array property) on the `:GATES_DISPATCH` edge (`ON CREATE SET` + `ON MATCH SET`).

- **`MacroKeyRegistry` classpath resource** (`src/main/resources/macro-keys.json`). Comprehensive JSON mapping of all 6 `NotificationMacro` classes → their keys (macroKey, placeholder, dbColumn) + two lookup tables: `_statusWriterTable.entries` (sibling method name → affected macro keys, e.g. `modifyRequestWorkflow` → `[workstatus, status]`) and `_statusWriterTable.tableWriteEntries` (DB table name → affected macro keys, e.g. `ADSMWorkflowStatus` → `[workstatus]`). Loaded once as a singleton via `MacroKeyRegistry.instance()`.

- **Three-tier narrowing in `SliceExecutor.runAffectedMacros` Layer C path.** (1) Primary: `allSiblings` collected from the Cypher query's `g.sibling_methods` properties → `registry.keysFromSiblingCalls(siblingNames)`. (2) Fallback: `registry.keysAffectedByTables(simple, patchWritesTables)` + `registry.keysFromWrittenTables(patchWritesTables)` using the patched method's `:WRITES_TABLE` edges. (3) Final fallback: `registry.allKeysForClass(simple)` only when neither tier resolves (truly unknown scope). The `AffectedMacro` record gains `List<String> affectedMacroKeys` (backward-compat ctor retained); `report.ftl` renders a new "Affected Placeholders" column with "(sibling-write narrowed)" or "(all — binary gating)" annotation depending on whether narrowing succeeded.

- **Implementation delta vs binary-gating spec:** The original §8c2 spec says "binary effect — entire notification was dropped pre-fix, so all keys are affected." Strictly true in the fires-or-doesn't sense, but practically useless for QA (they need to know WHICH template fields to verify). The per-key narrowing answers the useful question: "given the dispatch now fires, which specific placeholders carry data that this patch's sibling writes populate?" For the 758cdf37d5 patch: `modifyRequestWorkflow` is a sibling in block L2490-L2582 → maps to `admp.workflow.notification.macros.workstatus` + `admp.workflow.notification.macros.status` → report shows `%WorkflowStatus%`, `%Status%`.

**Verification on the 758cdf37d5 patch (Issue 13032):** ingest emits 44 `:GATES_DISPATCH` edges (32 land on `:NotificationMacro`-labeled classes, 12 on plain `:Class` nodes where the macro name doesn't match any tagged concrete class — those are filtered out by the analyze-side `(cls:NotificationMacro)` label). Re-analyze on the patch surfaces `macros=1` (was `macros=0` pre-fix), HTML row: `WFNotificationMacro` / `1 changed method` / `approveRequest (gates L2490-L2582)` / **HIGH** / `%WorkflowStatus%`, `%Status%` (sibling-write narrowed). Layer A/B return 0 (no forward-call-graph reach exists from the patched lines to a `:MacroInit` node — exactly the Layer-A/B gap §8c2 was designed to close); Layer C HIGH-risk supersedes the (non-existent) Layer-A/B MEDIUM.

✅ **§8c2-g Layer D — direct modification of a NotificationMacro class (2026-05-26).** Layers A/B detect methods that *call forward into* macro classes; Layer C detects methods that *gate dispatch to* macro classes. Neither fires when the changed method **belongs to** the macro class itself. Layer D closes this gap with a simple Cypher pattern:

```cypher
MATCH (cls:Class:NotificationMacro)-[:CONTAINS]->(m:Method {fqn: row.fqn})
OPTIONAL MATCH (:Method)-[h:HANDLES_ATTRIBUTE]->(cls)
  WHERE h.block_start_line <= row.hEnd AND h.block_end_line >= row.hStart
```

If a changed method is contained by a `:NotificationMacro` class, layer D flags it as **CRITICAL** risk. It then narrows affected keys using `:HANDLES_ATTRIBUTE` edges: `MacroAttributeResolver` (at ingest time) detects `ldapName.equals("X")` / `equalsIgnoreCase("X")` patterns and records the enclosing `if`-block line range per attribute. At analyze time, patch-hunk lines are overlapped against these block ranges; only attributes whose blocks intersect the hunks are reported. Fallback to all keys when no `:HANDLES_ATTRIBUTE` edges exist or no overlap is found.

**FQN mismatch note:** The `:HANDLES_ATTRIBUTE` edge lives on a phantom Method node (simple param types from `ResolverUtils.methodFqn`) while `:CONTAINS` targets the canonical Method node (fully-qualified params from CallResolver). The query uses `(:Method)-[h:HANDLES_ATTRIBUTE]->(cls)` (any Method pointing to the same class) instead of `(m)-[h]->(cls)` to bridge this difference.

**Risk escalation:** CRITICAL > HIGH > MEDIUM. Layer D CRITICAL supersedes any existing Layer A/B MEDIUM or Layer C HIGH entry for the same class (merged via `byClass` map in `SliceExecutor.runAffectedMacros`).

**Verification on the `9b3f4f5752` patch (Issue 13216):** The patch modifies `WFNotificationMacro.parseMacrosAdmin()` directly — adds `daysToExpireAccount` date-formatting logic inside the macro's per-user property iteration loop. Hunk L172–290 overlaps 3 attribute blocks: `WfComments` (L179–187), `daysToExpireAccount` (L194–218), `groupType` (L227–238). Of these, only `daysToExpireAccount` has a `macro-keys.json` entry → report shows CRITICAL / `%DaysToExpireAccount%` (was all 23 keys before attribute-level narrowing).

### 8d. Cross-class summary — which macro × which keys

| Macro class | Layer-A keys it renders | Layer-B keys it renders | Net effect of this patch |
|---|---|---|---|
| `WFNotificationMacro` | requestid, requestor(+display), createtime, subject, description, workstatus, status, modifiername(+display), comments, reviewer/approver/executor(+display), expire.time, executelink, request_link_macro | username, OBJECT_GUID, all LDAP attrs, Manager (per-user), folder_list_macro, mgmt_status_macro, ouName, memberOf_dn, password | **Layer-B output differs** — every per-user line in the notification body, table, or recipient list reflects the filtered user set. Layer-A request-metadata content is identical. |
| `AutomationNotificationMacro` (extends MgmtNM) | automation_name_macro, createtime, subject, description, modifiername(+display), actioname, action_time, failure_status_macro (⚠ edge case), domainName | (inherited from MgmtNM) all LDAP attrs, username, Manager, password, ouName, status, mgmt_status_macro | **Layer-B output differs** (inherited iteration); `failure_status_macro` may flip when filter empties the list. Layer-A automation-metadata is identical. |
| `SendNotificationTaskMacro` (extends AutomationNM) | actiontile, actioname, modifiername, action_time, domainName | per-user-with-manager iteration: username, manager, password, all LDAP attrs, recipient mail/mobile | **All output of this macro is Layer-B-shaped** — the entire purpose of the macro is per-user recipient rendering. Send-Notification-task notifications during a CSV-driven automation: fewer recipients, different per-user content. |
| `SingleNotifyMacro` | (none — constructor takes `requestDetails` populated by upstream caller) | per-user recipient lookup gated by `userAutomationActionIds` / `groupAutomationActionIds` / `computerAutomationActionIds` / `contactAutomationActionIds` (HashSet keyed by auditObj IDs); manager-vs-user maps; mail/mobile lookups | **All Layer-B** — single-object notifications won't fire for filtered-out users at all; per-user content for remaining users uses the same data shape. |
| `MgmtNotificationMacro` (standalone) | actiontile, actioname, modifiername(+display), action_time, isCreateAction, isDeleteAction | (same per-user iteration as inherited usages above) | Not selected by this patch's path — see §8e for why only the automation branch fires for the auto-created request; standalone-instance row skipped. |
| `ScheduleReportNotificationMacro` | scheduleId, ScheduleName, scheduleOwner, executor, sharedTechniciansList, fileList, isEmptyReport, emptyReports | (none — not user-iterating) | Not on the patch's data graph. |

### 8e. Patch impact — worked across two ADMP patches

Two ADMP patches have been used as testbeds for §8c/§8c2 reasoning. They illustrate the **two structurally different ways** a patch reaches a macro: data-flow (Layer-B, upstream of `init`) and control-flow (Layer-C, gates the dispatch). Each yields a different affected-keys set.

#### Patch 1 — `1660081c13...4feea1efe7.patch` (CSV-import OU/exclude filter)

The patch modifies the **automation CSV-import filter chain**, not any macro class. The change is upstream of the macros — it shrinks the user set that ends up in the workflow request's audit rows. Concretely:

- `AutomationCSVImportListener.getGUIDListFromCsv` (web/adsm) — gains a `filteredUniqueIDList` that prunes `userDetailsList` / `valuesVectorlist` / `customAttributesList` on `robo` (the `ScheduledRequest`). Users whose `UNIQUE_ID` doesn't match the OU/excluded-OU criteria are dropped before request creation.
- `FcBulkExecuteFormFlow.addObjectGuidToList` (web/adsm) — new `fillOuAndExcludeMap(robo, domainName)` builds an OU-criteria filter, ANDed into the AD lookup `Criteria` before object-GUID resolution.
- `IMgmtListener.importCSVDetailsForAutomation` and the 5 `Fc*CreationListener` overrides — signature extended with `ScheduledRequest robo` so the filter context propagates down.
- `AddUserRequestHandler` (java_source) — passes `robo` into the new signature at both `importCSVDetailsForAutomation` call sites.

These changes flow forward into the workflow request's `ADSMAuditDetails` / `ADSMAuditObjs` / `ADSMAuditObjProps` rows (smaller object count, different object identities). Macros that read those rows render different output.

**Reached macros (deep forward-reach, not name-matching):**

| Macro | Why reached |
|---|---|
| ✅ `WFNotificationMacro` | `init` reads `requestId` then queries `ADSMAuditDetails`/`ADSMAuditObjs` for that request — the rows the patch's filter shrinks. Workflow approval / commit / assigning-rule notifications on the auto-created request all render against the filtered set. |
| ✅ `AutomationNotificationMacro` | `init` reads `automationId`+`autoRaisedId`+`requestId`; `super.init(ids)` then captures the same audit-row set that the patch shrinks. This is the **automation-completion** notification — fewer/different users in the post-execution mail. |
| ✅ `SendNotificationTaskMacro` | Extends `AutomationNotificationMacro`. Used by the workflow's "Send Notification" task on auto-created requests — same audit context, plus `objectIdList` / `userNmeVsObjectId` populated from the (now-filtered) request objects. |
| ✅ `SingleNotifyMacro` | Constructor takes the `DataObject` from `AutomationSingleNotify.getDataObjectForMacro(requestId)` — that DataObject is the audit snapshot, again shrunk by the patch. Per-user mail/SMS recipient lists change. |
| ❌ `MgmtNotificationMacro` (used standalone) | Only the AUTOMATION branch (L177-195) is reached for automation-driven requests; the MANAGEMENT branch (L196-256) that instantiates `MgmtNotificationMacro` directly is taken **only** when `AutomationUtil.getAutomationDetails(requestId)` returns empty — i.e., non-automation flows. CSV-driven automation doesn't reach this branch. Note: the *class* is still reached transitively because `AutomationNotificationMacro extends MgmtNotificationMacro` and calls `super.init(ids)`; the **standalone-instance reach** is what's negative here. |
| ❌ `ScheduleReportNotificationMacro` | Reads `scheduleId` / `auditId` / `fileList` / `emptyReports` — populated by `ReportHandler.triggerTemplateNotification` from scheduled-report generation, a completely independent flow with no CSV-automation input. |

**Why this is not a name-match conclusion:**
- The naive heuristic ("anything with `Notification` + `Macro` in its name is affected") would over-approximate to all 6 macros plus `CustomActionMacro` and the `WFAssigneeMacro*` family (12+ classes), inflating QA scope.
- The actual gate is *whether the macro's init-time data graph intersects the patched data graph*. For this patch, that intersection is **the audit rows of the CSV-driven workflow request** — which is read by exactly the 4 macros above and not by `ScheduleReportNotificationMacro` or `CustomActionMacro`.
- `MgmtNotificationMacro` is the subtle case: as a class it's transitively reached (via inheritance), but as an **instance type instantiated at a dispatch site**, the CSV-automation path never picks it directly. The boundary edge cares about instance type, so it's marked ❌ for direct reach but a patch report should still surface its class for inheritance-chain reasons.

#### Patch 2 — `758cdf37d5...7e22fcaf73.patch` (Issue 13032: reject-path NPE)

The patch adds a null-guard for `fcBulkExecuteFormBean` access inside the workflow reject/cancel branch in `WorkFlowAction.approveRequest` (web/adsm L2518-L2521). Single file, single block, ~6 lines changed.

The macro and its data sources are NOT modified — Layer A/B analysis would report "no macros affected". This is wrong; the FixDetails confirms the QA-visible bug is "rejection notification not delivered". The classification is **Layer C — control-flow-gated dispatch** (§8c2 details).

**Reached macros — Layer-C analysis:**

| Macro | Why reached |
|---|---|
| ✅ `WFNotificationMacro` (gating) | Same enclosing block as the trigger.start() at L2584; patch removes the NPE that previously aborted the block before the trigger ran. Pre-fix: notification silently dropped. Post-fix: notification fires. |
| ❌ all other macros | The patch's enclosing block contains only `WFNotificationMacro` + its trigger. Other macros in the same file (L2601, L3008, L4619) are in different methods or different branches and are not co-resident with the patched lines. |

**Affected macro keys (Layer-C condition 4 lookup):**

| Key | Why | Pre-fix value | Post-fix value |
|---|---|---|---|
| `admp.workflow.notification.macros.workstatus` | Sibling statement `WorkFlow.modifyRequestWorkflow(..., WORKFLOW_STATUS_CANCELED, ...)` at L2486 writes `ADSMRequests.WORKFLOW_STATUS`; WFNotificationMacro.java:1349 reads that column to render `workstatus`. | not rendered (notification dropped) | "Canceled" / "Rejected" — the just-written workflow status label |
| `WFStatus` (numeric) | Same source as `workstatus` — same write at L2486. | not rendered | numeric WORKFLOW_STATUS code |
| every other key the rejection notification template references (`subject`, `description`, `requestor`, `comments`, `reviewer`/`approver`/`executor`, `expire.time`, `requestid`, `request_link_macro`, etc.) | Binary effect — the whole notification was dropped pre-fix and now fires. | not rendered (entire notification dropped) | rendered (template substitutes values from the workflow request) |

The **highest-signal report row** is `workstatus`, because (a) the sibling statement in the same block writes the column that feeds it, and (b) it's the macro key whose **value** changes in the same flow as the patch (other keys merely move from "not rendered" to "rendered same as a cancel-path notification would have rendered"). For QA, listing all of them is correct; for triage, surfacing `workstatus` first is the highest-precision answer.

**Why Layer A/B missed it (per §8c2):**
- Data-flow forward-reach from L2518 (`fcBulkExecuteFormBean = ...`) flows into `templateCategoryID` and `isMgmtModification`, both of which are dead-ends w.r.t. macro state (used only in `WorkFlowUtil.getTemplateActionAttributes` and `attributelist`).
- Macro `init` at L2498 is positioned **before** the patched line — patched line writes nothing the macro reads. Pure data-flow analysis correctly says "macro unaffected".
- The dependency is **control flow**: pre-fix NPE on L2520-L2521 → `try` swallows → flow exits the loop and the outer block → `trigger.start()` at L2584 never reached. Post-fix: NPE eliminated → block runs to completion → notification fires.

This is exactly what Layer C is designed to catch. The §8c2 four-condition check resolves cleanly to `:GATES_DISPATCH (WorkFlowAction.approveRequest → WFNotificationMacro)` with `affected_macro_keys = "workstatus, WFStatus, *"` (the `*` flags the binary-effect catch-all).

### 8f. Resolver implication

Three detection patterns. Patterns A/B emit `:POPULATES_MACRO` (data-flow); Pattern C (defined in §8c2) emits `:GATES_DISPATCH` (control-flow). All three carry the `:NotificationMacro` / `:MacroInit` labels on the destination so backward-reach queries can union them framework-agnostically.

```
Pattern A — interface-implementing macros (covers 5 of 6 classes in §8b)
  ClassOrInterfaceDeclaration where:
    implements (directly or transitively via :IMPLEMENTS chain)
        com.adventnet.sym.adsm.common.server.admin.notification.NotificationMacro
  Find MethodDeclaration where name=="init" AND single param ends with "Hashtable".
  Tag method :MacroInit, owning class :NotificationMacro.
  Stamp the edge with macro_kind = simple-name of the concrete class
    (e.g. "WFNotificationMacro", "AutomationNotificationMacro").

Pattern B — structural macros (covers SingleNotifyMacro)
  ClassOrInterfaceDeclaration whose simple name ends with "NotifyMacro" or
    "NotificationMacro" AND has a constructor taking (DataObject, Long, ...)
    OR a method named "parseMacro*"/"parseSMSMacro*"
    AND does NOT implement NotificationMacro.
  Find each ConstructorDeclaration.
  Tag constructor :MacroInit, owning class :NotificationMacro.
  Stamp the edge with macro_kind = simple-name of the class.
```

**Call-site pattern (informational, used for resolver verification):**

```java
// Pattern A — universal call shape across §8b row 5 "Selected at" call sites:
NotificationMacro macro = new <ConcreteMacro>();   // ObjectCreationExpr
Hashtable macroDetailsMap = new Hashtable();
macroDetailsMap.put("requestId", requestId);
// ... 5-15 more .put() calls
macro.init(macroDetailsMap);                       // MethodCallExpr on same local var

// Pattern B — SingleNotifyMacro is constructed with positional args (no init()):
singleNotifyMacro = new SingleNotifyMacro(
    AutomationSingleNotify.getDataObjectForMacro(requestId),
    requestId, loginId, autoRaisedId, baseTable, baseTableColumn,
    automationId, requestDetails);                  // ObjectCreationExpr
```

The resolver only needs to recognize the macro **class definition** (to label its `init`/ctor as `:MacroInit`) — it does NOT need to match call sites, because the existing `:CALLS` edges from each caller into the labelled method already give backward reach.

### 8g. Why the destination is the override (not the interface / not the dispatcher)

- **Not the interface method** (`NotificationMacro.init`) — same reason as §2b: uniform inheritance collapses all 5 implementations into one indistinguishable hop, defeating per-flow precision.
- **Not the dispatcher** (`MgmtNotificationListener.triggerNotification` / `NotificationTrigger.start`) — already covered by §1's `:SENDS_NOTIFICATION` edge. Macros are downstream **data shape**, not dispatch.
- **Not `parseMacro*` methods** — they're pure renderers reading from the fields `init` already populated. Tagging them would double-emit; the data-ingestion boundary at `init` is sufficient.
- **Not the helper methods** (`getRequestDetails`, `getUserDetails`, `fillPropsFromDetailsTable`, etc.) — same reason as `*Handler.getProfileList` in §1: callees of the entry, not the entry itself.

### 8h. Out-of-scope / honest gaps

- **`CustomActionMacro`** (orchestration) — *not* a notification macro despite the name; it shapes orchestration custom-action result metadata for the audit log, not notification template variables. Excluded from this section. If a future patch reaches orchestration custom-action code, it surfaces under §6 (`:TRIGGERS_ORCHESTRATION`), not here.
- **`WFAssigneeMacro` / `WFAssigneeMacroHandler` / `WFAssigneeMacroUtil` and the `…webclient.workflow.assigneemacro.*Macro` family (13+ classes)** — these resolve workflow **assignee** identities (manager, folder owner, M365 manager, NTFS owner, etc.) for assigning-rule evaluation. They are macros in the assigning-rule sense (placeholder → user-DN resolution), NOT in the notification-template sense. The notification-template macros above read assignee names indirectly via `WFAssigneeMacroHandler` calls inside `WFNotificationMacro.parseMacros`, but the assignee macros themselves don't fill notification placeholders. Out of scope for `:POPULATES_MACRO`.
- **`O365AutoReplyMacro`** — auto-reply message template parsing for M365 mailbox auto-reply; does NOT implement `NotificationMacro` and is invoked from a mailbox-management code path, not a notification dispatcher. Out of scope.
- **`Macro` + `ADMPMacroParser`** in `automation/orchestration/` — orchestration-step macro substitution (replaces `${...}` in orchestration template steps); orthogonal to notification macros. Out of scope.
- **Inheritance-via-super.init transitive reach** — `AutomationNotificationMacro.init` calls `super.init(ids)`, which is `MgmtNotificationMacro.init`. The graph captures this via a `:CALLS` edge from the subclass method to the super method. Backward reach from a patched method to either node is correct; the **macro_kind** stamp on the edge tracks the concrete instance type, not the inherited base.
- **The patch report's "affected macros" row** — should be aggregated from the `:POPULATES_MACRO` edges where the macro's read-set (a property bag of which `ids.get("X")` keys the `init` body reads) intersects with the patched method's write-set (which audit/request tables the patched method or its forward reach writes to). Until that read-set/write-set intersection is implemented, the conservative report is "all 5 interface impls + SingleNotifyMacro are potentially affected if any audit-table-writing method is patched" — which is an over-approximation but a sound one.

---

## Resolver implementation checklist

Each resolver should follow the pattern:

```java
public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
        String fromFqn = null;  // computed lazily
        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            // Pattern match against the destination FQN.method — DO NOT match by name suffix
            if (!isDestinationCall(call, "FQN", "methodName")) continue;
            if (fromFqn == null) fromFqn = ResolverUtils.methodFqn(md, pkg);
            String boundaryId = extractBoundaryConstant(call.getArguments(), N);
            batch.<edgeList>.add(new <EdgeType>(fromFqn, boundaryId));
        }
        // ObjectCreationExpr + chained .start() patterns: walk the local-var binding
        // similar to RequestParamResolver's getParameter→equals chain.
    }
}
```

Critically, the resolver must **NOT** fire on:
- Subclasses or interfaces of the destination (those are invoked BY the dispatcher, not the dispatcher themselves).
- Methods on the destination class other than the listed one (`triggerNotification` only — not `init`, `getProfileList`, etc.).

This keeps false positives low. The `loginId`/`rb` garbage observed in `impact-report-48b24115` was the cost of NOT having this discipline — the previous greedy NotificationResolver matched anything called `*.init()` on a class with "Notification" in its name.

---

## Implementation priority for the patch backlog

1. **NotificationResolver rewrite** — two-pattern matcher for `MgmtNotificationListener.triggerNotification` AND `NotificationTrigger` ctor + `.start()`. Highest QA impact; fixes the current `loginId`/`rb` false positives.
2. **AuditResolver** — `AdminAuditUtil.saveAuditDetails` matcher with `categoryId` constant extraction.
3. **ScheduleResolver** — `SchedulerHandler.createScheduler` matcher. Combined with the existing data-flow `shares-data` heuristic this gives both control- and data-flow async coupling.
4. **PermissionResolver** — `getActionList().contains(<const>)` pattern matcher.
5. **UserScheduleResolver** — `SchedulerInputsUtil.addSchedulerDetails` (one-line addition on top of #3).
6. **OrchestrationResolver** — `OrchestrationTrigger` ctor + `.start()` pattern.
7. **NotificationMacroResolver** — structural detection per §8f. **Patterns A/B (`:POPULATES_MACRO`)**: tag any `init(Hashtable)` on a class implementing `NotificationMacro` as `:MacroInit` (A); tag the `SingleNotifyMacro` constructor as `:MacroInit` (B). Owning class gets `:NotificationMacro` label. Stamp `macro_kind = simple class name` on the edge. Backward reach from a patched method to a `:MacroInit` node tells the impact report *which notification template content* is data-flow-affected. **Pattern C (`:GATES_DISPATCH`)** per §8c2: for each `:SENDS_NOTIFICATION` edge, locate the enclosing block containing both the trigger and the preceding macro-init; for each patched line **between** them that's control-flow-altering (null guard add/remove, try/catch/throw change, guard-expression change), emit `:GATES_DISPATCH` from the patched method to the `:NotificationMacro` class. Stamp `affected_macro_keys` from the status-writer → macro-key lookup table in §8c2 (e.g. `modifyRequestWorkflow` → `workstatus`+`WFStatus`); fall back to `*` (binary catch-all) when no sibling DB write resolves. Orthogonal to §1's `:SENDS_NOTIFICATION` (which only says *that* a notification fires) and to `:POPULATES_MACRO` (which only says *what content* changes).

---

## 9. Unified backward-reach via `:INSTANTIATES` — generic constructor-call edge

### Overview

The `:INSTANTIATES` edge is a **generic constructor-call relationship** emitted by `ClassShapeResolver` for every `new Foo()` and `Foo.getInstance()` call (with a JDK skip-list). It replaces the former notification-specific `:GATES_DISPATCH` edge by unifying all constructor-based backward-reach queries under one edge type.

### Edge variants

| Source | `classFqn` | `targetSimpleName` | `block_start_line` | Purpose |
|--------|-----------|-------------------|-------------------|---------|
| `ClassShapeResolver` | non-null (FQN) | null | absent | Generic constructor detection for all classes |
| `NotificationAuditResolver` | null | non-null (simple name) | present (>0) | §8c2 Layer C dispatch-block annotation |

**MERGE keys:**
- Basic: `(m:Method {fqn})-[:INSTANTIATES]->(c:Class {fqn})` — one edge per (method, class) pair
- Block-annotated: `(m)-[:INSTANTIATES {block_start_line}]->(cls)` — multiple per pair (one per dispatch block)

### Query patterns (generalized)

Three backward-reach patterns, now applicable to ALL boundary domains that use constructor-based dispatch:

**Pattern 1 — Direct backward reach (notification example):**
```cypher
MATCH (cls:Class:NotificationMacro)-[:CONTAINS]->(m:Method {fqn: $changedFqn})
WITH DISTINCT cls
MATCH (instantiator:Method)-[:INSTANTIATES]->(cls)
MATCH (instantiator)-[:SENDS_NOTIFICATION]->(n:NotificationType)
RETURN n.id, collect(DISTINCT instantiator.fqn) AS senders
```

**Pattern 2 — URL backward reach (generic):**
```cypher
MATCH (cls:Class)-[:CONTAINS]->(m:Method {fqn: $changedFqn})
WITH DISTINCT cls
MATCH (instantiator:Method)-[:INSTANTIATES]->(cls)
MATCH (epClass:Class)-[:CONTAINS]->(instantiator)
MATCH (epClass)-[:EXPOSES]->(re:RestEndpoint)
RETURN re.url, count(DISTINCT instantiator.fqn) AS reaching
```
Catches: "utility/helper class modified → its instantiators' entry-point URLs are affected."

**Pattern 3 — One-hop upstream (callers of instantiators):**
```cypher
MATCH (cls:Class:NotificationMacro)-[:CONTAINS]->(m:Method {fqn: $changedFqn})
WITH DISTINCT cls
MATCH (instantiator:Method)-[:INSTANTIATES]->(cls)
MATCH (upstream:Method)-[:CALLS]->(instantiator)
MATCH (upstream)-[:SENDS_NOTIFICATION]->(n:NotificationType)
RETURN n.id, collect(DISTINCT upstream.fqn) AS senders
```

### Layer C hunk-overlap (block-annotated edges only)

For dispatch-block gating detection (§8c2), block-annotated `:INSTANTIATES` edges carry:
- `block_start_line` / `block_end_line` — line range of the dispatch block
- `sibling_methods` — method calls within the block (for macro-key attribution)

Query filters on these properties:
```cypher
MATCH (caller)-[g:INSTANTIATES]->(cls:NotificationMacro)
WHERE g.block_start_line IS NOT NULL
  AND g.block_start_line <= $hunkEnd
  AND g.block_end_line   >= $hunkStart
```

### Applicability to boundary domains

| § | Domain | Pattern 1 | Pattern 2 (URLs) | Notes |
|---|--------|-----------|-------------------|-------|
| 1 | Notifications | ✅ (backward reach to senders) | ✅ | Was `:GATES_DISPATCH`, now `:INSTANTIATES` |
| 2 | Schedules | — | ✅ | Static-method based (no constructors) |
| 3 | User schedules | — | ✅ | Static-method based |
| 4 | Auth/delegation | — | ✅ | Static-method based |
| 5 | Audit | — | ✅ | Static-method based |
| 6 | Orchestration | ✅ (via `OrchestrationTrigger` ctor) | ✅ | Constructor-based dispatch |
| 7 | URLs/REST | — | (self) | URLs are the destination, not intermediate |
| 8 | Macro data-population | ✅ (via macro `:MacroInit`) | ✅ | Constructor-based dispatch |

### Migration from `:GATES_DISPATCH`

- `GatesDispatchEdge` → `InstantiatesEdge` (block-annotated constructor)
- `Neo4jWriter.writeGatesDispatch()` → merged into `writeInstantiates()` (block path)
- `SliceExecutor` queries: `:GATES_DISPATCH` → `:INSTANTIATES` with `WHERE g.block_start_line IS NOT NULL`
- `ExtractionBatch.gatesDispatch` → deprecated; callers emit directly to `batch.instantiates`
- The `drainLeafCollections` caveat (class nodes must exist before edge write) is handled by the writer's `OPTIONAL MATCH (cls:Class {simple_name})` which safely drops phantom edges
