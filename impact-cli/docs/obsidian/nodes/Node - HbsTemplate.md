# Node - HbsTemplate

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:HbsTemplate` |
| **Key** | `path` (string) |
| **Layer** | UI (Ember) |
| **Constraint** | `REQUIRE h.path IS UNIQUE` |

## Description

Represents a Handlebars (`.hbs`) template file in the Ember UI layer. Templates render JS components via `{{component-name}}` invocations and are paired with JS files by path convention.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | String | Relative path under `source/ember/app` |
| `simple_name` | String | Template name without extension (e.g., `ads-select-input`) |
| `role` | String | `ComponentTemplate` / `RouteTemplate` / `Other` |
| `file_path` | String | Absolute disk path |

## Java Record

```java
public record HbsTemplateNode(
    String path,
    String simpleName,
    String role,
    String filePath
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (h:HbsTemplate {path: row.path})
  SET h.simple_name = row.simple, h.role = row.role, h.file_path = row.filePath
```

## Cypher — Query Examples

```cypher
-- Find all components used in a template
MATCH (h:HbsTemplate {simple_name: "workflow-form"})-[:USES_COMPONENT]->(j:JsFile)
RETURN j.path, j.simple_name

-- Find which JS file renders this template
MATCH (j:JsFile)-[:RENDERS_TEMPLATE]->(h:HbsTemplate {path: $hbsPath})
RETURN j.path, j.role
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - USES_COMPONENT]] | [[Node - JsFile]] |
| IN | [[Edge - RENDERS_TEMPLATE]] | [[Node - JsFile]] |
