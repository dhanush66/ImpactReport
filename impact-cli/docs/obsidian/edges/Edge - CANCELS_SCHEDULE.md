# Edge - CANCELS_SCHEDULE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:CANCELS_SCHEDULE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - ScheduledTask]] |
| **Layer** | §4.1 Scheduling |

## Description

Records that a method cancels a previously scheduled task (Timer.cancel, ScheduleManager.cancel).

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (s:ScheduledTask {task_class_fqn: row.fqn})
MERGE (m)-[:CANCELS_SCHEDULE]->(s)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:CANCELS_SCHEDULE]->(s:ScheduledTask {task_class_fqn: $fqn})
RETURN m.fqn
```
