# Node - Method

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:Method` |
| **Key** | `fqn` (string) |
| **Layer** | Core |
| **Constraint** | `REQUIRE m.fqn IS UNIQUE` |
| **Indexes** | `ON (m.simple_name)`, `ON (m.owner_fqn)` |

## Description

Represents a Java method or constructor. The most connected node type — source and target for most edges. The FQN format is `owner.methodName(paramType1,paramType2)`.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `fqn` | String | `pkg.ClassName.method(ParamTypes)` |
| `signature` | String | `method(ParamTypes)` (without owner) |
| `simple_name` | String | `method` (just the name) |
| `owner_fqn` | String | FQN of containing class |
| `return_type` | String | Return type name |
| `is_static` | Boolean | Static method flag |
| `is_constructor` | Boolean | Constructor flag |
| `start_line` | Integer | Start line in source |
| `end_line` | Integer | End line in source |
| `commit_sha` | String | Commit for version tracking |
| `extra_labels` | List\<String\> | e.g., `["EntryPoint"]` |

## Java Record

```java
public record MethodNode(
    String fqn,           // owner.method(paramTypes)
    String signature,     // method(paramTypes)
    String simpleName,
    String ownerFqn,
    String returnType,
    boolean isStatic,
    boolean isConstructor,
    int startLine,
    int endLine,
    List<String> extraLabels   // e.g. ["EntryPoint"]
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.fqn})
  SET m.signature = row.sig,
      m.simple_name = row.simple,
      m.owner_fqn = row.owner,
      m.return_type = row.ret,
      m.is_static = row.isStatic,
      m.is_constructor = row.isCtor,
      m.start_line = row.start,
      m.end_line = row.end,
      m.commit_sha = row.sha,
      m.extra_labels = row.extras
FOREACH (_ IN CASE WHEN row.isCtor THEN [1] ELSE [] END | SET m:Constructor)
WITH m, row
MATCH (c:Class {fqn: row.owner})
MERGE (c)-[:CONTAINS]->(m)
```

## Cypher — Query Examples

```cypher
-- Find method by name
MATCH (m:Method {simple_name: "parseMacrosAdmin"})
RETURN m.fqn, m.owner_fqn, m.start_line, m.end_line

-- Find all callers of a method
MATCH (caller:Method)-[:CALLS]->(m:Method {fqn: $targetFqn})
RETURN caller.fqn, caller.owner_fqn

-- Find methods that read a specific table
MATCH (m:Method)-[:READS_TABLE]->(t:DbTable {name: "ADSMRequests"})
RETURN m.fqn, m.simple_name

-- Callgraph traversal (3 hops)
MATCH path = (m:Method {fqn: $startFqn})-[:CALLS*1..3]->(target:Method)
RETURN [n IN nodes(path) | n.fqn] AS chain
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - CONTAINS]] | [[Node - Class]] |
| OUT | [[Edge - CALLS]] | [[Node - Method]] |
| IN | [[Edge - CALLS]] | [[Node - Method]] |
| OUT | [[Edge - READS]] | [[Node - Field]] |
| OUT | [[Edge - WRITES]] | [[Node - Field]] |
| OUT | [[Edge - OVERRIDES]] | [[Node - Method]] |
| OUT | [[Edge - DISPATCHES_TO]] | [[Node - Method]] |
| OUT | [[Edge - READS_TABLE]] | [[Node - DbTable]] |
| OUT | [[Edge - WRITES_TABLE]] | [[Node - DbTable]] |
| OUT | [[Edge - INVOKES_SCRIPT]] | [[Node - PsScript]] |
| OUT | [[Edge - SENDS_MESSAGE]] | [[Node - MessageConstant]] |
| OUT | [[Edge - RECEIVES_MESSAGE]] | [[Node - MessageConstant]] |
| OUT | [[Edge - SENDS_NOTIFICATION]] | [[Node - NotificationType]] |
| OUT | [[Edge - SENDS_EMAIL]] | [[Node - EmailTemplate]] |
| OUT | [[Edge - WRITES_AUDIT]] | [[Node - AuditCategory]] |
| OUT | [[Edge - SCHEDULES]] | [[Node - ScheduledTask]] |
| OUT | [[Edge - CANCELS_SCHEDULE]] | [[Node - ScheduledTask]] |
| OUT | [[Edge - USER_SCHEDULES]] | [[Node - ScheduledTask]] |
| OUT | [[Edge - PUBLISHES_EVENT]] | [[Node - EventType]] |
| OUT | [[Edge - LISTENS_FOR]] | [[Node - EventType]] |
| OUT | [[Edge - INSTANTIATES_HANDLER]] | [[Node - Class]] |
| OUT | [[Edge - INSTANTIATES]] | [[Node - Class]] |
| OUT | [[Edge - READS_PROPERTY]] | [[Node - Property]] |
| OUT | [[Edge - GATED_BY]] | [[Node - FeatureFlag]] |
| OUT | [[Edge - REQUIRES_PERMISSION]] | [[Node - Permission]] |
| OUT | [[Edge - VALIDATES_INPUT]] | [[Node - Validator]] |
| OUT | [[Edge - READS_PARAM]] | [[Node - RequestParam]] |
| OUT | [[Edge - CALLS_EXTERNAL]] | [[Node - ExternalSystem]] |
| OUT | [[Edge - WRITES_LOG]] | [[Node - LogChannel]] |
| OUT | [[Edge - TRANSITIONS_STATE]] | [[Node - State]] |
| OUT | [[Edge - TRIGGERS_ORCHESTRATION]] | [[Node - OrchestrationProfile]] |
| OUT | [[Edge - GATES_DISPATCH]] | [[Node - Class]] (NotificationMacro) |
| OUT | [[Edge - HANDLES_ATTRIBUTE]] | [[Node - Class]] (NotificationMacro) |

## Sub-labels

| Label | Meaning |
|-------|---------|
| `:EntryPoint` | Servlet doGet/doPost, main(), scheduled run() |
| `:Constructor` | Class constructor |
