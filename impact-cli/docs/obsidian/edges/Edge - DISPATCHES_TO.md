# Edge - DISPATCHES_TO

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:DISPATCHES_TO` |
| **From** | [[Node - Method]] (dispatcher) |
| **To** | [[Node - Method]] (target execute site) |
| **Layer** | P4 Framework |

## Description

Represents a forward cross-thread or registry-driven dispatch. The caller method doesn't directly invoke the target — instead it goes through a registry/Thread.start()/executor that ultimately calls the target method. Bridges callgraph gaps caused by indirection.

## Edge Properties

None.

## Java Record

```java
public record DispatchesToEdge(String fromMethodFqn, String toMethodFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (a:Method {fqn: row.from})
  ON CREATE SET a.simple_name = row.fromSimple, a.owner_fqn = row.fromOwner
MERGE (b:Method {fqn: row.to})
  ON CREATE SET b.simple_name = row.toSimple, b.owner_fqn = row.toOwner
MERGE (a)-[:DISPATCHES_TO]->(b)
```

## Cypher — Query

```cypher
-- Find dispatch targets from a method
MATCH (m:Method {fqn: $dispatcherFqn})-[:DISPATCHES_TO]->(target:Method)
RETURN target.fqn, target.owner_fqn

-- Trace: method → dispatch → execute → downstream calls
MATCH (m:Method)-[:DISPATCHES_TO]->(exec:Method)-[:CALLS*1..3]->(downstream:Method)
WHERE m.fqn = $startFqn
RETURN exec.fqn, collect(DISTINCT downstream.fqn) AS reached
```

## ADMP Code Example

```java
// WorkFlowCommitListener.run() dispatches to NotificationTrigger.run()
// via Thread.start() — no direct CALLS edge exists between them.
// The resolver emits:
//   WorkFlowCommitListener.run() -[:DISPATCHES_TO]-> NotificationTrigger.run()
```

## Difference from :CALLS

| :CALLS | :DISPATCHES_TO |
|--------|----------------|
| Direct method invocation | Indirect registry/thread dispatch |
| Same thread | Typically cross-thread |
| Resolved by SymbolSolver | Resolved by DispatchResolver (semantic) |
