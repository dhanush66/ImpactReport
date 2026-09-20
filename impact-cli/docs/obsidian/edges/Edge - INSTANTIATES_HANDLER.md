# Edge - INSTANTIATES_HANDLER

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:INSTANTIATES_HANDLER` |
| **From** | [[Node - Method]] (coordinator) |
| **To** | [[Node - Class]] (handler) |
| **Layer** | §4.1 Orchestration |

## Description

Subset of `:INSTANTIATES` reserved for registry/coordinator method bodies that construct task-handler classes. Used by the TaskRegistry-style orchestration view.

## Edge Properties

None.

## Java Record

```java
public record InstantiatesHandlerEdge(String fromMethodFqn, String handlerClassFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (c:Class {fqn: row.fqn})
MERGE (m)-[:INSTANTIATES_HANDLER]->(c)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:INSTANTIATES_HANDLER]->(c:Class)
RETURN m.fqn, c.fqn, c.simple_name
```
