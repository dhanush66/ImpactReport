# Edge - LISTENS_FOR

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:LISTENS_FOR` |
| **From** | [[Node - Method]] |
| **To** | [[Node - EventType]] |
| **Layer** | §4.1 Events |

## Description

Records that a method is an event listener/subscriber (Spring `@EventListener` or Guava `@Subscribe`).

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (e:EventType {fqn: row.fqn})
MERGE (m)-[:LISTENS_FOR]->(e)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:LISTENS_FOR]->(e:EventType {fqn: $eventFqn})
RETURN m.fqn, m.owner_fqn
```
