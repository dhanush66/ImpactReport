# Edge - SINGLETON_OF

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:SINGLETON_OF` |
| **From** | [[Node - Class]] |
| **To** | [[Node - Class]] (self-loop) |
| **Layer** | §4.1 (N5) |

## Description

Marks a class as implementing the Singleton pattern (self-referencing edge).

## Edge Properties

None.

## Java Record

```java
public record SingletonOfEdge(String classFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (c:Class {fqn: row.fqn})
MERGE (c)-[:SINGLETON_OF]->(c)
```

## Cypher — Query

```cypher
MATCH (c:Class)-[:SINGLETON_OF]->(c)
RETURN c.fqn, c.simple_name
```
