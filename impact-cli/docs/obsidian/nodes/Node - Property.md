# Node - Property

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Property` |
| **Key** | `key` (string) |
| **Layer** | §4.1 Configuration |
| **Constraint** | `REQUIRE p.key IS UNIQUE` |

## Description

Represents a configuration property key read via `System.getProperty(...)`, `ADSMPersUtil.getSyMParameter(...)`, or `@Value` annotations. Tracks which code depends on runtime configuration.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `key` | String | Property name (e.g., `SHARE_PASSWORD_AFTER_WFTASK_EXECUTION`) |

## Java Record

```java
public record PropertyNode(String key) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (p:Property {key: row.key})
```

## Cypher — Query Examples

```cypher
-- Find methods reading a property
MATCH (m:Method)-[:READS_PROPERTY]->(p:Property {key: "SHARE_PASSWORD_AFTER_WFTASK_EXECUTION"})
RETURN m.fqn, m.owner_fqn

-- Find all properties and their reader count
MATCH (m:Method)-[:READS_PROPERTY]->(p:Property)
RETURN p.key, count(m) AS readers ORDER BY readers DESC
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - READS_PROPERTY]] | [[Node - Method]] |
