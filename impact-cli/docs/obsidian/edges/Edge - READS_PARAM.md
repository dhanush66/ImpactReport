# Edge - READS_PARAM

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:READS_PARAM` |
| **From** | [[Node - Method]] |
| **To** | [[Node - RequestParam]] |
| **Layer** | §4.1 Web (P1) |

## Description

Records that a method reads an HTTP request parameter. Carries the compared-against value and the enclosing branch line range for per-branch narrowing during impact analysis.

## Edge Properties

| Property | Type | Description |
|----------|------|-------------|
| `value` | String | Literal value compared (empty for bare reads) |
| `block_start_line` | Integer | Start line of enclosing branch |
| `block_end_line` | Integer | End line of enclosing branch |

## Java Record

```java
public record ReadsParamEdge(
    String fromMethodFqn,
    String paramName,
    String branchValue,
    int blockStartLine,
    int blockEndLine
) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (p:RequestParam {name: row.name})
MERGE (m)-[r:READS_PARAM {value: row.value, block_start_line: row.blockStart}]->(p)
  ON CREATE SET r.block_end_line = row.blockEnd
```

## Cypher — Query

```cypher
-- Find all param values checked by a method
MATCH (m:Method {fqn: $methodFqn})-[r:READS_PARAM]->(p:RequestParam)
RETURN p.name, r.value, r.block_start_line, r.block_end_line

-- Find methods that handle a specific param value (analyze filter)
MATCH (m:Method)-[r:READS_PARAM]->(p:RequestParam {name: "methodToCall"})
WHERE r.value = "approveRequest"
  AND r.block_start_line <= $hunkEnd
  AND r.block_end_line >= $hunkStart
RETURN m.fqn
```

## ADMP Code Example

```java
// In WorkFlowAction.processRequest():
String method = request.getParameter("methodToCall");
if ("approveRequest".equals(method)) {  // block_start = 150, block_end = 200
    // ...approveRequest logic...
}
// Emits: READS_PARAM {value:"approveRequest", block_start_line:150, block_end_line:200}
```
