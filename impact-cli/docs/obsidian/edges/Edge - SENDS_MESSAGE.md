# Edge - SENDS_MESSAGE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:SENDS_MESSAGE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - MessageConstant]] |
| **Layer** | P5 Communication |

## Description

Records that a method sends a JGroups cluster message with this constant key. Used to trace cross-node communication in clustered deployments.

## Edge Properties

None.

## Java Record

```java
public record MessageConstantEdge(String fromMethodFqn, String constantValue, boolean sending) {}
// sending=true → :SENDS_MESSAGE
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner
MERGE (c:MessageConstant {value: row.value})
MERGE (m)-[:SENDS_MESSAGE]->(c)
```

## Cypher — Query

```cypher
-- Find message flow: sender → receiver
MATCH (sender:Method)-[:SENDS_MESSAGE]->(msg:MessageConstant)<-[:RECEIVES_MESSAGE]-(receiver:Method)
RETURN sender.fqn, msg.value, receiver.fqn
```
