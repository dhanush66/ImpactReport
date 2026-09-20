# Edge - READS_TABLE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:READS_TABLE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - DbTable]] |
| **Layer** | P5 Database |

## Description

Records that a method performs a SQL SELECT (read) from a database table. Detected from Criteria API patterns like `Table.getTable("TableName")` in SelectQuery contexts.

## Edge Properties

None.

## Java Record

```java
public record DbTableEdge(String fromMethodFqn, String tableName, boolean write) {}
// write=false → :READS_TABLE
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner
MERGE (t:DbTable {name: row.table})
MERGE (m)-[:READS_TABLE]->(t)
```

## Cypher — Query

```cypher
-- Find all readers of a table
MATCH (m:Method)-[:READS_TABLE]->(t:DbTable {name: "ADSMRequests"})
RETURN m.fqn, m.owner_fqn

-- Find tables read by a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:READS_TABLE]->(t:DbTable)
RETURN DISTINCT t.name
```

## ADMP Code Example

```java
// In WFNotificationMacro.getRequestDetails():
SelectQuery query = new SelectQueryImpl(Table.getTable("ADSMRequests"));  // READS_TABLE → ADSMRequests
query.addJoin(new Join("ADSMRequests", "ADSMRequestDetails", ...));      // READS_TABLE → ADSMRequestDetails
```
