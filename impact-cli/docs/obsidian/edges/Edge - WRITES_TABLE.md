# Edge - WRITES_TABLE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:WRITES_TABLE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - DbTable]] |
| **Layer** | P5 Database |

## Description

Records that a method performs SQL INSERT, UPDATE, or DELETE on a table.

## Edge Properties

None.

## Java Record

```java
public record DbTableEdge(String fromMethodFqn, String tableName, boolean write) {}
// write=true → :WRITES_TABLE
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner
MERGE (t:DbTable {name: row.table})
MERGE (m)-[:WRITES_TABLE]->(t)
```

## Cypher — Query

```cypher
-- Find all writers to a table (impact of schema change)
MATCH (m:Method)-[:WRITES_TABLE]->(t:DbTable {name: "ADSMRequestWorkflowDetails"})
RETURN m.fqn, m.owner_fqn
```
