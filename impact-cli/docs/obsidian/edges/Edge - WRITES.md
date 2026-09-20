# Edge - WRITES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:WRITES` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Field]] |
| **Layer** | Core |

## Description

Records a field write/assignment from within a method body.

## Edge Properties

None.

## Java Record

```java
public record FieldAccessEdge(String fromMethodFqn, String toFieldFqn, boolean write) {}
// write=true → :WRITES edge
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
MERGE (f:Field  {fqn: row.to})
MERGE (m)-[:WRITES]->(f)
```

## Cypher — Query

```cypher
-- Find all methods that write a field
MATCH (m:Method)-[:WRITES]->(f:Field {fqn: $fieldFqn})
RETURN m.fqn, m.owner_fqn
```
