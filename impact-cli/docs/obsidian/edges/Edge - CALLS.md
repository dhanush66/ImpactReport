# Edge - CALLS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:CALLS` |
| **From** | [[Node - Method]] (caller) |
| **To** | [[Node - Method]] (callee) |
| **Layer** | Core |

## Description

Represents a method invocation. The `kind` property distinguishes call types. This is the primary edge for callgraph traversal during impact analysis.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `kind` | String | `"direct"` (default), `"virtual"`, `"static"` |

## Java Record

```java
public record CallEdge(String fromMethodFqn, String toMethodFqn, String kind) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (a:Method {fqn: row.from})
  ON CREATE SET a.simple_name = row.fromSimple, a.owner_fqn = row.fromOwner
MERGE (b:Method {fqn: row.to})
  ON CREATE SET b.simple_name = row.toSimple, b.owner_fqn = row.toOwner
MERGE (a)-[r:CALLS {kind: row.kind}]->(b)
```

## Cypher — Query

```cypher
-- Direct callers of a method
MATCH (caller:Method)-[:CALLS]->(m:Method {fqn: $targetFqn})
RETURN caller.fqn, caller.owner_fqn

-- Callgraph traversal (impact radius)
MATCH path = (start:Method {fqn: $startFqn})-[:CALLS*1..5]->(reached:Method)
RETURN DISTINCT reached.fqn, reached.owner_fqn, length(path) AS hops

-- Find all methods called by a specific class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:CALLS]->(target:Method)
RETURN m.simple_name AS caller, target.fqn AS callee
```

## ADMP Code Example

```java
// In WFNotificationMacro.parseMacrosAdmin():
parseMacros(rb, loginId);                    // CALLS → parseMacros
HashMap userProps = getUserDetails(rb, ...); // CALLS → getUserDetails
fillPropsFromDetailsTable(...);              // CALLS → fillPropsFromDetailsTable
```
