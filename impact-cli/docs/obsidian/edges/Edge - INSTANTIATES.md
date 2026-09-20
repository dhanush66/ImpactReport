# Edge - INSTANTIATES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:INSTANTIATES` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Class]] |
| **Layer** | §4.1 (N4) |

## Description

Records that a method creates an instance of a class (via `new`, `getInstance()`, or factory pattern).

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `via` | String | `"new"`, `"getInstance"`, `"factory"` |

## Java Record

```java
public record InstantiatesEdge(String fromMethodFqn, String classFqn, String via) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (c:Class {fqn: row.fqn})
MERGE (m)-[r:INSTANTIATES]->(c)
  ON CREATE SET r.via = row.via
```

## Cypher — Query

```cypher
-- Find all instantiators of a class
MATCH (m:Method)-[r:INSTANTIATES]->(c:Class {simple_name: "WFNotificationMacro"})
RETURN m.fqn, r.via
```
