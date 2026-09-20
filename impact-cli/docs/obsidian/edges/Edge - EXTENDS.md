# Edge - EXTENDS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:EXTENDS` |
| **From** | [[Node - Class]] (child) |
| **To** | [[Node - Class]] (parent) |
| **Layer** | Core |

## Description

Represents Java class inheritance (`extends` keyword). Parent class node is auto-created (with ON CREATE SET for simple_name/pkg) even if not in the current source base.

## Edge Properties

None.

## Java Record

```java
public record ExtendsEdge(String fromClassFqn, String toClassFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (parent:Class {fqn: row.to})
  ON CREATE SET parent.simple_name = row.toSimple, parent.pkg = row.toPkg
MERGE (child:Class  {fqn: row.from})
MERGE (child)-[:EXTENDS]->(parent)
```

## Cypher — Query

```cypher
-- Find class hierarchy (all descendants)
MATCH path = (child:Class)-[:EXTENDS*]->(ancestor:Class {simple_name: "HttpServlet"})
RETURN child.fqn, length(path) AS depth

-- Find direct parent
MATCH (c:Class {fqn: $classFqn})-[:EXTENDS]->(parent:Class)
RETURN parent.fqn, parent.simple_name
```
