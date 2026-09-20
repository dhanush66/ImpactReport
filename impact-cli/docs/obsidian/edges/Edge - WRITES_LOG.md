# Edge - WRITES_LOG

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:WRITES_LOG` |
| **From** | [[Node - Method]] |
| **To** | [[Node - LogChannel]] |
| **Layer** | §4.1 Observability (E2) |

## Description

Records that a method writes to a named logger channel (only non-default loggers).

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (l:LogChannel {name: row.name})
MERGE (m)-[:WRITES_LOG]->(l)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:WRITES_LOG]->(l:LogChannel {name: $channelName})
RETURN m.fqn, m.owner_fqn
```
