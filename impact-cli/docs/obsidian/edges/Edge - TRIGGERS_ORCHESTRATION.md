# Edge - TRIGGERS_ORCHESTRATION

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:TRIGGERS_ORCHESTRATION` |
| **From** | [[Node - Method]] |
| **To** | [[Node - OrchestrationProfile]] |
| **Layer** | §4.1 Orchestration (D5) |

## Description

Records that a method builds `new OrchestrationTrigger(...)` and invokes `.start()` on it. Captures the structural fact that this code path starts an orchestration workflow.

## Edge Properties

None.

## Java Record

```java
public record TriggersOrchestrationEdge(String fromMethodFqn, String actionId) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (o:OrchestrationProfile {id: row.id})
MERGE (m)-[:TRIGGERS_ORCHESTRATION]->(o)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:TRIGGERS_ORCHESTRATION]->(o:OrchestrationProfile)
WHERE o.id <> '<unspecified>'
RETURN m.fqn, o.id
```
