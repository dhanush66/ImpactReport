# Edge - READS_PROPERTY

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:READS_PROPERTY` |
| **From** | [[Node - Method]] |
| **To** | [[Node - Property]] |
| **Layer** | §4.1 Configuration (C1) |

## Description

Records that a method reads a configuration property via `System.getProperty(...)`, `ADSMPersUtil.getSyMParameter(...)`, `ConfigManager.get(...)`, or `@Value`.

## Edge Properties

None.

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (p:Property {key: row.key})
MERGE (m)-[:READS_PROPERTY]->(p)
```

## Cypher — Query

```cypher
MATCH (m:Method)-[:READS_PROPERTY]->(p:Property {key: "SHARE_PASSWORD_AFTER_WFTASK_EXECUTION"})
RETURN m.fqn, m.owner_fqn
```

## ADMP Code Example

```java
// In WFNotificationMacro.getUserDetails():
if(!Boolean.parseBoolean(ADSMPersUtil.getSyMParameter("SHARE_PASSWORD_AFTER_WFTASK_EXECUTION")) ...)
// Emits: getUserDetails() -[:READS_PROPERTY]-> Property{key: "SHARE_PASSWORD_AFTER_WFTASK_EXECUTION"}
```
