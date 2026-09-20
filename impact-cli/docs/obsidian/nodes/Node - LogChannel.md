# Node - LogChannel

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:LogChannel` |
| **Key** | `name` (string) |
| **Layer** | §4.1 Observability |
| **Constraint** | `REQUIRE l.name IS UNIQUE` |

## Description

Represents a named logger channel (only non-default loggers are captured). Tracks which code writes to specific log destinations.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Logger channel name |

## Java Record

```java
public record LogChannelNode(String name) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (l:LogChannel {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find methods writing to a log channel
MATCH (m:Method)-[:WRITES_LOG]->(l:LogChannel {name: $channelName})
RETURN m.fqn, m.owner_fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - WRITES_LOG]] | [[Node - Method]] |
