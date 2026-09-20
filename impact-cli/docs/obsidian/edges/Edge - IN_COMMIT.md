# Edge - IN_COMMIT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:IN_COMMIT` |
| **From** | [[Node - File]] |
| **To** | [[Node - Commit]] |
| **Layer** | Core |

## Description

Anchors a source file to the specific commit snapshot it was ingested under.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (f:File {path: row.path, commit_sha: row.sha})
WITH f, row
MATCH (c:Commit {sha: row.sha})
MERGE (f)-[:IN_COMMIT]->(c)
```

## Cypher — Query

```cypher
-- Find all files in a commit
MATCH (f:File)-[:IN_COMMIT]->(c:Commit {sha: $sha})
RETURN f.path, f.content_hash
```
