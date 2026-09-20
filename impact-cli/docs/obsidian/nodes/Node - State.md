# Node - State

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:State` |
| **Key** | `entity` + `to` (composite) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE (s.entity, s.to) IS UNIQUE` |

## Description

Represents a state-machine end-state. Captures the target state a method transitions an entity into (e.g., WorkflowRequest → REJECTED, WorkflowRequest → APPROVED).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `entity` | String | Entity being transitioned (e.g., `WorkflowRequest`) |
| `to` | String | Target state name (e.g., `REJECTED`) |

## Java Record

```java
public record StateNode(String entity, String to) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (s:State {entity: row.entity, to: row.to})
```

## Cypher — Query Examples

```cypher
-- Find methods that transition to REJECTED
MATCH (m:Method)-[r:TRANSITIONS_STATE]->(s:State {entity: "WorkflowRequest", to: "REJECTED"})
RETURN m.fqn, r.from_state

-- Find all possible states for an entity
MATCH (s:State {entity: "WorkflowRequest"})
RETURN s.to ORDER BY s.to
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - TRANSITIONS_STATE]] | [[Node - Method]] |
