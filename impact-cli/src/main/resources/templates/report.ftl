<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Impact Report — ${diffSource}</title>
<style>
  :root {
    --bg: #0f1419; --panel: #15191f; --panel2: #1c222a; --text: #e6e6e6;
    --muted: #9aa1a8; --accent: #5cc8ff; --hi: #ff5c5c; --med: #ffb86c; --low: #7ed957;
    --border: #2a3038;
  }
  * { box-sizing: border-box; }
  body { font: 14px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 24px;
         background: var(--bg); color: var(--text); }
  h1 { font-size: 22px; margin: 0 0 4px; }
  h2 { font-size: 16px; margin: 24px 0 8px; color: var(--accent); border-bottom: 1px solid var(--border); padding-bottom: 4px; }
  .meta { color: var(--muted); margin-bottom: 16px; font-size: 12px; }
  .meta strong { color: var(--text); }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
  .card { background: var(--panel); border: 1px solid var(--border); border-radius: 6px; padding: 12px; }
  .card .label { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.05em; }
  .card .value { font-size: 22px; font-weight: 600; margin-top: 4px; }
  .pill { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px;
          font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; vertical-align: middle; }
  .pill-critical { background: rgba(211,47,47,0.22); color: #d32f2f; }
  .pill-high   { background: rgba(255,92,92,0.18);  color: var(--hi); }
  .pill-medium { background: rgba(255,184,108,0.18); color: var(--med); }
  .pill-low    { background: rgba(126,217,87,0.18); color: var(--low); }
  .pill-added     { background: rgba(92,200,255,0.15); color: var(--accent); }
  .pill-signature { background: rgba(255,184,108,0.15); color: var(--med); }
  .pill-deleted   { background: rgba(180,76,76,0.18);  color: #c44; }
  .pill-feat      { background: rgba(92,200,255,0.12); color: var(--accent); margin: 1px 2px; display: inline-block; }
  .pill-body      { background: rgba(154,161,168,0.18); color: var(--muted); }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); vertical-align: top; }
  th { background: var(--panel2); color: var(--muted); font-weight: 600; font-size: 12px;
       text-transform: uppercase; letter-spacing: 0.05em; }
  tr.hi { background: rgba(255,92,92,0.06); }
  code { background: var(--panel2); padding: 1px 5px; border-radius: 3px; font-size: 12px;
         font-family: "JetBrains Mono", Consolas, monospace; }
  details { margin: 4px 0; }
  details summary { cursor: pointer; color: var(--muted); font-size: 12px; }
  ul { margin: 4px 0 0 0; padding-left: 18px; }
  li { font-size: 12px; color: var(--text); }
  li .ep-meta { color: var(--muted); font-size: 11px; margin-left: 6px; }
  .empty { color: var(--muted); font-style: italic; }
  /* Pivot section ("Affected by Task / Action") styling */
  td.risk-CRITICAL { color: #d32f2f; font-weight: 700; }
  td.risk-HIGH   { color: var(--hi);   font-weight: 600; }
  td.risk-MEDIUM { color: var(--med);  font-weight: 600; }
  td.risk-LOW    { color: var(--low);  font-weight: 600; }
  .count { display: inline-block; padding: 0 6px; border-radius: 3px;
           background: var(--panel2); color: var(--muted); font-size: 11px;
           font-weight: 600; margin-right: 4px; vertical-align: top; }
  ul.affected-list { margin: 2px 0 0 0; padding-left: 14px; max-height: 8em; overflow-y: auto; }
  ul.affected-list li { font-size: 11px; line-height: 1.4; }
  /* Discovery path (collapsible graph visualization) */
  .discovery-row td { border-top: none !important; padding-top: 0 !important; }
  .discovery-details { margin: 2px 0 6px; }
  .discovery-details summary { font-size: 11px; color: var(--muted); cursor: pointer; user-select: none; }
  .discovery-details summary:hover { color: var(--text); }
  .graph-path { display: flex; align-items: center; flex-wrap: wrap; gap: 0; margin: 6px 0 2px 4px; font-size: 11px; }
  .gp-node { display: inline-flex; flex-direction: column; align-items: center; border: 1.5px solid var(--border);
    border-radius: 20px; padding: 3px 10px; background: var(--panel); min-width: 40px; text-align: center; }
  .gp-node.highlighted { border-color: #e67e22; background: #fef3e7; box-shadow: 0 0 0 2px rgba(230,126,34,0.18); }
  .gp-type { font-size: 9px; font-weight: 700; text-transform: uppercase; color: var(--muted); letter-spacing: 0.3px; }
  .gp-name { font-size: 11px; font-weight: 500; color: var(--text); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .gp-node.highlighted .gp-name { color: #d35400; font-weight: 700; }
  .gp-edge { display: inline-flex; align-items: center; padding: 0 3px; font-size: 10px; color: var(--muted); white-space: nowrap; }
  .gp-edge::before { content: ''; display: inline-block; width: 18px; height: 1.5px; background: var(--muted); margin-right: 2px; }
  .gp-edge::after { content: '▸'; margin-left: 2px; font-size: 11px; }
  .gp-pattern { font-size: 10px; color: var(--muted); margin-bottom: 3px; font-style: italic; }
</style>
</head>
<body>
<#-- Render a comma-separated list of FeatureRef maps with kind+displayName. -->
<#macro features feats>
  <#if feats?? && feats?size gt 0>
    <#list feats as f><span class="pill pill-feat">${f.kind}: <b>${f.displayName}</b></span><#sep> </#list>
  <#else><span class="empty">—</span></#if>
</#macro>
  <h1>Impact Report
    <span class="pill pill-${overallRisk?lower_case}">${overallRisk}</span>
  </h1>
  <div class="meta">
    <strong>Generated:</strong> ${generated}
    &nbsp;&nbsp;<strong>Diff:</strong> ${diffSource}
    &nbsp;&nbsp;<strong>Repo:</strong> <code>${repoPath}</code>
  </div>

  <div class="grid">
    <div class="card">
      <div class="label">Changed Symbols</div>
      <div class="value">${totalChangedSymbols}</div>
    </div>
    <div class="card">
      <div class="label">API Entry Points Reached</div>
      <div class="value">${totalEntryPointsReachedApi}</div>
    </div>
    <div class="card">
      <div class="label">Schedule Entry Points Reached</div>
      <div class="value">${totalEntryPointsReachedSchedule}</div>
    </div>
    <#-- <div class="card">
      <div class="label">Forward Reach Total</div>
      <div class="value">${totalForwardReach}</div>
    </div> -->
    <div class="card">
      <div class="label">Risk Breakdown</div>
      <div class="value">
        <span class="pill pill-high">${riskCounts.HIGH!0}</span>
        <span class="pill pill-medium">${riskCounts.MEDIUM!0}</span>
        <span class="pill pill-low">${riskCounts.LOW!0}</span>
      </div>
    </div>
  </div>



 

  <#-- ─── AFF: APIs Affected ──────────────────────────────────────────────── -->
  <#if apisAffected?? && apisAffected?size gt 0>
    <h2>APIs Affected (${apisAffected?size})</h2>
    <p class="meta" style="margin-top: -4px;">
      REST endpoints touched by this patch — either declared/changed in a config XML hunk, or exposed by a Java class that the call graph reaches from a changed method.
      Existing JS/C#/HTML callers are listed so QA knows which UI surfaces to re-verify.
    </p>
    <table>
      <thead>
        <tr>
          <th>URL</th>
          <th>Source</th>
          <th>Owner</th>
          <th>Reached by</th>
          <th>Risk</th>
        </tr>
      </thead>
      <tbody>
        <#list apisAffected as a>
        <tr class="risk-${a.risk?lower_case}">
          <td><code>${a.url}</code></td>
          <td><span class="pill">${a.source}</span></td>
          <td>
            <#if a.ownerSimpleName?? && a.ownerSimpleName?length gt 0>
              <code>${a.ownerSimpleName}</code>
              <#if a.ownerClassFqn?? && a.ownerClassFqn?length gt 0>
                <br><span class="meta">${a.ownerClassFqn}</span>
              </#if>
            <#else><span class="empty">—</span></#if>
          </td>
          <td>${a.changedSymbolsReaching} changed method<#if a.changedSymbolsReaching != 1>s</#if></td>
          <td><span class="pill pill-${a.risk?lower_case}">${a.risk}</span></td>
        </tr>
        <#if a.discoveryPaths?? && a.discoveryPaths?size gt 0>
        <tr class="discovery-row">
          <td colspan="10" style="padding: 0 12px 6px;">
            <details class="discovery-details">
              <summary>Discovery path (${a.discoveryPaths?size} pattern<#if a.discoveryPaths?size != 1>s</#if>)</summary>
              <#list a.discoveryPaths as dp>
              <div class="gp-pattern">${dp.pattern}</div>
              <div class="graph-path">
                <#list dp.nodes as node>
                  <div class="gp-node<#if node.highlighted> highlighted</#if>">
                    <span class="gp-type">${node.nodeType}</span>
                    <span class="gp-name" title="${node.name}">${node.name}</span>
                  </div>
                  <#if node?has_next>
                    <div class="gp-edge">${dp.edges[node?index]}</div>
                  </#if>
                </#list>
              </div>
              </#list>
            </details>
          </td>
        </tr>
        </#if>
        </#list>
      </tbody>
    </table>
  </#if>

  <#-- ─── AFF: Schedules Affected ──────────────────────────────────────────────── -->
  <#if schedulesAffected?? && schedulesAffected?size gt 0>
    <h2>Schedules Affected (${schedulesAffected?size})</h2>
    <p class="meta" style="margin-top: -4px;">
      REST endpoints touched by this patch — either declared/changed in a config XML hunk, or exposed by a Java class that the call graph reaches from a changed method.
      Existing JS/C#/HTML callers are listed so QA knows which UI surfaces to re-verify.
    </p>
    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th>Source</th>
          <th>Owner</th>
          <th>Reached by</th>
          <th>Risk</th>
        </tr>
      </thead>
      <tbody>
        <#list schedulesAffected as a>
        <tr class="risk-${a.risk?lower_case}">
          <td><code>${a.name}</code></td>
          <td><span class="pill">${a.source}</span></td>
          <td>
            <#if a.ownerSimpleName?? && a.ownerSimpleName?length gt 0>
              <code>${a.ownerSimpleName}</code>
              <#if a.ownerClassFqn?? && a.ownerClassFqn?length gt 0>
                <br><span class="meta">${a.ownerClassFqn}</span>
              </#if>
            <#else><span class="empty">—</span></#if>
          </td>
          <td>${a.changedSymbolsReaching} changed method<#if a.changedSymbolsReaching != 1>s</#if></td>
          <td><span class="pill pill-${a.risk?lower_case}">${a.risk}</span></td>
        </tr>
        <#if a.discoveryPaths?? && a.discoveryPaths?size gt 0>
        <tr class="discovery-row">
          <td colspan="10" style="padding: 0 12px 6px;">
            <details class="discovery-details">
              <summary>Discovery path (${a.discoveryPaths?size} pattern<#if a.discoveryPaths?size != 1>s</#if>)</summary>
              <#list a.discoveryPaths as dp>
              <div class="gp-pattern">${dp.pattern}</div>
              <div class="graph-path">
                <#list dp.nodes as node>
                  <div class="gp-node<#if node.highlighted> highlighted</#if>">
                    <span class="gp-type">${node.nodeType}</span>
                    <span class="gp-name" title="${node.name}">${node.name}</span>
                  </div>
                  <#if node?has_next>
                    <div class="gp-edge">${dp.edges[node?index]}</div>
                  </#if>
                </#list>
              </div>
              </#list>
            </details>
          </td>
        </tr>
        </#if>
        </#list>
      </tbody>
    </table>
  </#if>

  <#-- ─── AFF: Schedules Affected ────────────────────────────────────────── -->
  <#-- ALWAYS render this section (like URLs / DB tables / UI components). When empty,
       show an explicit "no candidates" message so QA knows the analyzer looked and
       came up clean — vs. simply omitting the section, which is ambiguous. -->
  <#-- <h2 id="affected-schedules">Schedules Affected (${(schedulesAffected!?size)!0})</h2>
  <p class="meta" style="margin-top: -4px;">
    Scheduled jobs, cluster task handlers, and management schedulers that this patch can affect. The <b>Source</b>
    column tells you HOW: <code>patch-added</code> / <code>patch-modified</code> = the schedule's own code is in the
    diff; <code>reached</code> = the backward call-graph slice from the patched method hits the schedule;
    <code>schedules</code> = the patch's forward reach contains an explicit <code>:SCHEDULES</code> call to this task
    (control-flow proof of async dispatch); <code>shares-data</code> = async-only coupling — the schedule reads or
    writes a DB table the patched code also touches, but neither calls the other directly.
    Each row names the DB tables the schedule writes/reads and (for task handlers) the TaskType ids it registers for.
  </p>
  <#if schedulesAffected?? && schedulesAffected?size gt 0>
    <table>
      <thead>
        <tr>
          <th>Owner</th>
          <th>Kind</th>
          <th>Source</th>
          <th>Task types</th>
          <th>DB writes / reads</th>
          <th>Reached by</th>
          <th>Risk</th>
        </tr>
      </thead>
      <tbody>
        <#list schedulesAffected as s>
        <tr class="risk-${s.risk?lower_case}">
          <td>
            <code>${s.ownerSimpleName}</code>
            <br><span class="meta">${s.ownerClassFqn}</span>
          </td>
          <td><span class="pill">${s.kind}</span></td>
          <td><span class="pill">${s.source}</span></td>
          <td>
            <#if s.dbTablesWritten?size gt 0>
              <div><b>writes:</b> <#list s.dbTablesWritten as t><code>${t}</code><#sep>, </#list></div>
            </#if>
            <#if s.dbTablesRead?size gt 0>
              <div><b>reads:</b> <#list s.dbTablesRead as t><code>${t}</code><#sep>, </#list></div>
            </#if>
            <#if !(s.dbTablesWritten?size gt 0) && !(s.dbTablesRead?size gt 0)><span class="empty">—</span></#if>
          </td>
          <td>${s.changedSymbolsReaching} changed method<#if s.changedSymbolsReaching != 1>s</#if></td>
          <td><span class="pill pill-${s.risk?lower_case}">${s.risk}</span></td>
        </tr>
        </#list>
      </tbody>
    </table>
  <#else>
    <p class="meta" style="margin: 6px 0 18px; padding: 10px 12px; background: var(--panel); border: 1px solid var(--border); border-radius: 4px;">
      <b>No scheduled jobs / task handlers identified for this patch.</b>
      The analyzer searched for: (a) Scheduler/Job/TaskHandler classes reached by the
      backward call-graph slice from the patched method; (b) any of the patched classes
      themselves carrying those labels; and (c) Scheduler-like classes (name suffix
      <code>Task</code>/<code>Job</code>/<code>Scheduler</code>/<code>ScheduleHandler</code>,
      or containing <code>run</code>/<code>execute</code>/<code>executeTask</code>/<code>runTask</code>
      methods, or with <code>:EntryPoint</code>-tagged methods) that read/write a DB table
      the patched code also touches.
      <br><br>
      If you believe a schedule IS affected (e.g. a non-URL entry point like an expiry-driven
      auto-action, or an HDT/automation reject path), add it manually to your test plan —
      static analysis can't enumerate scheduler-triggered or runtime-dispatched entry points.
    </p>
  </#if> -->

  <#-- ─── AFF: Database Tables Affected ──────────────────────────────────── -->
  <#--<#if dbTablesAffected?? && dbTablesAffected?size gt 0>
    <h2>Database Tables Affected (${dbTablesAffected?size})</h2>
    <p class="meta" style="margin-top: -4px;">
      DB tables touched by this patch — either with a schema change (data-dictionary.xml hunk) or written/read by Java methods the slice reaches.
      <b>Feature</b> names which user-visible operation (TaskType / Report / Action / Scheduler) uses the table.
    </p>
    <table>
      <thead>
        <tr>
          <th>Table</th>
          <th>Feature</th>
          <th>Source</th>
          <th>Changed columns</th>
          <th>Writers</th>
          <th>Readers</th>
          <th>Risk</th>
        </tr>
      </thead>
      <tbody>
        <#list dbTablesAffected as t>
        <tr class="risk-${t.risk?lower_case}<#if t.source == 'regression-safety'> regression-safety</#if>"<#if t.source == 'regression-safety'> style="opacity:0.85;"</#if>>
          <td><code>${t.name}</code><#if t.source == 'regression-safety'><br><span class="meta" style="font-style:italic;">no direct writes from patched code — async schedules consume this state (see <a href="#affected-schedules">Affected Schedules</a> above)</span></#if></td>
          <td><@features t.features /></td>
          <td><span class="pill<#if t.source == 'regression-safety'> pill-low</#if>">${t.source}</span></td>
          <td>
            <#if t.changedColumns?? && t.changedColumns?size gt 0>
              <#list t.changedColumns as c><code>${c}</code><#sep>, </#list>
            <#else><span class="empty">—</span></#if>
          </td>
          <td>
            <#if t.writerCount gt 0>
              <b>${t.writerCount}</b> method<#if t.writerCount != 1>s</#if>
              <#if t.sampleWriters?size gt 0>
                <br><span class="meta"><#list t.sampleWriters as w><code>${w}</code><#sep>, </#list></span>
              </#if>
            <#else><span class="empty">—</span></#if>
          </td>
          <td>
            <#if t.readerCount gt 0>
              <b>${t.readerCount}</b> method<#if t.readerCount != 1>s</#if>
              <#if t.sampleReaders?size gt 0>
                <br><span class="meta"><#list t.sampleReaders as r><code>${r}</code><#sep>, </#list></span>
              </#if>
            <#else><span class="empty">—</span></#if>
          </td>
          <td><span class="pill pill-${t.risk?lower_case}">${t.risk}</span></td>
        </tr>
        </#list>
      </tbody>
    </table>
  </#if>-->



  <#-- Dedicated panel for DELETED symbols: graph-wise these don't fan out (they no longer
       exist as nodes), so showing them in the Symbols table below would be misleading. -->
  <#assign deletedSymbols = []>
  <#list symbols as s>
    <#if s.nature == 'DELETED'>
      <#assign deletedSymbols = deletedSymbols + [s]>
    </#if>
  </#list>
  <#if deletedSymbols?size gt 0>
    <h2>Deleted Symbols (${deletedSymbols?size})</h2>
    <p class="meta" style="margin-top: -4px;">
      Java methods, constructors, and classes <b>removed</b> by this patch. They no longer
      exist in the post-patch graph, so the slice queries above can't fan out from them — but
      <b>callers still referencing them will break</b>. Verify each FQN below has zero usages
      in the codebase after the patch is applied.
    </p>
    <table>
      <thead>
        <tr><th>Kind</th><th>FQN</th><th>File</th><th>Line</th></tr>
      </thead>
      <tbody>
        <#list deletedSymbols as ds>
          <tr>
            <td><span class="pill pill-deleted">${ds.kind}</span></td>
            <td><code>${ds.fqn}</code></td>
            <td><code>${ds.filePath}</code></td>
            <td>${ds.startLine}</td>
          </tr>
        </#list>
      </tbody>
    </table>
  </#if>

  

  <h2>Symbol Details (${symbols?size})</h2>
  <table>
    <thead>
      <tr>
        <th>Risk</th>
        <th>Symbol</th>
        <th>Kind / Nature</th>
        <th>API Entry Points</th>
        <th>Schedule Entry Points</th>
        <#-- <th>Forward Reach</th> -->
        <#-- <th>DB Tables</th> -->
      </tr>
    </thead>
    <tbody>
    <#list symbols as s>
      <tr class="<#if s.risk == 'HIGH'>hi</#if>">
        <td><span class="pill pill-${s.risk?lower_case}">${s.risk}</span></td>
        <td>
          <code>${s.fqn}</code>
          <div class="meta" style="margin: 4px 0 0;">
            <#if s.hunkStartLine?? && s.hunkEndLine?? && (s.hunkStartLine != s.startLine || s.hunkEndLine != s.endLine)>
              ${s.filePath}: hunk L${s.hunkStartLine}<#if s.hunkEndLine != s.hunkStartLine>-${s.hunkEndLine}</#if> (in symbol L${s.startLine}-${s.endLine})
            <#else>
              ${s.filePath}:${s.startLine}-${s.endLine}
            </#if>
            <#if s.sensitivePackage> &middot; <span style="color: var(--hi);">sensitive package</span></#if>
          </div>
          <#if s.likelyUserVisibleEffect?? && s.likelyUserVisibleEffect?length gt 0>
            <div class="meta" style="margin: 4px 0 0; color: var(--lo);">
              <b>Likely user-visible effect:</b> ${s.likelyUserVisibleEffect}
            </div>
          </#if>
        </td>
        <td>
          <span class="pill pill-${s.nature?lower_case}">${s.nature}</span>
          <div class="meta" style="margin: 4px 0 0;">${s.kind}</div>
        </td>
        <td>
          <#if s.entryPointsApi?size == 0>
            <span class="empty">none</span>
          <#else>
            ${s.entryPointsApi?size}
            <details>
              <summary>show</summary>
              <ul>
                <#list s.entryPointsApi as ep>
                  <li>
                    <code>${ep.fqn}</code>
                    <span class="ep-meta">${ep.labels?join(',')}</span>
                    <#if ep.restUrls?? && ep.restUrls?size gt 0>
                      <div class="ep-meta">URLs: <#list ep.restUrls as u><code>${u}</code><#sep>, </#list></div>
                    </#if>
                  </li>
                </#list>
              </ul>
            </details>
          </#if>
        </td>
        <td>
          <#if s.entryPointsSchedule?size == 0>
            <span class="empty">none</span>
          <#else>
            ${s.entryPointsSchedule?size}
            <details>
              <summary>show</summary>
              <ul>
                <#list s.entryPointsSchedule as ep>
                  <li>
                    <code>${ep.fqn}</code>
                    <span class="ep-meta">${ep.labels?join(',')}</span>
                    <#if ep.restUrls?? && ep.restUrls?size gt 0>
                      <div class="ep-meta">Scheduled Task: <#list ep.restUrls as t><code>${t}</code><#sep>, </#list></div>
                    </#if>
                  </li>
                </#list>
              </ul>
            </details>
          </#if>
        </td>
        <#-- <td>
          <#if s.forwardReach?size == 0>
            <span class="empty">none</span>
          <#else>
            ${s.forwardReach?size}
            <details>
              <summary>show first 20</summary>
              <ul>
                <#list s.forwardReach[0..*20] as r>
                  <li><code>${r}</code></li>
                </#list>
              </ul>
            </details>
          </#if>
        </td> -->
        <#-- <td>
          <#if (s.readsTables?size + s.writesTables?size) == 0>
            <span class="empty">none</span>
          <#else>
            <#if s.readsTables?size gt 0>R: ${s.readsTables?join(', ')}<br/></#if>
            <#if s.writesTables?size gt 0>W: ${s.writesTables?join(', ')}</#if>
          </#if>
        </td> -->
      </tr>
    </#list>
    </tbody>
  </table>
</body>
</html>
