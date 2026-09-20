# Edge - CONTAINS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:CONTAINS` |
| **From** | [[Node - Package]] / [[Node - File]] / [[Node - Class]] |
| **To** | [[Node - File]] / [[Node - Class]] / [[Node - Method]] / [[Node - Field]] |
| **Layer** | Core |

## Description

General containment edge representing hierarchical ownership:
- Package → File (files in package)
- Package → Class (classes in package)
- File → Class (classes defined in file)
- Class → Method (methods of a class)
- Class → Field (fields of a class)

## Edge Properties

None.

## Cypher — Create (examples)

```cypher
-- Package contains File
MATCH (p:Package {name: row.pkg})
MERGE (p)-[:CONTAINS]->(f)

-- Package contains Class
MATCH (p:Package {name: row.pkg})
MERGE (p)-[:CONTAINS]->(c)

-- File contains Class
MATCH (c:Class {fqn: row.fqn})
MATCH (f:File {path: row.path})
MERGE (f)-[:CONTAINS]->(c)

-- Class contains Method
MATCH (c:Class {fqn: row.owner})
MERGE (c)-[:CONTAINS]->(m)

-- Class contains Field
MATCH (c:Class {fqn: row.owner})
MERGE (c)-[:CONTAINS]->(f)
```

## Cypher — Query

```cypher
-- Get full class breakdown
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(member)
RETURN labels(member)[0] AS type, member.simple_name, member.start_line
ORDER BY member.start_line

-- Navigate package → class → method hierarchy
MATCH (p:Package {name: $pkg})-[:CONTAINS]->(c:Class)-[:CONTAINS]->(m:Method)
RETURN c.simple_name, m.simple_name
```
