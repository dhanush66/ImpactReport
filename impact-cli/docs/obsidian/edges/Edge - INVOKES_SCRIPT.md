# Edge - INVOKES_SCRIPT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:INVOKES_SCRIPT` |
| **From** | [[Node - Method]] / [[Node - CsFile]] |
| **To** | [[Node - PsScript]] |
| **Layer** | P5 Scripts |

## Description

Records invocation of a PowerShell script from Java (via ProcessBuilder) or C# (via Process.Start or similar).

## Edge Properties

None.

## Java Record

```java
public record InvokesScriptEdge(String fromMethodFqn, String scriptName) {}
public record CsInvokesScriptEdge(String csFilePath, String scriptName) {}
```

## Cypher — Create

```cypher
-- Java method → PsScript
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner
MERGE (p:PsScript {name: row.script})
MERGE (m)-[:INVOKES_SCRIPT]->(p)

-- C# file → PsScript
UNWIND $rows AS row
MATCH (c:CsFile {path: row.path})
MERGE (p:PsScript {name: row.script})
MERGE (c)-[:INVOKES_SCRIPT]->(p)
```

## Cypher — Query

```cypher
-- Find all callers of a script
MATCH (n)-[:INVOKES_SCRIPT]->(p:PsScript {name: "EnableMailbox.ps1"})
RETURN labels(n), COALESCE(n.fqn, n.path) AS caller
```
