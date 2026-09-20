# Node - ExternalSystem

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:ExternalSystem` |
| **Key** | `id` (string) |
| **Layer** | §4.1 External |
| **Constraint** | `REQUIRE x.id IS UNIQUE` |

## Description

Represents an external service the application integrates with (SharePoint, Microsoft Graph, Slack, LDAP, etc.). Detected from URL patterns and API client instantiations.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | System identifier (e.g., `SharePoint`, `GraphAPI`) |
| `base_url` | String | Representative base URL (when extractable) |

## Java Record

```java
public record ExternalSystemNode(String id, String baseUrl) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (x:ExternalSystem {id: row.id})
  ON CREATE SET x.base_url = row.baseUrl
```

## Cypher — Query Examples

```cypher
-- Find methods calling an external system
MATCH (m:Method)-[r:CALLS_EXTERNAL]->(x:ExternalSystem {id: "SharePoint"})
RETURN m.fqn, r.url_sample

-- Impact: what external systems are affected by a class change?
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:CALLS_EXTERNAL]->(x:ExternalSystem)
RETURN DISTINCT x.id, x.base_url
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - CALLS_EXTERNAL]] | [[Node - Method]] |
