# Worked example — how impact-cli identifies affected URLs

This document walks through, end-to-end, exactly how impact-cli figures out *which REST
URLs* are affected by a code change. The example uses a tiny made-up application — **a
todo-list app** — so you can follow along without any SPMP-specific knowledge.

We'll cover:
1. The sample source tree
2. What the **ingest** step writes to the Neo4j graph
3. A small code change (the **patch**)
4. What the **analyze** step does — every step traced through the graph
5. The resulting APIs Affected section of the report
6. Edge cases and how the tool handles each

---

## 1. The sample app

Imagine a five-file SharePoint-style "todos" web app:

```
todo-app/
├── src/main/java/com/example/todo/
│   ├── TodoController.java         ← Java REST handler (servlet-style)
│   ├── TodoService.java            ← business logic
│   └── TodoDao.java                ← DB access
├── source/ember/app/
│   ├── components/todo-list.js     ← Ember UI component (calls the REST URL)
│   └── templates/components/todo-list.hbs    ← HBS template using the JS component
├── source/c_sharp/
│   └── TodoSyncWorker.cs           ← C# worker that also hits the REST URL
└── product_package/conf/
    ├── RestAPIs.xml                ← REST URL → servlet-class mapping
    └── data-dictionary.xml         ← DB schema (Todos table + 4 columns)
```

### `TodoController.java`

```java
package com.example.todo;

public class TodoController {

    private final TodoService service = new TodoService();

    /** Maps to /api/todos/create via RestAPIs.xml */
    public Response doCreate(Request req) {
        String text = req.param("text");
        long id = service.createTodo(text);
        return Response.ok().body("{\"id\":" + id + "}");
    }

    /** Maps to /api/todos/complete via RestAPIs.xml */
    public Response doComplete(Request req) {
        long id = Long.parseLong(req.param("id"));
        service.markComplete(id);
        return Response.ok();
    }
}
```

### `TodoService.java`

```java
package com.example.todo;

public class TodoService {
    private final TodoDao dao = new TodoDao();

    public long createTodo(String text) {
        return dao.insert(text);    // writes the Todos table
    }
    public void markComplete(long id) {
        dao.updateStatus(id, "DONE");
    }
}
```

### `TodoDao.java`

```java
package com.example.todo;

public class TodoDao {
    public long insert(String text) {
        SelectQuery q = new SelectQueryImpl("Todos");   // ← DbTableResolver picks this up
        // ... actual write ...
        return 42;
    }
    public void updateStatus(long id, String status) {
        UpdateQuery q = new UpdateQueryImpl("Todos");   // ← DbTableResolver picks this up
        // ... actual update ...
    }
}
```

### `RestAPIs.xml`

```xml
<RestAPIs>
  <Api URL="/api/todos/create"   SERVLET_CLASS_NAME="com.example.todo.TodoController" />
  <Api URL="/api/todos/complete" SERVLET_CLASS_NAME="com.example.todo.TodoController" />
</RestAPIs>
```

### `data-dictionary.xml`

```xml
<data-dictionary>
  <table name="Todos">
    <column name="ID"      data-type="BIGINT"  nullable="false"/>
    <column name="TEXT"    data-type="VARCHAR" max-size="500"/>
    <column name="STATUS"  data-type="VARCHAR" max-size="20"/>
    <column name="CREATED" data-type="TIMESTAMP"/>
  </table>
</data-dictionary>
```

### `todo-list.js`

```javascript
import Component from '@ember/component';
import { inject as service } from '@ember/service';

export default Component.extend({
  ajax: service(),

  saveTodo(text) {
    return this.ajax.post('/api/todos/create', { text });
  },

  completeTodo(id) {
    return this.ajax.post('/api/todos/complete', { id });
  }
});
```

### `todo-list.hbs`

