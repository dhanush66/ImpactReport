# Edge - SENDS_NOTIFICATION

## Overview

| Property | Value |
|----------|-------|
| **Type** | `:SENDS_NOTIFICATION` |
| **From** | [[Node - Method]] |
| **To** | [[Node - NotificationType]] |
| **Layer** | §4.1 Communication |

## Description

Records that a method triggers a notification dispatch (via `triggerNotification(...)` or `NotificationTrigger.start()`). Connects the triggering code to the notification type being sent.

## Edge Properties

None.

## Java Record

```java
public record SendsNotificationEdge(String fromMethodFqn, String notificationTypeId) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row
MERGE (m:Method {fqn: row.from})
  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple
MERGE (n:NotificationType {id: row.id})
MERGE (m)-[:SENDS_NOTIFICATION]->(n)
```

## Cypher — Query

```cypher
-- Find what triggers a notification
MATCH (m:Method)-[:SENDS_NOTIFICATION]->(n:NotificationType {id: $notifId})
RETURN m.fqn, m.owner_fqn

-- Impact: notification types affected by a class change
MATCH (c:Class {fqn: $classFqn})-[:CONTAINS]->(m:Method)-[:SENDS_NOTIFICATION]->(n:NotificationType)
RETURN DISTINCT n.id
```

## ADMP Code Example

```java
// In WorkFlowAction.approveRequest():
MgmtNotificationListener.triggerNotification(list);
// This emits: approveRequest() -[:SENDS_NOTIFICATION]-> NotificationType{id: "WF_APPROVED"}
```
