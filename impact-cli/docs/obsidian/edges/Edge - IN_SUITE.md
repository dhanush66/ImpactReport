# Edge - IN_SUITE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:IN_SUITE` |
| **From** | [[Node - TestCase]] |
| **To** | [[Node - TestSuite]] |
| **Layer** | Testing |

## Description

Groups a test case into a test suite.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MATCH (t:TestCase {id: row.tc})
MATCH (s:TestSuite {name: row.suite})
MERGE (t)-[:IN_SUITE]->(s)
```

## Cypher — Query

```cypher
-- All test cases in a suite
MATCH (t:TestCase)-[:IN_SUITE]->(s:TestSuite {name: $suiteName})
RETURN t.id, t.title, t.area
```
