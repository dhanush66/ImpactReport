# Edge - OVERRIDES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:OVERRIDES` |
| **From** | [[Node - Method]] (subclass) |
| **To** | [[Node - Method]] (superclass) |
| **Layer** | Core |

## Description

Records method override relationships. The child method overrides the parent method (same signature in a subclass).

## Edge Properties

None.

## Java Record

```java
public record OverridesEdge(String fromMethodFqn, String toMethodFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (a:Method {fqn: row.from})
MERGE (b:Method {fqn: row.to})
MERGE (a)-[:OVERRIDES]->(b)
```

## Cypher — Query

```cypher
-- Find what a method overrides
MATCH (m:Method {fqn: $methodFqn})-[:OVERRIDES]->(parent:Method)
RETURN parent.fqn, parent.owner_fqn

-- Find all overriders of a method
MATCH (child:Method)-[:OVERRIDES]->(m:Method {fqn: $methodFqn})
RETURN child.fqn, child.owner_fqn
```
