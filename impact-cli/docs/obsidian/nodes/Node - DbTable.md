# Node - DbTable

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:DbTable` |
| **Key** | `name` (string) |
| **Layer** | P5 Database |
| **Constraint** | `REQUIRE t.name IS UNIQUE` |

## Description

Represents a SQL database table. Created from table names found in data-dictionary XML, SQL queries in source code (Criteria API's `Table.getTable("...")` pattern), and schema definition files.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Table name (e.g., `ADSMRequests`, `ADSMUserGeneralDetails`) |

## Java Record

```java
public record DbTableNode(String name) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (t:DbTable {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find all methods that write to a table
MATCH (m:Method)-[:WRITES_TABLE]->(t:DbTable {name: "ADSMRequests"})
RETURN m.fqn, m.owner_fqn

-- Find columns of a table
MATCH (t:DbTable {name: "ADSMUserGeneralDetails"})-[:HAS_COLUMN]->(c:DbColumn)
RETURN c.name, c.data_type, c.nullable

-- Find tables both read and written by same method
MATCH (m:Method)-[:READS_TABLE]->(tr:DbTable)
MATCH (m)-[:WRITES_TABLE]->(tw:DbTable)
RETURN m.fqn, collect(DISTINCT tr.name) AS reads, collect(DISTINCT tw.name) AS writes
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - READS_TABLE]] | [[Node - Method]] |
| IN | [[Edge - WRITES_TABLE]] | [[Node - Method]] |
| OUT | [[Edge - HAS_COLUMN]] | [[Node - DbColumn]] |
| IN | [[Edge - COVERS]] | [[Node - TestCase]] |
