# Node - Class

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Class` |
| **Key** | `fqn` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE c.fqn IS UNIQUE` |
| **Index** | `ON (c.simple_name)` |

## Description

Represents a Java class, interface, or enum. Central node in the graph — contains methods and fields, participates in inheritance, and may be tagged with additional role labels (`:Interface`, `:Servlet`, `:TaskHandler`, `:NotificationMacro`, etc.).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `fqn` | String | Fully-qualified name (e.g., `com.adventnet.sym.adsm.common.server.workflow.WFNotificationMacro`) |
| `simple_name` | String | Simple class name (e.g., `WFNotificationMacro`) |
| `pkg` | String | Package name |
| `file_path` | String | Source file relative path |
| `is_interface` | Boolean | True for interfaces |
| `is_abstract` | Boolean | True for abstract classes |
| `start_line` | Integer | Start line in source file |
| `end_line` | Integer | End line in source file |
| `commit_sha` | String | Commit SHA for version tracking |
| `extra_labels` | List\<String\> | Dynamic sub-labels applied |

## Java Record

```java
public record ClassNode(
    String fqn,
    String simpleName,
    String pkg,
    String filePath,
    boolean isInterface,
    boolean isAbstract,
    int startLine,
    int endLine,
    List<String> extraLabels   // e.g. ["Servlet"], ["Scheduler"], ["TaskHandler"]
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (c:Class {fqn: row.fqn})
  SET c.simple_name = row.simple,
      c.pkg = row.pkg,
      c.file_path = row.file,
      c.is_interface = row.isInterface,
      c.is_abstract = row.isAbstract,
      c.start_line = row.start,
      c.end_line = row.end,
      c.commit_sha = row.sha,
      c.extra_labels = row.extras
FOREACH (_ IN CASE WHEN row.isInterface THEN [1] ELSE [] END | SET c:Interface)
WITH c, row
MATCH (p:Package {name: row.pkg})
MERGE (p)-[:CONTAINS]->(c)
```

## Cypher — Query Examples

```cypher
-- Find class by name
MATCH (c:Class {simple_name: "WFNotificationMacro"})
RETURN c.fqn, c.file_path, c.start_line, c.end_line

-- Find all NotificationMacro classes
MATCH (c:Class:NotificationMacro)
RETURN c.fqn, c.simple_name

-- Find class hierarchy
MATCH (child:Class)-[:EXTENDS]->(parent:Class {simple_name: "WorkFlowAction"})
RETURN child.fqn

-- Find all methods in a class
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)
RETURN m.fqn, m.simple_name, m.start_line
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - CONTAINS]] | [[Node - Package]], [[Node - File]] |
| OUT | [[Edge - CONTAINS]] | [[Node - Method]], [[Node - Field]] |
| OUT | [[Edge - EXTENDS]] | [[Node - Class]] |
| OUT | [[Edge - IMPLEMENTS]] | [[Node - Class]] (Interface) |
| OUT | [[Edge - HANDLES]] | [[Node - TaskType]] |
| OUT | [[Edge - EXPOSES]] | [[Node - RestEndpoint]] |
| OUT | [[Edge - SINGLETON_OF]] | [[Node - Class]] (self) |
| OUT | [[Edge - INJECTS]] | [[Node - Class]] |
| IN | [[Edge - INSTANTIATES]] | [[Node - Method]] |
| IN | [[Edge - GATES_DISPATCH]] | [[Node - Method]] |
| IN | [[Edge - HANDLES_ATTRIBUTE]] | [[Node - Method]] |

## Sub-labels

| Label | Meaning |
|-------|---------|
| `:Interface` | Java interface |
| `:Servlet` | HttpServlet subclass |
| `:Scheduler` | TimerTask/Runnable scheduled by system |
| `:TaskHandler` | Implements ManagementTask/TaskHandler |
| `:Job` | Background job class |
| `:NotificationMacro` | Implements NotificationMacro interface |
