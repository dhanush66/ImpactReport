# Edge - RECEIVES_MESSAGE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:RECEIVES_MESSAGE` |
| **From** | [[Node - Method]] |
| **To** | [[Node - MessageConstant]] |
| **Layer** | P5 Communication |

## Description

Records that a method receives/handles a JGroups cluster message with this constant key.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner
MERGE (c:MessageConstant {value: row.value})
MERGE (m)-[:RECEIVES_MESSAGE]->(c)
```

## Cypher — Query

```cypher
-- Find all receivers of a message type
MATCH (m:Method)-[:RECEIVES_MESSAGE]->(msg:MessageConstant {value: $msgValue})
RETURN m.fqn, m.owner_fqn
```
