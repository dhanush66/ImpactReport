# Node - Commit

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Commit` |
| **Key** | `sha` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE c.sha IS UNIQUE` |

## Description

Represents a specific Git commit snapshot. All `:File` nodes are anchored to a commit via `:IN_COMMIT`. The incremental ingest uses commit SHA to detect which files have changed between runs.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `sha` | String | Git commit SHA (or synthetic identifier) |

## Cypher — Create

```cypher
MERGE (c:Commit {sha: $sha})
```

## Cypher — Query Examples

```cypher
-- Find all files in a commit
MATCH (f:File)-[:IN_COMMIT]->(c:Commit {sha: "abc123"})
RETURN f.path

-- Find repo for a commit
MATCH (r:Repo)-[:HAS_SNAPSHOT]->(c:Commit {sha: "abc123"})
RETURN r.id, r.source_root
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - HAS_SNAPSHOT]] | [[Node - Repo]] |
| IN | [[Edge - IN_COMMIT]] | [[Node - File]] |
