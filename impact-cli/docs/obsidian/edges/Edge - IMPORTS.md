# Edge - IMPORTS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:IMPORTS` |
| **From** | [[Node - JsFile]] |
| **To** | [[Node - JsFile]] |
| **Layer** | UI (Ember) |

## Description

Represents an ES module import between JS files (e.g., `import Service from '../services/workflow'`).

## Edge Properties

None.

## Java Record

```java
public record JsImportsEdge(String fromJsPath, String toJsPath) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MATCH (a:JsFile {path: row.from})
MATCH (b:JsFile {path: row.to})
MERGE (a)-[:IMPORTS]->(b)
```

## Cypher — Query

```cypher
-- Find import chain
MATCH path = (j:JsFile {path: $startPath})-[:IMPORTS*1..3]->(dep:JsFile)
RETURN [n IN nodes(path) | n.path] AS chain
```
