# Edge - HAS_COLUMN

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:HAS_COLUMN` |
| **From** | [[Node - DbTable]] |
| **To** | [[Node - DbColumn]] |
| **Layer** | P5 Database |

## Description

Structural containment edge linking a table to its columns. Populated from data-dictionary XML schema definitions.

## Edge Properties

None.

## Java Record

```java
public record HasColumnEdge(String tableName, String columnName) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (t:DbTable {name: row.table})
MERGE (c:DbColumn {table: row.table, name: row.col})
MERGE (t)-[:HAS_COLUMN]->(c)
```

## Cypher — Query

```cypher
MATCH (t:DbTable {name: "ADSMUserGeneralDetails"})-[:HAS_COLUMN]->(col:DbColumn)
RETURN col.name, col.data_type, col.nullable, col.pk_like
ORDER BY col.name
```
