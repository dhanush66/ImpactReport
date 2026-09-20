# Node - File

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:File` |
| **Key** | `path` + `commit_sha` (composite) |
| **Layer** | Core |
| **Constraint** | `REQUIRE (f.path, f.commit_sha) IS UNIQUE` |

## Description

Represents a source file within a specific commit. Used for incremental ingest (content hash comparison) and for anchoring classes to their source location.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | String | Relative file path from repo root |
| `commit_sha` | String | Commit this file version belongs to |
| `repo_id` | String | Owning repository ID |
| `content_hash` | String | SHA-1 of file content (for change detection) |
| `pkg` | String | Package name (derived from path) |

## Java Record

```java
public record FileNode(
    String path, 
    String pkg, 
    String repoId, 
    String commitSha, 
    String contentHash
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (f:File {path: row.path, commit_sha: row.sha})
  SET f.repo_id = row.repo, f.content_hash = row.hash, f.pkg = row.pkg
WITH f, row
MATCH (p:Package {name: row.pkg})
MERGE (p)-[:CONTAINS]->(f)
WITH f, row
MATCH (c:Commit {sha: row.sha})
MERGE (f)-[:IN_COMMIT]->(c)
```

## Cypher — Query Examples

```cypher
-- Find file by path
MATCH (f:File {path: "source/java_source/server/com/.../WFNotificationMacro.java"})
RETURN f.path, f.content_hash

-- Hash comparison for incremental ingest
MATCH (f:File {commit_sha: $sha})
RETURN f.path AS path, f.content_hash AS hash
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - CONTAINS]] | [[Node - Package]] |
| OUT | [[Edge - IN_COMMIT]] | [[Node - Commit]] |
| OUT | [[Edge - CONTAINS]] | [[Node - Class]] |
