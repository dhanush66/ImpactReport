# Node - RestEndpoint

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:RestEndpoint` |
| **Key** | `url` (string) |
| **Layer** | P4 Framework |
| **Constraint** | `REQUIRE r.url IS UNIQUE` |

## Description

Represents a REST/HTTP API URL endpoint exposed by a servlet or controller. URLs are extracted from web.xml, REST-API XML configs, @WebServlet annotations, and dynamically from JS/C# string literals.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `url` | String | URL pattern (e.g., `/api/json/workflow/approveRequest`) |
| `class_fqn` | String | FQN of the serving class (empty for materialized-from-JS) |

## Java Record

```java
public record RestEndpointNode(String url, String classFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (r:RestEndpoint {url: row.url})
  ON CREATE SET r.class_fqn = row.class_fqn
```

## Cypher — Query Examples

```cypher
-- Find which class serves a URL
MATCH (c:Class)-[:EXPOSES]->(r:RestEndpoint {url: "/api/json/workflow/approveRequest"})
RETURN c.fqn, c.simple_name

-- Find all URLs a JS file calls
MATCH (j:JsFile {path: "routes/workflow.js"})-[:CALLS_API]->(r:RestEndpoint)
RETURN r.url

-- Find all pages linking to an API
MATCH (p:HtmlPage)-[:REFERENCES]->(r:RestEndpoint {url: $url})
RETURN p.filename, p.title
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - EXPOSES]] | [[Node - Class]] |
| IN | [[Edge - REFERENCES]] | [[Node - HtmlPage]] |
| IN | [[Edge - CALLS_API]] | [[Node - JsFile]], [[Node - CsFile]] |
| IN | [[Edge - COVERS]] | [[Node - TestCase]] |
