# Node - Permission

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Permission` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Security |
| **Constraint** | `REQUIRE p.id IS UNIQUE` |

## Description

Represents an access-control permission constant. Initially stored as numeric action IDs, the ConstantIndex post-pass re-keys them to symbolic names from `*ActionConstants`/`*PermissionConstants` fields.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Permission identifier (symbolic after cleanup) |

## Java Record

```java
public record PermissionNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (p:Permission {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find methods requiring a permission
MATCH (m:Method)-[:REQUIRES_PERMISSION]->(p:Permission {id: "WORKFLOW_APPROVE"})
RETURN m.fqn, m.owner_fqn

-- Find all permissions touched by a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:REQUIRES_PERMISSION]->(p:Permission)
RETURN DISTINCT p.id
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - REQUIRES_PERMISSION]] | [[Node - Method]] |
