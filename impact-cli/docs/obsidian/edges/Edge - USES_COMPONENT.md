# Edge - USES_COMPONENT

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:USES_COMPONENT` |
| **From** | [[Node - HbsTemplate]] |
| **To** | [[Node - JsFile]] |
| **Layer** | UI (Ember) |

## Description

Links an HBS template to the JS component it renders via `{{component-name}}` invocations in Handlebars syntax.

## Edge Properties

None.

## Java Record

```java
public record HbsUsesComponentEdge(String hbsPath, String jsPath) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MATCH (h:HbsTemplate {path: row.hbs})
MATCH (j:JsFile {path: row.js})
MERGE (h)-[:USES_COMPONENT]->(j)
```

## Cypher — Query

```cypher
-- Which templates use a component?
MATCH (h:HbsTemplate)-[:USES_COMPONENT]->(j:JsFile {simple_name: "ads-select-input"})
RETURN h.path, h.simple_name
```
