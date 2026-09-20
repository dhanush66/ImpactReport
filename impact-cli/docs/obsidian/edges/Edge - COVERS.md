# Edge - COVERS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:COVERS` |
| **From** | [[Node - TestCase]] |
| **To** | [[Node - Method]], [[Node - Class]], [[Node - RestEndpoint]], [[Node - TaskType]], [[Node - DbTable]], [[Node - MessageConstant]] |
| **Layer** | Testing |

## Description

Links a test case to the graph elements it covers. The target kind determines which label is matched. The `confidence` score indicates how well the test case covers the target.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `confidence` | Float | Coverage confidence (0.0–1.0) |

## Cypher — Create

```cypher
-- Per target kind (Method shown)
UNWIND $rows AS row
MATCH (t:TestCase {id: row.tc})
MATCH (target:Method {fqn: row.key})
MERGE (t)-[r:COVERS]->(target)
  SET r.confidence = row.conf
```

## Cypher — Query

```cypher
-- Find test coverage for a method
MATCH (t:TestCase)-[r:COVERS]->(m:Method {fqn: $methodFqn})
RETURN t.id, t.title, r.confidence ORDER BY r.confidence DESC

-- Find uncovered methods in a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)
WHERE NOT EXISTS { MATCH (tc:TestCase)-[:COVERS]->(m) }
RETURN m.fqn, m.simple_name
```
