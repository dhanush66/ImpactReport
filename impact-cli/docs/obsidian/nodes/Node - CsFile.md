# Node - CsFile

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:CsFile` |
| **Key** | `path` (string) |
| **Layer** | UI (C# Agent) |
| **Constraint** | `REQUIRE c.path IS UNIQUE` |

## Description

Represents a C# source file from the SharePoint client/agent layer. Connects to REST APIs called and PowerShell scripts invoked from C# code.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | String | Relative path under `source/c_sharp` |
| `simple_name` | String | File stem (e.g., `ManagementTaskHandler`) |
| `role` | String | `Management` / `Reports` / `Audit` / `Client` / `Common` / `Core` / `Other` |
| `file_path` | String | Absolute disk path |

## Java Record

```java
public record CsFileNode(
    String path,
    String simpleName,
    String role,
    String filePath
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (c:CsFile {path: row.path})
  SET c.simple_name = row.simple, c.role = row.role, c.file_path = row.filePath
```

## Cypher — Query Examples

```cypher
-- Find scripts invoked by a C# file
MATCH (c:CsFile {simple_name: "ManagementTaskHandler"})-[:INVOKES_SCRIPT]->(p:PsScript)
RETURN p.name

-- Find all C# files calling an API
MATCH (c:CsFile)-[:CALLS_API]->(r:RestEndpoint {url: $url})
RETURN c.path, c.role
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - CALLS_API]] | [[Node - RestEndpoint]] |
| OUT | [[Edge - INVOKES_SCRIPT]] | [[Node - PsScript]] |
