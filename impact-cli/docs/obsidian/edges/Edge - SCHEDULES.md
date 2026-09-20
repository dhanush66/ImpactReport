# Edge - SCHEDULES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:SCHEDULES` |
| **From** | [[Node - Method]] |
| **To** | [[Node - ScheduledTask]] |
| **Layer** | §4.1 Scheduling |

## Description

Records system-level task scheduling (Timer.schedule, SchedulerHandler.createScheduler, Quartz). Distinct from `:USER_SCHEDULES` which is user-configured.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `delay_millis` | Long | Scheduling delay (-1 if not extractable) |

## Java Record

```java
public record SchedulesEdge(String fromMethodFqn, String taskClassFqn, long delayMillis) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (s:ScheduledTask {task_class_fqn: row.fqn})
MERGE (m)-[r:SCHEDULES]->(s)
  ON CREATE SET r.delay_millis = row.delay
```

## Cypher — Query

```cypher
-- Find all schedulers and their delays
MATCH (m:Method)-[r:SCHEDULES]->(s:ScheduledTask)
RETURN m.fqn, s.task_class_fqn, r.delay_millis
```
