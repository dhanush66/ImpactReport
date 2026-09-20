# Edge - USER_SCHEDULES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:USER_SCHEDULES` |
| **From** | [[Node - Method]] |
| **To** | [[Node - ScheduledTask]] |
| **Layer** | §4.1 Scheduling (D6) |

## Description

Records user-configured schedule creation via `SchedulerInputsUtil.addSchedulerDetails(...)` — the ADMP API that writes user-input schedule config into `ADSMSchedulerInputDetails`. Distinct from system-level `:SCHEDULES`.

## Edge Properties

None.

## Java Record

```java
public record UserSchedulesEdge(String fromMethodFqn, String scheduleId) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (t:ScheduledTask {task_class_fqn: row.id})
MERGE (m)-[:USER_SCHEDULES]->(t)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:USER_SCHEDULES]->(s:ScheduledTask)
RETURN m.fqn, s.task_class_fqn
```
