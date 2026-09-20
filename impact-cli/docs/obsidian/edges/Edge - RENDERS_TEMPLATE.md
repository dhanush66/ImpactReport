# Edge - RENDERS_TEMPLATE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:RENDERS_TEMPLATE` |
| **From** | [[Node - JsFile]] (Component) |
| **To** | [[Node - HbsTemplate]] |
| **Layer** | UI (Ember) |

## Description

Pairs a JS component file with its corresponding HBS template by Ember path convention (e.g., `components/my-widget.js` → `templates/components/my-widget.hbs`).

## Edge Properties

None.

## Java Record

```java
public record JsRendersTemplateEdge(String jsPath, String hbsPath) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MATCH (j:JsFile {path: row.js})
MATCH (h:HbsTemplate {path: row.hbs})
MERGE (j)-[:RENDERS_TEMPLATE]->(h)
```

## Cypher — Query

```cypher
-- Find template for a component
MATCH (j:JsFile {path: $jsPath})-[:RENDERS_TEMPLATE]->(h:HbsTemplate)
RETURN h.path, h.simple_name
```
