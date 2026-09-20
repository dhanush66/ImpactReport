# Node - Package

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Package` |
| **Key** | `name` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE p.name IS UNIQUE` |

## Description

Represents a Java package (e.g., `com.adventnet.sym.adsm.common.server`). Derived automatically from class FQNs during ingest. Contains files and classes.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Dot-separated package name |

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (p:Package {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find all classes in a package
MATCH (p:Package {name: "com.adventnet.sym.adsm.common.server.workflow"})
      -[:CONTAINS]->(c:Class)
RETURN c.fqn, c.simple_name

-- Count classes per package
MATCH (p:Package)-[:CONTAINS]->(c:Class)
RETURN p.name, count(c) AS classCount
ORDER BY classCount DESC LIMIT 20
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - CONTAINS]] | [[Node - File]], [[Node - Class]] |
