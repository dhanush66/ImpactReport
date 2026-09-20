# Edge - HANDLES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:HANDLES` |
| **From** | [[Node - Class]] (TaskHandler) |
| **To** | [[Node - TaskType]] |
| **Layer** | P4 Framework |

## Description

Connects a TaskHandler class to the task type it handles. Detected from ManagementTaskRegistry entries where a class is registered against a task-type key.

## Edge Properties

None.

## Java Record

```java
public record HandlesEdge(String handlerClassFqn, String taskTypeId) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (c:Class {fqn: row.class_fqn})
MERGE (t:TaskType {id: row.task_id})
MERGE (c)-[:HANDLES]->(t)
```

## Cypher — Query

```cypher
-- Find handler for a task
MATCH (c:Class)-[:HANDLES]->(t:TaskType {id: "UserCreation"})
RETURN c.fqn, c.simple_name

-- List all task-type registrations
MATCH (c:Class)-[:HANDLES]->(t:TaskType)
RETURN t.id, c.simple_name ORDER BY t.id
```
