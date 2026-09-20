# Nodes Index

All Neo4j node labels created by **impact-cli** during ingest and test-gen phases.

## Core Source-Code Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 1 | `:Repo` | `id` | Repository identifier (project root) | [[Node - Repo]] |
| 2 | `:Commit` | `sha` | Git commit snapshot | [[Node - Commit]] |
| 3 | `:Package` | `name` | Java package (dot-separated) | [[Node - Package]] |
| 4 | `:File` | `path` + `commit_sha` | Source file within a commit | [[Node - File]] |
| 5 | `:Class` | `fqn` | Java class or interface | [[Node - Class]] |
| 6 | `:Method` | `fqn` | Method or constructor | [[Node - Method]] |
| 7 | `:Field` | `fqn` | Field (instance or static) | [[Node - Field]] |

## P4 Framework Boundary Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 8 | `:TaskType` | `id` | Management task type (registry key) | [[Node - TaskType]] |
| 9 | `:RestEndpoint` | `url` | REST/HTTP API URL | [[Node - RestEndpoint]] |

## P5 Database & Script Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 10 | `:DbTable` | `name` | SQL database table | [[Node - DbTable]] |
| 11 | `:DbColumn` | `table` + `name` | Column on a specific table | [[Node - DbColumn]] |
| 12 | `:PsScript` | `name` | PowerShell script filename | [[Node - PsScript]] |
| 13 | `:MessageConstant` | `value` | JGroups message routing key | [[Node - MessageConstant]] |

## UI / Frontend Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 14 | `:HtmlPage` | `filename` | Admin/Server HTML page | [[Node - HtmlPage]] |
| 15 | `:JsFile` | `path` | Ember JS source file | [[Node - JsFile]] |
| 16 | `:CsFile` | `path` | C# agent/client file | [[Node - CsFile]] |
| 17 | `:HbsTemplate` | `path` | Handlebars template file | [[Node - HbsTemplate]] |

## §4.1 Behavioral Boundary Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 18 | `:NotificationType` | `id` | Notification kind constant | [[Node - NotificationType]] |
| 19 | `:EmailTemplate` | `id` | Email template identifier | [[Node - EmailTemplate]] |
| 20 | `:AuditCategory` | `id` | Audit event category | [[Node - AuditCategory]] |
| 21 | `:ScheduledTask` | `task_class_fqn` | Scheduled background task | [[Node - ScheduledTask]] |
| 22 | `:EventType` | `fqn` | Application event class | [[Node - EventType]] |
| 23 | `:Property` | `key` | Configuration property key | [[Node - Property]] |
| 24 | `:FeatureFlag` | `id` | Boolean feature gate | [[Node - FeatureFlag]] |
| 25 | `:Permission` | `id` | Access control permission | [[Node - Permission]] |
| 26 | `:Validator` | `id` | Input validator | [[Node - Validator]] |
| 27 | `:ExternalSystem` | `id` | External service (SharePoint, Graph…) | [[Node - ExternalSystem]] |
| 28 | `:LogChannel` | `name` | Named logger channel | [[Node - LogChannel]] |
| 29 | `:State` | `entity` + `to` | State-machine end-state | [[Node - State]] |
| 30 | `:RequestParam` | `name` | HTTP request parameter | [[Node - RequestParam]] |
| 31 | `:OrchestrationProfile` | `id` | Orchestration trigger profile | [[Node - OrchestrationProfile]] |

## Testing Nodes

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 32 | `:TestCase` | `id` | Generated test case | [[Node - TestCase]] |
| 33 | `:TestSuite` | `name` | Test suite grouping | [[Node - TestSuite]] |

## Special Nodes (Web App)

| # | Node Label | Key Property | Description | Details |
|---|-----------|-------------|-------------|---------|
| 34 | `:AppUser` | `username` | Application user (P9.6) | [[Node - AppUser]] |

## Sub-labels (applied dynamically)

Some nodes receive additional labels based on their role:

- `:Class` → `:Interface`, `:Servlet`, `:Scheduler`, `:TaskHandler`, `:Job`, `:NotificationMacro`
- `:Method` → `:EntryPoint`, `:Constructor`
- `:JsFile` → `:JsRoute`, `:JsModel`, `:JsController`, `:JsComponent`, `:JsService`, `:JsAdapter`, `:JsHelper`
- `:CsFile` → `:CsManagement`, `:CsReports`, `:CsAudit`, `:CsClient`, `:CsCommon`, `:CsCore`
- `:HbsTemplate` → `:HbsComponentTemplate`, `:HbsRouteTemplate`
