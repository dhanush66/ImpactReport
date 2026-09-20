# Node - HtmlPage

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:HtmlPage` |
| **Key** | `filename` (string) |
| **Layer** | UI |
| **Constraint** | `REQUIRE p.filename IS UNIQUE` |

## Description

Represents an HTML page in the admin/server web UI. Detected from `.html` files in the web resources directory. References to REST APIs embedded in the page are captured as `:REFERENCES` edges.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `filename` | String | Relative HTML filename (e.g., `Admin-MailServer.html`) |
| `title` | String | Content of `<title>` tag (empty if absent) |
| `file_path` | String | Absolute disk path |

## Java Record

```java
public record HtmlPageNode(
    String filename,
    String title,
    String filePath
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (p:HtmlPage {filename: row.filename})
  SET p.title = row.title, p.file_path = row.filePath
```

## Cypher — Query Examples

```cypher
-- Find all APIs used by a page
MATCH (p:HtmlPage {filename: "Admin-WorkFlow.html"})-[:REFERENCES]->(r:RestEndpoint)
RETURN r.url

-- Find pages affected by a servlet change
MATCH (c:Class {fqn: $servletFqn})-[:EXPOSES]->(r:RestEndpoint)
MATCH (p:HtmlPage)-[:REFERENCES]->(r)
RETURN p.filename, p.title, r.url
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - REFERENCES]] | [[Node - RestEndpoint]] |
