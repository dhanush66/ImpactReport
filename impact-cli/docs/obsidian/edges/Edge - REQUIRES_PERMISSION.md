# Edge - REQUIRES_PERMISSION

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:REQUIRES_PERMISSION` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Permission]] |
| **Layer** | §4.1 Security (S1) |

## Description

Records that a method performs a permission/authorization check before executing its logic.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (p:Permission {id: row.id})
MERGE (m)-[:REQUIRES_PERMISSION]->(p)
```

## Cypher — Query

```cypher
-- Impact: permissions affected by a change
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:REQUIRES_PERMISSION]->(p:Permission)
RETURN DISTINCT p.id

-- Find all methods requiring a permission
MATCH (m:Method)-[:REQUIRES_PERMISSION]->(p:Permission {id: $permId})
RETURN m.fqn, m.owner_fqn
```
