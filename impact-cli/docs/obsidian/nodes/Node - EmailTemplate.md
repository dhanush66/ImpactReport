# Node - EmailTemplate

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:EmailTemplate` |
| **Key** | `id` (string) |
| **Layer** | §4.1 Behavioral |
| **Constraint** | `REQUIRE e.id IS UNIQUE` |

## Description

Represents an email template identifier used in direct email sending (distinct from notification-system dispatched emails). Captured when methods reference template IDs via MailSender or similar APIs.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Email template identifier |

## Java Record

```java
public record EmailTemplateNode(String id) {}
```

## Cypher — Create

```cypher
UNWIND $rows AS row MERGE (e:EmailTemplate {id: row.id})
```

## Cypher — Query Examples

```cypher
-- Find methods that use a template
MATCH (m:Method)-[:SENDS_EMAIL]->(e:EmailTemplate {id: $templateId})
RETURN m.fqn, m.owner_fqn
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| IN | [[Edge - SENDS_EMAIL]] | [[Node - Method]] |
