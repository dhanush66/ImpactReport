# Edge - WRITES_AUDIT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:WRITES_AUDIT` |
| **From** | [[Node - Method]] |
| **To** | [[Node - AuditCategory]] |
| **Layer** | §4.1 Observability |

## Description

Records that a method writes an audit trail entry to a specific audit category.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (a:AuditCategory {id: row.id})
MERGE (m)-[:WRITES_AUDIT]->(a)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:WRITES_AUDIT]->(a:AuditCategory {id: "WORKFLOW_APPROVED"})
RETURN m.fqn, m.owner_fqn
```
