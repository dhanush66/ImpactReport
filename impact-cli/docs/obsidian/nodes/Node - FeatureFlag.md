# Node - FeatureFlag

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:FeatureFlag` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Configuration |
| **Constraint** | `REQUIRE f.id IS UNIQUE` |

## Description

Represents a boolean feature gate / toggle that conditionally enables or disables functionality at runtime.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Feature flag identifier |

## Java Record

```java
public record FeatureFlagNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (f:FeatureFlag {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find code gated by a flag
MATCH (m:Method)-[:GATED_BY]->(f:FeatureFlag {id: $flagId})
RETURN m.fqn, m.owner_fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - GATED_BY]] | [[Node - Method]] |
