# Node - TestCase

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:TestCase` |
| **Key** | `id` (string) |
| **Layer** | Testing |
| **Constraint** | `REQUIRE t.id IS UNIQUE` |

## Description

Represents a generated test case produced by the test-gen phase. Links to nodes it covers (methods, classes, endpoints, tables) via `:COVERS` edges and to suites via `:IN_SUITE`.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Unique test case identifier |
| `title` | String | Test case title |
| `area` | String | Functional area |
| `steps` | String | Test steps description |
| `expected` | String | Expected result |
| `source` | String | Source/origin of the test case |

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (t:TestCase {id: row.id})
  SET t.title = row.title, t.area = row.area,
      t.steps = row.steps, t.expected = row.expected, t.source = row.source
```

## Cypher — Query Examples

```cypher
-- Find test cases covering a method
MATCH (t:TestCase)-[:COVERS]->(m:Method {fqn: $methodFqn})
RETURN t.id, t.title, t.area

-- Find uncovered methods in a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)
WHERE NOT exists((any:TestCase)-[:COVERS]->(m))
RETURN m.fqn, m.simple_name
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - IN_SUITE]] | [[Node - TestSuite]] |
| OUT | [[Edge - COVERS]] | [[Node - Method]], [[Node - Class]], [[Node - RestEndpoint]], [[Node - TaskType]], [[Node - DbTable]], [[Node - MessageConstant]] |
