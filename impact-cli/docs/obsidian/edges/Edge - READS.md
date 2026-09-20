# Edge - READS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:READS` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Field]] |
| **Layer** | Core |

## Description

Records a field read access from within a method body.

## Edge Properties

None.

## Java Record

```java
public record FieldAccessEdge(String fromMethodFqn, String toFieldFqn, boolean write) {}
// write=false → :READS edge
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
MERGE (f:Field  {fqn: row.to})
MERGE (m)-[:READS]->(f)
```

## Cypher — Query

```cypher
-- Find all fields read by a method
MATCH (m:Method {fqn: $methodFqn})-[:READS]->(f:Field)
RETURN f.fqn, f.simple_name, f.type

-- Find all readers of a field
MATCH (m:Method)-[:READS]->(f:Field {fqn: $fieldFqn})
RETURN m.fqn, m.owner_fqn
```
