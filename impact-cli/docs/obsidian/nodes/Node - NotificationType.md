# Node - NotificationType

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:NotificationType` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE n.id IS UNIQUE` |

## Description

Represents a notification kind constant (e.g., `WF_REQUEST_REJECTED`, `WF_EXECUTION_SUCCESS`). Emitted when a method calls `triggerNotification(...)` or `NotificationTrigger.start()` with an identifiable type constant.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Notification type constant value |

## Java Record

```java
public record NotificationTypeNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (n:NotificationType {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find which methods send a notification type
MATCH (m:Method)-[:SENDS_NOTIFICATION]->(n:NotificationType {id: "WF_REQUEST_REJECTED"})
RETURN m.fqn, m.owner_fqn

-- Find all notification types in the system
MATCH (n:NotificationType)
RETURN n.id ORDER BY n.id

-- Trace notification from trigger to macro
MATCH (m:Method)-[:SENDS_NOTIFICATION]->(n:NotificationType)
MATCH (m)-[:GATES_DISPATCH]->(macro:Class)
RETURN m.fqn, n.id, macro.simple_name
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - SENDS_NOTIFICATION]] | [[Node - Method]] |
