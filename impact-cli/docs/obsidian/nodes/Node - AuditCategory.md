# Node - AuditCategory

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:AuditCategory` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE a.id IS UNIQUE` |

## Description

Represents an audit event category constant. Initially stored as numeric IDs from code, then re-keyed to symbolic names by the ConstantIndex post-pass (matching `*AuditConstants` field values).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Audit category (symbolic name after cleanup, e.g., `WORKFLOW_APPROVED`) |

## Java Record

```java
public record AuditCategoryNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (a:AuditCategory {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find methods writing to an audit category
MATCH (m:Method)-[:WRITES_AUDIT]->(a:AuditCategory {id: "WORKFLOW_APPROVED"})
RETURN m.fqn, m.owner_fqn

-- List all audit categories
MATCH (a:AuditCategory)
RETURN a.id ORDER BY a.id
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - WRITES_AUDIT]] | [[Node - Method]] |
