# Edge - GATES_DISPATCH

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:GATES_DISPATCH` |
| **From** | [[Node - Method]] (gater) |
| **To** | [[Node - Class]] (NotificationMacro) |
| **Layer** | §4.1 Layer C (D7) |

## Description

Records a "dispatch block" inside a method — the line range between a `macro.init(...)` call and the subsequent `trigger.start()` / `MgmtNotificationListener.triggerNotification(...)`. Any patch landing **inside** this line range potentially gates whether the notification fires.

This is a **backward-tracking** edge — it points from the gating method back to the NotificationMacro class that gets dispatched. Used in Layer D narrowing to determine which macro classes are affected by a patch.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `block_start_line` | Integer | Start line of dispatch block (MERGE key) |
| `block_end_line` | Integer | End line of dispatch block |
| `macro_simple` | String | Macro class simple name |
| `sibling_methods` | List\<String\> | Method calls inside the gating block |

## Java Record

```java
public record GatesDispatchEdge(
    String fromMethodFqn,
    String macroSimpleName,
    int blockStartLine,
    int blockEndLine,
    List<String> siblingMethods
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
WITH m, row
OPTIONAL MATCH (cls:Class {simple_name: row.simple})
WITH m, row, cls WHERE cls IS NOT NULL
MERGE (m)-[r:GATES_DISPATCH {block_start_line: row.blockStart}]->(cls)
  ON CREATE SET r.block_end_line = row.blockEnd, r.macro_simple = row.simple, r.sibling_methods = row.siblings
  ON MATCH SET r.sibling_methods = row.siblings
```

## Cypher — Query

```cypher
-- Find dispatch blocks overlapping a patch hunk
MATCH (m:Method)-[r:GATES_DISPATCH]->(cls:Class)
WHERE m.fqn = $methodFqn
  AND r.block_start_line <= $hunkEnd
  AND r.block_end_line >= $hunkStart
RETURN cls.simple_name, r.block_start_line, r.block_end_line, r.sibling_methods

-- Find all methods that gate WFNotificationMacro
MATCH (m:Method)-[r:GATES_DISPATCH]->(cls:Class {simple_name: "WFNotificationMacro"})
RETURN m.fqn, r.block_start_line, r.block_end_line
```

## ADMP Code Example

```java
// In WorkFlowAction.approveRequest():
WFNotificationMacro macro = new WFNotificationMacro();  // block_start
macro.init(ids);
macro.setNotifyType(MAIL_TYPE_ID);
// ... (sibling methods: ["setNotifyType", "init"])
NotificationTrigger trigger = new NotificationTrigger(macro);
trigger.start();  // block_end
// Emits: approveRequest() -[:GATES_DISPATCH {block_start:150, block_end:180}]-> WFNotificationMacro
```

## Difference from :DISPATCHES_TO

| :DISPATCHES_TO               | :GATES_DISPATCH                        |
| ---------------------------- | -------------------------------------- |
| Forward: caller → callee     | Backward: gater → macro class          |
| Cross-thread call linkage    | Dispatch-block line-range tracking     |
| Used for callgraph extension | Used for notification impact narrowing |
| No line-range properties     | Has block_start/end for hunk overlap   |
