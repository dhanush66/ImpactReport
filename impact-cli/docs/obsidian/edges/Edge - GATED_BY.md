# Edge - GATED_BY

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:GATED_BY` |
| **From** | [[Node - Method]] |
| **To** | [[Node - FeatureFlag]] |
| **Layer** | §4.1 Configuration (C2) |

## Description

Records that a method's execution is conditionally gated by a feature flag/boolean toggle.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (f:FeatureFlag {id: row.id})
MERGE (m)-[:GATED_BY]->(f)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:GATED_BY]->(f:FeatureFlag {id: $flagId})
RETURN m.fqn, m.owner_fqn
```
