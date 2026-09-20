# Node - ScheduledTask

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:ScheduledTask` |
| **Key** | `task_class_fqn` (string) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE s.task_class_fqn IS UNIQUE` |

## Description

Represents a class scheduled for background execution via Timer, ScheduleManager, Quartz, or ExecutorService. Used as target for both system-level `:SCHEDULES` edges and user-configured `:USER_SCHEDULES` edges.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `task_class_fqn` | String | FQN of the Runnable/Task class |

## Java Record

```java
public record ScheduledTaskNode(String taskClassFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (s:ScheduledTask {task_class_fqn: row.fqn})
```

## Cypher — Query Examples

```cypher
-- Find who schedules a task
MATCH (m:Method)-[r:SCHEDULES]->(s:ScheduledTask {task_class_fqn: $fqn})
RETURN m.fqn, r.delay_millis

-- Find tasks that can be both scheduled and cancelled
MATCH (m1:Method)-[:SCHEDULES]->(s:ScheduledTask)
MATCH (m2:Method)-[:CANCELS_SCHEDULE]->(s)
RETURN s.task_class_fqn, m1.fqn AS scheduler, m2.fqn AS canceller
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - SCHEDULES]] | [[Node - Method]] |
| IN | [[Edge - CANCELS_SCHEDULE]] | [[Node - Method]] |
| IN | [[Edge - USER_SCHEDULES]] | [[Node - Method]] |
