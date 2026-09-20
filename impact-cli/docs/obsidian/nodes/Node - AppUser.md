# Node - AppUser

## Overview

| Property | Value |
|----------|-------|
| **Label** | `:AppUser` |
| **Key** | `username` (string) |
| **Layer** | Web App (P9.6) |
| **Constraint** | `REQUIRE u.username IS UNIQUE` |

## Description

Represents an application user in the web-app user store. Used by the impact-web frontend for authentication and session management.

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `username` | String | Login username |

## Cypher — Create

```cypher
MERGE (u:AppUser {username: $username})
  SET u.password_hash = $hash, u.role = $role
```

## Cypher — Query Examples

```cypher
-- Find user by username
MATCH (u:AppUser {username: $username})
RETURN u.username, u.role
```

## Relationships

| Direction | Edge | Target |
|-----------|------|--------|
| — | (none defined in graph schema) | — |