```handlebars
<div class="todo-list">
  {{#each this.todos as |todo|}}
    <button {{action 'complete' todo.id}}>{{todo.text}}</button>
  {{/each}}
  <input @value={{this.newText}} />
  <button {{action 'save'}}>Add</button>
</div>
```

### `TodoSyncWorker.cs`

```csharp
namespace TodoApp.Sync {
    public class TodoSyncWorker {
        public void Sync() {
            var http = new HttpClient();
            // C# worker calls back into the REST API to mark todos complete
            var resp = http.PostAsync("/api/todos/complete", payload).Result;
        }
    }
}
```

---

## 2. What `ingest` writes to the graph

`java -jar impact.jar ingest --src todo-app/src/main/java …`

After the run, Neo4j contains (counts approximate):

| Node label | Count | Key examples |
|---|---|---|
| `:File` | 3 (Java) | `TodoController.java`, `TodoService.java`, `TodoDao.java` |
| `:Class` | 3 | `com.example.todo.TodoController`, `…TodoService`, `…TodoDao` |
| `:Method` | 6 | `TodoController.doCreate(Request)`, `TodoController.doComplete(Request)`, `TodoService.createTodo(String)`, `TodoService.markComplete(long)`, `TodoDao.insert(String)`, `TodoDao.updateStatus(long,String)` |
| `:RestEndpoint` | 2 | `/api/todos/create`, `/api/todos/complete` |
| `:DbTable` | 1 | `Todos` |
| `:DbColumn` | 4 | `(Todos,ID)`, `(Todos,TEXT)`, `(Todos,STATUS)`, `(Todos,CREATED)` |
| `:JsFile` | 1 | `components/todo-list.js` (role=Component) |
| `:HbsTemplate` | 1 | `templates/components/todo-list.hbs` |
| `:CsFile` | 1 | `TodoApp/Sync/TodoSyncWorker.cs` |

And these edges:

| Edge | From → To | Count | Origin |
|---|---|---|---|
| `:CALLS` | Method → Method | 4 | core extractor — `doCreate→createTodo→insert`, `doComplete→markComplete→updateStatus` |
| `:CONTAINS` | Class → Method | 6 | core extractor |
| `:EXPOSES` | Class → RestEndpoint | 2 | `RestApiXmlResolver` reading `RestAPIs.xml` |
| `:WRITES_TABLE` | Method → DbTable | 2 | `DbTableResolver` matching `SelectQueryImpl("Todos")` / `UpdateQueryImpl("Todos")` |
| `:HAS_COLUMN` | DbTable → DbColumn | 4 | `DbSchemaXmlResolver` reading `data-dictionary.xml` |
| `:CALLS_API` | JsFile → RestEndpoint | 2 | `JsEmberResolver` — string literals `/api/todos/create`, `/api/todos/complete` in `todo-list.js` |
| `:CALLS_API` | CsFile → RestEndpoint | 1 | `CsSharepointResolver` — `/api/todos/complete` in `TodoSyncWorker.cs` |
| `:USES_COMPONENT` | HbsTemplate → JsFile | 0 | (the HBS uses raw HTML, not a `{{kebab-name}}` component invocation, so no edge) |

Now Neo4j knows:

```
                       ┌────────────────────┐
                       │ TodoController      │
                       │ :Class (no servlet  │
                       │   sub-label — XML   │
                       │   handler)          │
                       └────┬───────────┬────┘
                            │ :EXPOSES   │ :EXPOSES
                            ▼            ▼
              ┌─────────────────┐  ┌────────────────────┐
              │ /api/todos/create│  │ /api/todos/complete│
              └────┬─────────────┘  └─────────┬──────────┘
                   │ :CALLS_API               │ :CALLS_API
                   ▼                          ▼
              ┌────────────┐              ┌────────────────┐
              │ todo-list.js│ ◄──────────►│ TodoSyncWorker  │
              │ (JsFile)    │ (also       │  (CsFile)        │
              └─────────────┘  CALLS_API)  └─────────────────┘
```

