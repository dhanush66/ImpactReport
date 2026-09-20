# Node - DbColumn

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:DbColumn` |
| **Key** | `table` + `name` (composite) |
| **Layer** | P5 Database |
| **Constraint** | `REQUIRE (c.table, c.name) IS UNIQUE` |

## Description

Represents a column on a specific database table. Metadata (data type, nullability, primary-key-like status) is extracted from schema XML definitions (`data-dictionary.xml`).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `table` | String | Parent table name |
| `name` | String | Column name |
| `data_type` | String | SQL type (e.g., `BIGINT`, `NCHAR`, `INTEGER`) |
| `max_size` | Integer | Max field size (-1 if unspecified) |
| `nullable` | Boolean | Whether column allows NULL |
| `pk_like` | Boolean | True when named in a uniquevalue-generation block |

## Java Record

```java
public record DbColumnNode(
    String table,
    String name,
    String dataType,
    Integer maxSize,
    boolean nullable,
    boolean pkLike
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (col:DbColumn {table: row.table, name: row.name})
  SET col.data_type = row.dataType,
      col.max_size  = row.maxSize,
      col.nullable  = row.nullable,
      col.pk_like   = row.pkLike
```

## Cypher — Query Examples

```cypher
-- Find all primary-key-like columns
MATCH (col:DbColumn)
WHERE col.pk_like = true
RETURN col.table, col.name, col.data_type

-- Find columns of a specific table
MATCH (t:DbTable {name: "ADSMRequests"})-[:HAS_COLUMN]->(col:DbColumn)
RETURN col.name, col.data_type, col.nullable
ORDER BY col.name
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - HAS_COLUMN]] | [[Node - DbTable]] |
