# Edge - EXPOSES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:EXPOSES` |
| **From** | [[Node - Class]] (Servlet/Controller) |
| **To** | [[Node - RestEndpoint]] |
| **Layer** | P4 Framework |

## Description

Links a servlet or controller class to the REST URL it serves. The `target_method_simple_name` property enables per-method URL routing granularity for dispatcher-style classes (e.g., Struts actions with 48+ URLs through one class).

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `target_method_simple_name` | String | Method name for dispatcher routing (empty = class-granularity) |

## Java Record

```java
public record ExposesEdge(String classFqn, String url, String targetMethodSimpleName) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (c:Class {fqn: row.class_fqn})
MERGE (r:RestEndpoint {url: row.url})
MERGE (c)-[e:EXPOSES]->(r)
  ON CREATE SET e.target_method_simple_name = row.tgt_simple
  ON MATCH  SET e.target_method_simple_name =
    CASE WHEN coalesce(e.target_method_simple_name, '') = ''
         THEN row.tgt_simple ELSE e.target_method_simple_name END
```

## Cypher — Query

```cypher
-- Find URLs exposed by a class
MATCH (c:Class {fqn: $classFqn})-[e:EXPOSES]->(r:RestEndpoint)
RETURN r.url, e.target_method_simple_name

-- Find servlet for a URL
MATCH (c:Class)-[:EXPOSES]->(r:RestEndpoint {url: $url})
RETURN c.fqn, c.simple_name

-- Find affected URLs when a specific method changes
MATCH (c:Class)-[e:EXPOSES]->(r:RestEndpoint)
WHERE c.fqn = $classFqn
  AND (e.target_method_simple_name = '' OR e.target_method_simple_name = $methodName)
RETURN r.url
```
