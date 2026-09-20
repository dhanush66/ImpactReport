# Edge - REFERENCES

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:REFERENCES` |
| **From** | [[Node - HtmlPage]] |
| **To** | [[Node - RestEndpoint]] |
| **Layer** | UI |

## Description

Links an HTML page to REST endpoints it references (URLs embedded in the page source as links, form actions, or AJAX targets).

## Edge Properties

None.

## Java Record

```java
public record HtmlPageReferencesEdge(String pageFilename, String restUrl) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MATCH (p:HtmlPage {filename: row.filename})
MATCH (r:RestEndpoint)
  WHERE r.url = row.url OR r.url STARTS WITH (row.url + '?')
MERGE (p)-[:REFERENCES]->(r)
```

## Cypher — Query

```cypher
-- Impact: which pages are affected by a URL change?
MATCH (p:HtmlPage)-[:REFERENCES]->(r:RestEndpoint {url: $url})
RETURN p.filename, p.title
```
