# Edge - VALIDATES_INPUT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:VALIDATES_INPUT` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Validator]] |
| **Layer** | §4.1 Security (S2) |

## Description

Records that a method invokes an input validator before processing data.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (v:Validator {id: row.id})
MERGE (m)-[:VALIDATES_INPUT]->(v)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:VALIDATES_INPUT]->(v:Validator)
RETURN m.fqn, v.id
```
