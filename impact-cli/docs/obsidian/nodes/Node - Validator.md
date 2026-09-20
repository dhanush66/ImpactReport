# Node - Validator

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Validator` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Security |
| **Constraint** | `REQUIRE v.id IS UNIQUE` |

## Description

Represents an input validator instance or class used for data validation before processing.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Validator FQN or constant identifier |

## Java Record

```java
public record ValidatorNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (v:Validator {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find methods using a validator
MATCH (m:Method)-[:VALIDATES_INPUT]->(v:Validator {id: $validatorId})
RETURN m.fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - VALIDATES_INPUT]] | [[Node - Method]] |
