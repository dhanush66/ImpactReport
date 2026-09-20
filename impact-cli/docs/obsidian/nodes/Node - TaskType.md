# Node - TaskType

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:TaskType` |
| **Key** | `id` (string) |
| **Layer** | P4 Framework |
| **Constraint** | `REQUIRE t.id IS UNIQUE` |

## Description

Represents a management task type string keyed in the ManagementTaskRegistry (e.g., `"UserCreation"`, `"PasswordReset"`, `"GroupCreation"`). Task handlers register against these IDs.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Task type identifier from registry |

## Java Record

```java
public record TaskTypeNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (t:TaskType {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find handler class for a task type
MATCH (c:Class)-[:HANDLES]->(t:TaskType {id: "UserCreation"})
RETURN c.fqn, c.simple_name

-- Find dispatchable methods for a task type
MATCH (c:Class)-[:HANDLES]->(t:TaskType {id: $taskId})
MATCH (c)-[:CONTAINS]->(m:Method {simple_name: "execute"})
RETURN m.fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - HANDLES]] | [[Node - Class]] |
| IN | [[Edge - COVERS]] | [[Node - TestCase]] |
