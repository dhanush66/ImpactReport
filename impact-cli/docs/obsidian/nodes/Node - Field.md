# Node - Field

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Field` |
| **Key** | `fqn` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE f.fqn IS UNIQUE` |

## Description

Represents a Java field (instance or static). For `public static final` fields with compile-time constant values, the literal is stored in `constant_value` for the ConstantIndex post-pass (reverse-mapping numeric IDs to symbolic names).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `fqn` | String | `pkg.ClassName.fieldName` |
| `simple_name` | String | Field name |
| `owner_fqn` | String | FQN of containing class |
| `type` | String | Declared type |
| `constant_value` | String | Literal value for static-final fields (empty otherwise) |

## Java Record

```java
public record FieldNode(
    String fqn,
    String simpleName,
    String ownerFqn,
    String type,
    String constantValue   // e.g. "1914" for public static final Long WORKFLOW_REJECT = 1914L
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (f:Field {fqn: row.fqn})
  SET f.simple_name = row.simple, f.owner_fqn = row.owner, f.type = row.type,
      f.constant_value = row.constVal
WITH f, row
MATCH (c:Class {fqn: row.owner})
MERGE (c)-[:CONTAINS]->(f)
```

## Cypher — Query Examples

```cypher
-- Find all constants in a class
MATCH (c:Class {simple_name: "WorkFlowUtil"})-[:CONTAINS]->(f:Field)
WHERE f.constant_value <> ''
RETURN f.simple_name, f.constant_value, f.type

-- Reverse-map a numeric value to symbolic name
MATCH (f:Field)
WHERE f.constant_value = "1914"
RETURN f.simple_name, f.owner_fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - CONTAINS]] | [[Node - Class]] |
| IN | [[Edge - READS]] | [[Node - Method]] |
| IN | [[Edge - WRITES]] | [[Node - Method]] |
