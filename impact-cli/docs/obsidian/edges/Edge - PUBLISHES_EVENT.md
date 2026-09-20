# Edge - PUBLISHES_EVENT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:PUBLISHES_EVENT` |
| **From** | [[Node - Method]] |
| **To** | [[Node - EventType]] |
| **Layer** | §4.1 Events |

## Description

Records that a method publishes an application event (Spring `eventPublisher.publishEvent()` or Guava `EventBus.post()`).

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (e:EventType {fqn: row.fqn})
MERGE (m)-[:PUBLISHES_EVENT]->(e)
```

## Cypher — Query

```cypher
-- Trace event from publisher to listener
MATCH (pub:Method)-[:PUBLISHES_EVENT]->(e:EventType)<-[:LISTENS_FOR]-(sub:Method)
RETURN pub.fqn AS publisher, e.fqn AS event, sub.fqn AS listener
```