Plus the Java internals:

```
   TodoController.doCreate(Request)
     │ :CALLS
     ▼
   TodoService.createTodo(String)
     │ :CALLS
     ▼
   TodoDao.insert(String)
     │ :WRITES_TABLE
     ▼
   :DbTable {name: "Todos"}
     │ :HAS_COLUMN (× 4)
     ▼
   :DbColumn {table: "Todos", name: "ID"}, etc.
```

---

## 3. The patch

A developer modifies the body of `TodoService.markComplete()` to also log a timestamp.
Here's the unified diff:

```diff
--- a/src/main/java/com/example/todo/TodoService.java
+++ b/src/main/java/com/example/todo/TodoService.java
@@ -10,5 +10,7 @@ public class TodoService {

     public void markComplete(long id) {
+        long now = System.currentTimeMillis();
+        AuditLog.write("todo.complete", id, now);
         dao.updateStatus(id, "DONE");
     }
 }
```

Save as `change.patch`. Run:

```powershell
java -jar target/impact.jar analyze `
  --patch change.patch `
  --repo C:\todo-app `
  --output both --out C:\reports\todo-impact.html `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

---

## 4. What `analyze` does — step by step

### Step 4.1: Map the hunk to a symbol

`PatchFileDiffSource.readAll()` parses `change.patch` and yields one `FileChange`:
* path: `src/main/java/com/example/todo/TodoService.java`
* change type: `MODIFY`
* hunks: `[[10, 16]]`

`HunkToSymbolResolver.resolve()` walks the post-image AST of `TodoService.java`. Inside
the hunk range L10–L16 it finds the method `markComplete(long)`. Result:

```
ChangedSymbol {
  filePath:  "src/main/java/com/example/todo/TodoService.java",
  fqn:       "com.example.todo.TodoService.markComplete(long)",
  kind:      METHOD,
  nature:    BODY,
  startLine: 10, endLine: 13
}
```

### Step 4.2: Slice the graph

`SliceExecutor` runs Cypher with `$changed = ["com.example.todo.TodoService.markComplete(long)"]`.

**Forward reach** — `MATCH (m {fqn:$changed})-[:CALLS|DISPATCHES_TO*1..6]->(d) RETURN d.fqn`:

```
TodoDao.updateStatus(long, String)
```

**Backward to entry points** — `MATCH (ep:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m {fqn:$changed})`:

In our sample, `TodoController.doComplete` is NOT marked `:EntryPoint` (we didn't tag
it with a sub-label, because the example uses XML mapping rather than `extends HttpServlet`).
So the backward slice initially returns **0 entry points**.

But the **APIs Affected aggregator** doesn't rely on that — it walks differently:

### Step 4.3: APIs Affected — the key step

The aggregator runs this two-source union (see `SliceExecutor.runAffectedApis`):

**Source A — Java-reached URLs.** For every changed Java method, look at the entry-point
owners reached backward, and collect any URLs those owners expose:

```cypher
MATCH (m:Method)-[:CALLS|DISPATCHES_TO*0..6]-(ep:Method)  // both directions
MATCH (ownerClass:Class)-[:CONTAINS]->(ep)
MATCH (ownerClass)-[:EXPOSES]->(re:RestEndpoint)
WHERE m.fqn IN $changedFqns
RETURN DISTINCT re.url, ownerClass.fqn
```

For `markComplete`, this walks:
1. `markComplete` ← `doComplete` (one upstream call)
2. `doComplete`'s owner class is `TodoController`
3. `TodoController` has `:EXPOSES` to `/api/todos/complete`
4. Also `:EXPOSES` to `/api/todos/create` ← but the slice should only return URLs reachable from the changed method, not all URLs of the owner. The current implementation collects all URLs of any entry-point class on the changed method's call path. **Both URLs surface** because the test cannot distinguish "this URL routes to this method" from "this URL routes to another method on the same class" without per-URL `:HANDLED_BY` edges (a future enhancement).

Result for source A:
```
/api/todos/complete   (owner=TodoController)
/api/todos/create     (owner=TodoController)
```

**Source B — XML-declared URLs.** Iterate over polyglot changes. Our patch only changes
`TodoService.java` — no XML files. So source B is empty.

**Source C — C#-touches URLs.** For each patched C# file, look at its `:CALLS_API`
edges. No `.cs` files in the patch, so source C is empty.

### Step 4.4: Compute features for each URL

For each URL the owner class is consulted. `TodoController` has labels `[Class]` — no
`:Servlet` sub-label, no `:HANDLES` edge.

The fallback chain in `runAffectedApis` kicks in:
1. Servlet-prefix fallback (`servlet:Foo` → Action) — doesn't apply, URL starts with `/`.
2. **Last URL-segment fallback** — splits `/api/todos/complete` on `/` and `?`, picks the last non-empty segment `"complete"`, runs it through `EnglishTranslator.className()`:
   ```
   FeatureRef { kind: "Action", id: "complete", displayName: "complete" }
   ```

Similarly `/api/todos/create` → `FeatureRef { kind: "Action", id: "create", displayName: "create" }`.

### Step 4.5: Cross-reference UI callers

For each URL, the aggregator runs:

```cypher
MATCH (re:RestEndpoint {url: $url})
OPTIONAL MATCH (j:JsFile)-[:CALLS_API]->(re)
OPTIONAL MATCH (cs:CsFile)-[:CALLS_API]->(re)
RETURN collect(DISTINCT j.simple_name) AS js,
       collect(DISTINCT cs.simple_name) AS cs
