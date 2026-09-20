# Edges Index

All Neo4j relationship types created by **impact-cli**.

## Core Structural Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 1 | `:HAS_SNAPSHOT` | Repo → Commit | Repo owns a commit snapshot | [[Edge - HAS_SNAPSHOT]] |
| 2 | `:IN_COMMIT` | File → Commit | File belongs to commit | [[Edge - IN_COMMIT]] |
| 3 | `:CONTAINS` | Package → File/Class; Class → Method/Field; File → Class | Containment hierarchy | [[Edge - CONTAINS]] |
| 4 | `:EXTENDS` | Class → Class | Class inheritance | [[Edge - EXTENDS]] |
| 5 | `:IMPLEMENTS` | Class → Class(Interface) | Interface implementation | [[Edge - IMPLEMENTS]] |
| 6 | `:OVERRIDES` | Method → Method | Method override relationship | [[Edge - OVERRIDES]] |
| 7 | `:CALLS` | Method → Method | Direct/virtual method call | [[Edge - CALLS]] |
| 8 | `:READS` | Method → Field | Field read access | [[Edge - READS]] |
| 9 | `:WRITES` | Method → Field | Field write access | [[Edge - WRITES]] |

## P4 Framework Boundary Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 10 | `:HANDLES` | Class → TaskType | TaskHandler handles a task type | [[Edge - HANDLES]] |
| 11 | `:DISPATCHES_TO` | Method → Method | Cross-thread/registry dispatch | [[Edge - DISPATCHES_TO]] |
| 12 | `:EXPOSES` | Class → RestEndpoint | Servlet exposes URL | [[Edge - EXPOSES]] |

## P5 Database & Script Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 13 | `:READS_TABLE` | Method → DbTable | SQL SELECT from table | [[Edge - READS_TABLE]] |
| 14 | `:WRITES_TABLE` | Method → DbTable | SQL INSERT/UPDATE/DELETE on table | [[Edge - WRITES_TABLE]] |
| 15 | `:HAS_COLUMN` | DbTable → DbColumn | Table contains column | [[Edge - HAS_COLUMN]] |
| 16 | `:INVOKES_SCRIPT` | Method/CsFile → PsScript | Calls a .ps1 script | [[Edge - INVOKES_SCRIPT]] |
| 17 | `:SENDS_MESSAGE` | Method → MessageConstant | Sends JGroups message | [[Edge - SENDS_MESSAGE]] |
| 18 | `:RECEIVES_MESSAGE` | Method → MessageConstant | Receives JGroups message | [[Edge - RECEIVES_MESSAGE]] |

## UI / Frontend Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 19 | `:REFERENCES` | HtmlPage → RestEndpoint | HTML page embeds URL | [[Edge - REFERENCES]] |
| 20 | `:CALLS_API` | JsFile/CsFile → RestEndpoint | JS/C# calls a REST URL | [[Edge - CALLS_API]] |
| 21 | `:USES_COMPONENT` | HbsTemplate → JsFile | Template renders component | [[Edge - USES_COMPONENT]] |
| 22 | `:IMPORTS` | JsFile → JsFile | ES module import | [[Edge - IMPORTS]] |
| 23 | `:RENDERS_TEMPLATE` | JsFile → HbsTemplate | Component paired with template | [[Edge - RENDERS_TEMPLATE]] |

## §4.1 Notification & Communication Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 24 | `:SENDS_NOTIFICATION` | Method → NotificationType | Triggers notification | [[Edge - SENDS_NOTIFICATION]] |
| 25 | `:SENDS_EMAIL` | Method → EmailTemplate | Sends email via template | [[Edge - SENDS_EMAIL]] |
| 26 | `:WRITES_AUDIT` | Method → AuditCategory | Writes audit event | [[Edge - WRITES_AUDIT]] |

## §4.1 Scheduling & Events Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 27 | `:SCHEDULES` | Method → ScheduledTask | System-level schedule creation | [[Edge - SCHEDULES]] |
| 28 | `:CANCELS_SCHEDULE` | Method → ScheduledTask | Cancels a scheduled task | [[Edge - CANCELS_SCHEDULE]] |
| 29 | `:USER_SCHEDULES` | Method → ScheduledTask | User-configured schedule input | [[Edge - USER_SCHEDULES]] |
| 30 | `:PUBLISHES_EVENT` | Method → EventType | Publishes application event | [[Edge - PUBLISHES_EVENT]] |
| 31 | `:LISTENS_FOR` | Method → EventType | Listens for application event | [[Edge - LISTENS_FOR]] |

## §4.1 Instantiation & DI Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 32 | `:INSTANTIATES_HANDLER` | Method → Class | Creates task-handler instance | [[Edge - INSTANTIATES_HANDLER]] |
| 33 | `:INSTANTIATES` | Method → Class | Creates class instance | [[Edge - INSTANTIATES]] |
| 34 | `:SINGLETON_OF` | Class → Class (self) | Singleton pattern marker | [[Edge - SINGLETON_OF]] |
| 35 | `:INJECTS` | Class → Class | Spring/CDI dependency injection | [[Edge - INJECTS]] |

## §4.1 Configuration & Security Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 36 | `:READS_PROPERTY` | Method → Property | Reads config property | [[Edge - READS_PROPERTY]] |
| 37 | `:GATED_BY` | Method → FeatureFlag | Feature-flag gated code | [[Edge - GATED_BY]] |
| 38 | `:REQUIRES_PERMISSION` | Method → Permission | Permission check | [[Edge - REQUIRES_PERMISSION]] |
| 39 | `:VALIDATES_INPUT` | Method → Validator | Input validation | [[Edge - VALIDATES_INPUT]] |
| 40 | `:READS_PARAM` | Method → RequestParam | Reads HTTP parameter | [[Edge - READS_PARAM]] |

## §4.1 External & Logging Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 41 | `:CALLS_EXTERNAL` | Method → ExternalSystem | Calls external service | [[Edge - CALLS_EXTERNAL]] |
| 42 | `:WRITES_LOG` | Method → LogChannel | Writes to named logger | [[Edge - WRITES_LOG]] |

## §4.1 State & Orchestration Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 43 | `:TRANSITIONS_STATE` | Method → State | State machine transition | [[Edge - TRANSITIONS_STATE]] |
| 44 | `:TRIGGERS_ORCHESTRATION` | Method → OrchestrationProfile | Starts orchestration | [[Edge - TRIGGERS_ORCHESTRATION]] |

## §4.1 Notification Macro Edges (Layer C/D)

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 45 | `:GATES_DISPATCH` | Method → Class(NotificationMacro) | Dispatch block gating notification | [[Edge - GATES_DISPATCH]] |
| 46 | `:HANDLES_ATTRIBUTE` | Method → Class(NotificationMacro) | Method handles LDAP attribute macro | [[Edge - HANDLES_ATTRIBUTE]] |

## Testing Edges

| # | Relationship | From → To | Description | Details |
|---|-------------|-----------|-------------|---------|
| 47 | `:IN_SUITE` | TestCase → TestSuite | Test belongs to suite | [[Edge - IN_SUITE]] |
| 48 | `:COVERS` | TestCase → Method/Class/RestEndpoint/TaskType/DbTable/MessageConstant | Test covers a target | [[Edge - COVERS]] |
