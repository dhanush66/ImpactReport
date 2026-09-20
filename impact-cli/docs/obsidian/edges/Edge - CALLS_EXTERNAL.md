# Edge - CALLS_EXTERNAL

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:CALLS_EXTERNAL` |
| **From** | [[Node - Method]] |
| **To** | [[Node - ExternalSystem]] |
| **Layer** | §4.1 External (E1) |

## Description

Records that a method calls an external system (SharePoint, Microsoft Graph, Slack, LDAP, etc.).

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `url_sample` | String | Representative URL literal (empty if not extractable) |

## Java Record

```java
public record CallsExternalEdge(String fromMethodFqn, String systemId, String urlSample) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (x:ExternalSystem {id: row.id})
MERGE (m)-[r:CALLS_EXTERNAL]->(x)
  ON CREATE SET r.url_sample = row.urlSample
```

## Cypher — Query

```cypher
MATCH (m:Method)-[r:CALLS_EXTERNAL]->(x:ExternalSystem {id: "SharePoint"})
RETURN m.fqn, r.url_sample
```
