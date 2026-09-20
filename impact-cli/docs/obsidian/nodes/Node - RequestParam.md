# Node - RequestParam

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:RequestParam` |
| **Key** | `name` (string) |
| **Layer** | §4.1 Web |
| **Constraint** | `REQUIRE p.name IS UNIQUE` |

## Description

Represents an HTTP/form request parameter the application reads via `request.getParameter("name")`, `@RequestParam`, or `@PathVariable`. The per-method usage and per-comparison value are carried on the `:READS_PARAM` edge properties.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Parameter name string |

## Java Record

```java
public record RequestParamNode(String name) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (p:RequestParam {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find methods reading a parameter with specific values
MATCH (m:Method)-[r:READS_PARAM]->(p:RequestParam {name: "methodToCall"})
WHERE r.value <> ''
RETURN m.fqn, r.value, r.block_start_line, r.block_end_line

-- Find all parameters and their access count
MATCH (m:Method)-[:READS_PARAM]->(p:RequestParam)
RETURN p.name, count(DISTINCT m) AS accessors ORDER BY accessors DESC
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - READS_PARAM]] | [[Node - Method]] |
