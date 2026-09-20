# Node - TestSuite

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:TestSuite` |
| **Key** | `name` (string) |
| **Layer** | Testing |
| **Constraint** | `REQUIRE s.name IS UNIQUE` |

## Description

Groups related test cases into a suite for reporting and organization.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Suite name |

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (s:TestSuite {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find all test cases in a suite
MATCH (t:TestCase)-[:IN_SUITE]->(s:TestSuite {name: $suiteName})
RETURN t.id, t.title

-- Count tests per suite
MATCH (t:TestCase)-[:IN_SUITE]->(s:TestSuite)
RETURN s.name, count(t) AS testCount ORDER BY testCount DESC
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - IN_SUITE]] | [[Node - TestCase]] |