```

For `/api/todos/complete`:
* JS callers: `["todo-list"]` (from `todo-list.js` calling `this.ajax.post('/api/todos/complete', …)`)
* C# callers: `["TodoSyncWorker"]` (from the `http.PostAsync("/api/todos/complete", …)` literal)

For `/api/todos/create`:
* JS callers: `["todo-list"]`
* C# callers: `[]`

---

## 5. The resulting APIs Affected section

After all the slicing, the HTML report shows:

```
| URL                    | Feature              | Source       | Owner          | JS callers | C# callers       | HTML refs | Reached by | Risk   |
| ---------------------- | -------------------- | ------------ | -------------- | ---------- | ---------------- | --------- | ---------- | ------ |
| /api/todos/complete    | Action: complete     | java-reached | TodoController | todo-list  | TodoSyncWorker   | —         | 1 method   | MEDIUM |
| /api/todos/create      | Action: create       | java-reached | TodoController | todo-list  | —                | —         | 1 method   | MEDIUM |
```

The companion sections:

### Database Tables Affected

```
| Table | Feature                                              | Source       | Writers                | Readers | Risk   |
| ----- | ---------------------------------------------------- | ------------ | ---------------------- | ------- | ------ |
| Todos | Action: complete, Action: create                     | java-reached | 2 (TodoDao.insert(), TodoDao.updateStatus()) | — | MEDIUM |
```

The aggregator walks: `TodoService.markComplete` → forward-reaches `TodoDao.updateStatus`
which `:WRITES_TABLE` `Todos`. The feature attribution walks back through entry-point
owners (here only `TodoController` whose URLs we just classified) and uses their features.

### UI Components Affected

```
| File                                        | Feature              | Lang | Role               | Source              | URLs called             | Risk   |
| ------------------------------------------- | -------------------- | ---- | ------------------ | ------------------- | ----------------------- | ------ |
| components/todo-list.js                     | Action: complete     | JS   | Component          | calls-affected-api  | /api/todos/complete     | MEDIUM |
```

The HBS template `todo-list.hbs` is **not** affected because it doesn't have a
`{{component-name}}` invocation to any patched JS component.

### Generated Test Cases

```
## MISC

