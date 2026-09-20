# Node - JsFile

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:JsFile` |
| **Key** | `path` (string) |
| **Layer** | UI (Ember) |
| **Constraint** | `REQUIRE j.path IS UNIQUE` |

## Description

Represents an Ember.js source file (routes, models, controllers, components, services, adapters, helpers). Connected to REST APIs it calls, HBS templates it renders, and other JS files it imports.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | String | Relative path under `source/ember/app` (e.g., `routes/dashboard.js`) |
| `simple_name` | String | File stem (e.g., `dashboard`) |
| `role` | String | `Route` / `Model` / `Controller` / `Component` / `Service` / `Adapter` / `Helper` / `Other` |
| `file_path` | String | Absolute disk path |

## Java Record

```java
public record JsFileNode(
    String path,
    String simpleName,
    String role,
    String filePath
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (j:JsFile {path: row.path})
  SET j.simple_name = row.simple, j.role = row.role, j.file_path = row.filePath
```

## Cypher — Query Examples

```cypher
-- Find APIs called by a JS route
MATCH (j:JsFile {path: "routes/workflow/myrequests.js"})-[:CALLS_API]->(r:RestEndpoint)
RETURN r.url

-- Find all components used by a route's template
MATCH (j:JsFile {path: "routes/workflow.js"})-[:RENDERS_TEMPLATE]->(h:HbsTemplate)
MATCH (h)-[:USES_COMPONENT]->(comp:JsFile)
RETURN comp.path, comp.simple_name
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| OUT | [[Edge - CALLS_API]] | [[Node - RestEndpoint]] |
| OUT | [[Edge - IMPORTS]] | [[Node - JsFile]] |
| OUT | [[Edge - RENDERS_TEMPLATE]] | [[Node - HbsTemplate]] |
| IN | [[Edge - IMPORTS]] | [[Node - JsFile]] |
| IN | [[Edge - USES_COMPONENT]] | [[Node - HbsTemplate]] |

## Sub-labels

| Label | Meaning |
|-------|---------|
| `:JsRoute` | Ember Route file |
| `:JsModel` | Ember Model file |
| `:JsController` | Ember Controller file |
| `:JsComponent` | Ember Component file |
| `:JsService` | Ember Service file |
| `:JsAdapter` | Ember Adapter file |
| `:JsHelper` | Ember Helper file |
