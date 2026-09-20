# Edge - SENDS_EMAIL

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:SENDS_EMAIL` |
| **From** | [[Node - Method]] |
| **To** | [[Node - EmailTemplate]] |
| **Layer** | §4.1 Communication |

## Description

Records that a method sends an email using a specific template.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (t:EmailTemplate {id: row.id})
MERGE (m)-[:SENDS_EMAIL]->(t)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:SENDS_EMAIL]->(t:EmailTemplate {id: $templateId})
RETURN m.fqn, m.owner_fqn
```
