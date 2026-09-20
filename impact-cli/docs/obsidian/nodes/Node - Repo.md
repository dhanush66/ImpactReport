# Node - Repo

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Repo` |
| **Key** | `id` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE r.id IS UNIQUE` |

## Description

Represents the top-level repository/project being analyzed. Acts as the root of the graph. One repo node exists per ingested project. Contains the `source_root` path to locate files on disk.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Repository identifier (set by `--repo` flag) |
| `source_root` | String | Absolute path to source directory on disk |

## Java Record

```java
// No dedicated record — created inline in Neo4jWriter.upsertRepoAndCommit()
```

## Cypher — Create

```cypher
MERGE (r:Repo {id: $repo})
  ON CREATE SET r.source_root = $src
  ON MATCH  SET r.source_root = COALESCE($src, r.source_root)
```

## Cypher — Query Examples

```cypher
-- Find all repos
MATCH (r:Repo) RETURN r.id, r.source_root

-- Find repo with its commits
MATCH (r:Repo)-[:HAS_SNAPSHOT]->(c:Commit)
RETURN r.id, c.sha
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - HAS_SNAPSHOT]] | [[Node - Commit]] |

## Code Example (Neo4jWriter.java)

```java
public void upsertRepoAndCommit(String repoId, String commitSha, String sourceRoot) {
    Map<String, Object> params = new java.util.HashMap<>();
    params.put("repo", repoId);
    params.put("sha", commitSha);
    params.put("src", sourceRoot);
    try (CResult r = client.run(
        "MERGE (r:Repo {id: $repo}) "
      + "  ON CREATE SET r.source_root = $src "
      + "  ON MATCH  SET r.source_root = COALESCE($src, r.source_root) "
      + "MERGE (c:Commit {sha: $sha}) "
      + "MERGE (r)-[:HAS_SNAPSHOT]->(c)",
        params)) {
        r.consume();
    }
}
```
