# Edge - CALLS_API

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:CALLS_API` |
| **From** | [[Node - JsFile]] / [[Node - CsFile]] |
| **To** | [[Node - RestEndpoint]] |
| **Layer** | UI |

## Description

Links a JS or C# file to a REST endpoint URL it calls. URLs are detected from string literals in the source code. If the URL doesn't match an existing RestEndpoint, a bare one is materialized.

## Edge Properties

None.

## Java Record

```java
public record JsCallsApiEdge(String jsFilePath, String restUrl) {}
public record CsCallsApiEdge(String csFilePath, String restUrl) {}
```

## Cypher — Create (JS)

```cypher
UNWIND $rows AS row
MATCH (j:JsFile {path: row.path})
OPTIONAL MATCH (existing:RestEndpoint)
  WHERE existing.url = row.url OR existing.url STARTS WITH (row.url + '?')
WITH j, row, collect(DISTINCT existing) AS existings
FOREACH (e IN existings | MERGE (j)-[:CALLS_API]->(e))
FOREACH (_ IN CASE WHEN size(existings) = 0 THEN [1] ELSE [] END |
  MERGE (r:RestEndpoint {url: row.url})
    ON CREATE SET r.class_fqn = ''
  MERGE (j)-[:CALLS_API]->(r)
)
```

## Cypher — Query

```cypher
-- Find all frontend consumers of an API
MATCH (n)-[:CALLS_API]->(r:RestEndpoint {url: $url})
RETURN labels(n)[0] AS source_type, COALESCE(n.path) AS source
```
