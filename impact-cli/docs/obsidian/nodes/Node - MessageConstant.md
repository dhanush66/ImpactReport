# Node - MessageConstant

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:MessageConstant` |
| **Key** | `value` (string) |
| **Layer** | P5 Communication |
| **Constraint** | `REQUIRE m.value IS UNIQUE` |

## Description

Represents a string constant used in JGroups cluster message routing. Methods that send or receive messages with a specific constant key are connected via `:SENDS_MESSAGE` / `:RECEIVES_MESSAGE`.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `value` | String | Message routing key constant value |
| `owner_fqn` | String | FQN of class defining this constant |

## Java Record

```java
public record MessageConstantNode(String value, String ownerFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:MessageConstant {value: row.value})
  ON CREATE SET m.owner_fqn = row.owner
```

## Cypher — Query Examples

```cypher
-- Find sender/receiver pairs for a message
MATCH (sender:Method)-[:SENDS_MESSAGE]->(msg:MessageConstant {value: "DOMAIN_SYNC_COMPLETE"})
MATCH (receiver:Method)-[:RECEIVES_MESSAGE]->(msg)
RETURN sender.fqn AS sender, receiver.fqn AS receiver

-- Find all message types in the system
MATCH (m:MessageConstant)
RETURN m.value, m.owner_fqn
ORDER BY m.value
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - SENDS_MESSAGE]] | [[Node - Method]] |
| IN | [[Edge - RECEIVES_MESSAGE]] | [[Node - Method]] |
| IN | [[Edge - COVERS]] | [[Node - TestCase]] |
