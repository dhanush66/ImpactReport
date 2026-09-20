# Edge - HAS_SNAPSHOT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:HAS_SNAPSHOT` |
| **From** | [[Node - Repo]] |
| **To** | [[Node - Commit]] |
| **Layer** | Core |

## Description

Links a repository to a commit snapshot. Each repo can have multiple snapshots (one per ingest run).

## Edge Properties

None.

## Cypher — Create

```cypher
MERGE (r:Repo {id: $repo})
MERGE (c:Commit {sha: $sha})
MERGE (r)-[:HAS_SNAPSHOT]->(c)
```

## Cypher — Query

```cypher
MATCH (r:Repo {id: $repoId})-[:HAS_SNAPSHOT]->(c:Commit)
RETURN c.sha
```

## Java Code

```java
// Neo4jWriter.upsertRepoAndCommit()
"MERGE (r:Repo {id: $repo}) "
+ "MERGE (c:Commit {sha: $sha}) "
+ "MERGE (r)-[:HAS_SNAPSHOT]->(c)"
```
