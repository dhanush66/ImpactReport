# Edge - TRANSITIONS_STATE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:TRANSITIONS_STATE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - State]] |
| **Layer** | §4.1 Behavioral (M1) |

## Description

Records that a method transitions an entity to a new state (state-machine pattern). The `from_state` may be empty when the source state isn't statically extractable.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `from_state` | String | Source state (empty if unknown) |

## Java Record

```java
public record TransitionsStateEdge(String fromMethodFqn, String entity, String fromState, String toState) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (s:State {entity: row.entity, to: row.to})
MERGE (m)-[r:TRANSITIONS_STATE]->(s)
  ON CREATE SET r.from_state = row.fromState
```

## Cypher — Query

```cypher
-- Find all methods that transition to REJECTED
MATCH (m:Method)-[r:TRANSITIONS_STATE]->(s:State {entity: "WorkflowRequest", to: "REJECTED"})
RETURN m.fqn, r.from_state

-- State machine visualization
MATCH (m:Method)-[r:TRANSITIONS_STATE]->(s:State)
WHERE s.entity = "WorkflowRequest"
RETURN r.from_state AS from, s.to AS to, m.fqn AS via
```
