# Edge - INJECTS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:INJECTS` |
| **From** | [[Node - Class]] (consumer) |
| **To** | [[Node - Class]] (dependency) |
| **Layer** | §4.1 (N6) |

## Description

Records Spring/CDI dependency injection between classes.

## Edge Properties

None.

## Java Record

```java
public record InjectsEdge(String fromClassFqn, String dependencyClassFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (a:Class {fqn: row.from})
MERGE (b:Class {fqn: row.dep})
MERGE (a)-[:INJECTS]->(b)
```

## Cypher — Query

```cypher
-- Find all dependencies of a class
MATCH (c:Class {fqn: $classFqn})-[:INJECTS]->(dep:Class)
RETURN dep.fqn, dep.simple_name
```
