# Edge - HANDLES_ATTRIBUTE

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:HANDLES_ATTRIBUTE` |
| **From** | [[Node - Method]] (in NotificationMacro class) |
| **To** | [[Node - Class]] (NotificationMacro) |
| **Layer** | §4.1 Layer D |

## Description

Records that a method in a NotificationMacro class handles a specific LDAP attribute. Detected from `ldapName.equals("attributeName")` / `equalsIgnoreCase("attributeName")` patterns in the source. Used for Layer D narrowing — determining exactly which macro attributes are affected by a patch.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `attribute` | String | LDAP attribute name (e.g., `daysToExpireAccount`) |
| `block_start_line` | Integer | Start line of the if-block handling this attribute |
| `block_end_line` | Integer | End line of the if-block |

## Java Record

```java
public record HandlesAttributeEdge(
    String fromMethodFqn,
    String macroSimpleName,
    String attributeName,
    int blockStartLine,
    int blockEndLine
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
MERGE (m)-[r:HANDLES_ATTRIBUTE {attribute: row.attr, block_start_line: row.blockStart}]->(cls)
  ON CREATE SET r.block_end_line = row.blockEnd
  ON MATCH SET r.block_end_line = row.blockEnd
```

## Cypher — Query

```cypher
-- Find all attributes handled by a macro class
MATCH (m:Method)-[r:HANDLES_ATTRIBUTE]->(cls:Class {simple_name: "WFNotificationMacro"})
RETURN m.simple_name, r.attribute, r.block_start_line, r.block_end_line

-- Impact: which attributes are affected by patch lines 191-222?
MATCH (m:Method {fqn: $methodFqn})-[r:HANDLES_ATTRIBUTE]->(cls:Class)
WHERE r.block_start_line <= 222 AND r.block_end_line >= 191
RETURN r.attribute AS affected_attribute, r.block_start_line, r.block_end_line
```

## Code Example (ADMP)

```java
// In WFNotificationMacro.parseMacrosAdmin():
if(ldapName.equalsIgnoreCase("daysToExpireAccount") && ...) {
    // Line 194-216: handles the daysToExpireAccount attribute
    String daysToExpVal = userProps.get(ldapName).toString();
    if(daysToExpVal.equals("0") || daysToExpVal.equals("-1")){
        userProps.put(ldapName, rb.getString("...not_applicable"));
    } else if(daysToExpVal.matches("\\d+")){
        Long accountExpiryDateTemp = Long.valueOf(daysToExpVal);
        // ... date parsing logic ...
    } else {
        // ... date-string parsing (the 13216 fix) ...
    }
}
// Graph:
// parseMacrosAdmin(...) -[:HANDLES_ATTRIBUTE {attribute:"daysToExpireAccount", 
//   block_start_line:194, block_end_line:222}]-> (:Class {simple_name:"WFNotificationMacro"})
```

## Analyze-Side Usage

Layer D overlap test in SliceExecutor:
```cypher
MATCH (m:Method {fqn: row.methodFqn})-[h:HANDLES_ATTRIBUTE]->(cls)
WHERE h.block_start_line <= row.hEnd AND h.block_end_line >= row.hStart
RETURN h.attribute AS affected_macro_key
```
Only reports the specific attribute whose if-block overlaps the patch hunk lines.
