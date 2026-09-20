# Impact-CLI Graph Schema

The impact-cli ingests Java/JS/C#/HTML/HBS source code and builds a Neo4j property graph used for **patch impact analysis**. This vault documents every node label and relationship type.

## Architecture Layers

| Layer | Purpose |
|-------|---------|
| Core | Source-code structure (packages, files, classes, methods, fields) |
| P4 | Framework boundary (task handlers, REST endpoints, dispatchers) |
| P5 | Database & scripts (tables, columns, PowerShell, JGroups messages) |
| UI | Frontend (HTML pages, JS/Ember files, Handlebars templates, C# agents) |
| §4.1 | Behavioral boundary (notifications, schedules, events, state, permissions…) |
| Testing | Test cases and coverage mapping |

## Quick Links

- [[Nodes Index]] — All 30 node types
- [[Edges Index]] — All 50 relationship types
- [[Schema Constraints]] — Neo4j uniqueness constraints and indexes

## Source Files

| File | Role |
|------|------|
| `model/GraphNodes.java` | Node record definitions |
| `model/GraphEdges.java` | Edge record definitions |
| `graph/Neo4jWriter.java` | Cypher write methods |
| `graph/Schema.java` | Constraint bootstrap |