| ID            | Title                                          | Steps                                                                  | Expected                                            |
| ------------- | ---------------------------------------------- | ---------------------------------------------------------------------- | --------------------------------------------------- |
| GEN-MISC-001  | Verify the todo-list UI component (Ember)      | 1. Open the screen that uses the todo-list.js component. 2. Mark a todo complete. 3. Watch the browser console. | Component renders and responds to the action; no console errors. The call to /api/todos/complete returns 200. |
```

---

## 6. Edge cases

### What if the patch touches a brand-new file?

Example: the patch ADDs `TodoNotificationService.java` with three new methods. The
patch hunk covers lines 1–N of the whole file. `HunkToSymbolResolver` would normally
return the smallest enclosing symbol (the class) — which would be unhelpful. The
"inside-first" priority kicks in (see [ARCHITECTURE.md](ARCHITECTURE.md#7-extension-points)):
the resolver returns one `ChangedSymbol` per method inside the hunk, dropping the
wrapping class. So a newly-added class with 3 methods produces 3 method-level entries,
not 1 class-level entry.

### What if the patch deletes a method?

`DeletedJavaSymbolScanner` reads the raw `-` lines from the patch and recognises Java
method/constructor/class signatures via regex. The result is a `ChangedSymbol` with
`nature=DELETED`. The slice queries skip it (no graph node), but the report's
"**Deleted Symbols**" panel lists every deleted FQN with a warning: *"callers still
referencing them will break — verify each FQN has zero usages after the patch."*

### What if the URL is declared in XML and not in any Java class?

Patch modifies `RestAPIs.xml` to add `<Api URL="/api/todos/archive" SERVLET_CLASS_NAME="…"/>`.

* No Java symbol is in the patch, so the forward/backward slices have nothing to do.
* `PatchFileDiffSource.readAll()` returns the XML as a `FileChange`.
* `PolyglotResolver.resolveXml()` reads the disk file inside the hunk lines, applies
  the URL regex (`API_URL="…"` and `<api-url>`), extracts `/api/todos/archive`.
* The aggregator's "source B — XML-declared URLs" picks it up.
* APIs Affected shows it with `source=xml-declared`. Feature: `Action: archive` (URL last
  segment fallback). JS / C# callers from the graph if any exist.

### What if a C# file in the patch calls a SharePoint URL?

The C# `_api/web/…` pattern is captured by `CsSharepointResolver` at ingest time and
stored as a `:RestEndpoint` node with URL prefixed `external:sharepoint:`. The patched
C# file is processed by `PolyglotResolver.resolveCs()`. The aggregator's "source C —
C#-touches URLs" pulls every external URL the file calls and surfaces them in APIs
Affected with `source=c#-touches` and `Feature: ExternalApi: SharePoint CSOM`.

---

## 7. Quick reference — what causes a URL to appear in the report

| Cause | Section it appears in | Source value |
|---|---|---|
| Patch modifies a Java method on a class that `:EXPOSES` the URL | APIs Affected | `java-reached` |
| Patch declares/changes the URL in a REST-config XML hunk (`RestAPIs.xml`, `ServletActions.xml`, `web.xml`) | APIs Affected | `xml-declared` |
| Patch modifies a C# file that has a string literal for the URL | APIs Affected | `c#-touches` |
| Patch modifies a JS file that calls the URL — and the change reaches a Java owner backwards | UI Components Affected | `calls-affected-api` |
| HBS template uses a patched JS component | UI Components Affected | `uses-affected-component` |

Multiple causes can apply at once — the source column will read e.g. `java+xml+c#` or
`both` for a URL touched by all three pipelines.

---

For the architecture this rides on see [ARCHITECTURE.md](ARCHITECTURE.md). For the full
CLI reference see [USAGE.md](USAGE.md). For interpreting a real report end-to-end see
[REPORT_GUIDE.md](REPORT_GUIDE.md).
