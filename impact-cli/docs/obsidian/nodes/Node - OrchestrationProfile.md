# Node - OrchestrationProfile

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:OrchestrationProfile` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Orchestration |
| **Constraint** | `REQUIRE o.id IS UNIQUE` |

## Description

Represents an orchestration profile/template identifier. Emitted when code creates `new OrchestrationTrigger(...)` and invokes `.start()`. The `actionId` passed to `.setActionId(Long)` becomes the key; `"<unspecified>"` when runtime-computed.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Action ID (symbolic or `<unspecified>`) |

## Java Record

```java
public record OrchestrationProfileNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (o:OrchestrationProfile {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find methods triggering orchestration
MATCH (m:Method)-[:TRIGGERS_ORCHESTRATION]->(o:OrchestrationProfile)
WHERE o.id <> '<unspecified>'
RETURN m.fqn, o.id

-- Find all orchestration triggers in a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:TRIGGERS_ORCHESTRATION]->(o)
RETURN m.simple_name, o.id
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - TRIGGERS_ORCHESTRATION]] | [[Node - Method]] |
