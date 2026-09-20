# Node - PsScript

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:PsScript` |
| **Key** | `name` (string) |
| **Layer** | P5 Scripts |
| **Constraint** | `REQUIRE p.name IS UNIQUE` |

## Description

Represents a PowerShell script filename (`.ps1`). Detected when Java methods or C# files reference a `.ps1` file via ProcessBuilder or similar execution APIs.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | String | Script filename (e.g., `EnableMailbox.ps1`) |

## Java Record

```java
public record PsScriptNode(String name) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (p:PsScript {name: row.name})
```

## Cypher — Query Examples

```cypher
-- Find all methods that invoke a script
MATCH (m:Method)-[:INVOKES_SCRIPT]->(p:PsScript {name: "EnableMailbox.ps1"})
RETURN m.fqn, m.owner_fqn

-- Find all C# files calling scripts
MATCH (c:CsFile)-[:INVOKES_SCRIPT]->(p:PsScript)
RETURN c.path, p.name
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - INVOKES_SCRIPT]] | [[Node - Method]], [[Node - CsFile]] |
