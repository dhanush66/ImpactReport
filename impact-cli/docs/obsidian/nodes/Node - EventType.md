# Node - EventType

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:EventType` |
| **Key** | `fqn` (string) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE e.fqn IS UNIQUE` |

## Description

Represents an application event class (Spring `ApplicationEvent` subclass, Guava EventBus event). Connects publishers and listeners to enable impact analysis across decoupled event-driven boundaries.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `fqn` | String | FQN of the event class |

## Java Record

```java
public record EventTypeNode(String fqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (e:EventType {fqn: row.fqn})
```

## Cypher — Query Examples

```cypher
-- Find publisher/listener pairs
MATCH (pub:Method)-[:PUBLISHES_EVENT]->(e:EventType)
MATCH (sub:Method)-[:LISTENS_FOR]->(e)
RETURN e.fqn, pub.fqn AS publisher, sub.fqn AS listener
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - PUBLISHES_EVENT]] | [[Node - Method]] |
| IN | [[Edge - LISTENS_FOR]] | [[Node - Method]] |
