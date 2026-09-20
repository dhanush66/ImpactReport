# Edge - IMPLEMENTS

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:IMPLEMENTS` |
| **From** | [[Node - Class]] |
| **To** | [[Node - Class]] (Interface) |
| **Layer** | Core |

## Description

Represents interface implementation (`implements` keyword). Interface node is auto-created if not already present.

## Edge Properties

None.

## Java Record

```java
public record ImplementsEdge(String fromClassFqn, String toInterfaceFqn) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (iface:Class {fqn: row.to})
  ON CREATE SET iface.simple_name = row.toSimple, iface.pkg = row.toPkg
MERGE (cls:Class   {fqn: row.from})
MERGE (cls)-[:IMPLEMENTS]->(iface)
```

## Cypher — Query

```cypher
-- Find all implementations of NotificationMacro
MATCH (cls:Class)-[:IMPLEMENTS]->(iface:Class {simple_name: "NotificationMacro"})
RETURN cls.fqn, cls.simple_name

-- Find all interfaces a class implements
MATCH (c:Class {fqn: $classFqn})-[:IMPLEMENTS]->(iface:Class)
RETURN iface.fqn
```
