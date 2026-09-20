import { useEffect, useState } from "react";
import { api, type Health, type RepoSummary } from "../api/client";

export function Dashboard() {
  const [repos, setRepos]   = useState<RepoSummary[] | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [err, setErr]       = useState<string | null>(null);
  const [info, setInfo]     = useState<string | null>(null);

  // Confirmation-modal state — typed-confirmation pattern: user must type the repoId
  // (or "WIPE") for the destructive button to enable.
  type Pending = { kind: "repo"; repoId: string } | { kind: "all" } | null;
  const [pending,  setPending]  = useState<Pending>(null);
  const [typed,    setTyped]    = useState("");
  const [working,  setWorking]  = useState(false);

  function refresh() {
    Promise.all([api.repos(), api.health()])
      .then(([r, h]) => { setRepos(r); setHealth(h); })
      .catch((e) => setErr((e as Error).message));
  }
  useEffect(() => { refresh(); }, []);

  async function performDelete() {
    if (!pending) return;
    setWorking(true); setErr(null); setInfo(null);
    try {
      if (pending.kind === "repo") {
        const r = await api.deleteRepo(pending.repoId);
        setInfo(`Deleted '${pending.repoId}': ${r.filesDeleted ?? 0} files, ${r.commitsDeleted ?? 0} commits, ${r.repoNodesDeleted ?? 0} repo node(s). Shared class/method nodes preserved.`);
      } else {
        const r = await api.wipeAll();
        setInfo(`Wiped entire graph: ${r.nodesDeleted ?? 0} nodes deleted.`);
      }
      setPending(null);
      setTyped("");
      refresh();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setWorking(false);
    }
  }

  const expected = pending == null
    ? ""
    : pending.kind === "all" ? "WIPE" : pending.repoId;
  const canConfirm = pending != null && typed === expected && !working;

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">Ingested repos</h2>
          <p className="text-sm text-slate-500">
            Snapshots in the connected Neo4j (
            <code className="bg-slate-100 rounded px-1">{health?.neo4jUri ?? "…"}</code>
            {health?.neo4jReachable === false && (
              <span className="text-red-600"> — unreachable: {health.neo4jError}</span>
            )}
            )
          </p>
        </div>
        {repos && repos.length > 0 && (
          <button
            onClick={() => { setPending({ kind: "all" }); setTyped(""); }}
            className="text-sm border border-red-200 text-red-700 hover:bg-red-50 rounded px-3 py-1.5"
            title="Delete every node in the graph"
          >
            Delete entire graph…
          </button>
        )}
      </header>

      {info && (
        <div className="text-sm text-green-800 bg-green-50 border border-green-200 rounded px-3 py-2">{info}</div>
      )}
      {err && (
        <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">{err}</div>
      )}

      {!repos ? (
        <div className="text-sm text-slate-500">Loading…</div>
      ) : repos.length === 0 ? (
        <div className="text-sm text-slate-500">
          No repos ingested yet. Use the <strong>Ingest</strong> tab above, or run{" "}
          <code className="bg-slate-100 rounded px-1">impact ingest</code> from the CLI.
        </div>
      ) : (
        <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-slate-600 text-left">
              <tr>
                <th className="px-4 py-2 font-medium">Repo</th>
                <th className="px-4 py-2 font-medium">Source root</th>
                <th className="px-4 py-2 font-medium">Files</th>
                <th className="px-4 py-2 font-medium">Commits</th>
                <th className="px-4 py-2 font-medium w-24 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {repos.map((r) => (
                <tr key={r.repoId}>
                  <td className="px-4 py-2 font-medium text-slate-900">{r.repoId}</td>
                  <td className="px-4 py-2 text-slate-700 font-mono text-xs">
                    {r.sourceRoot
                      ? r.sourceRoot
                      : <span className="text-slate-400">— (re-ingest to populate)</span>}
                  </td>
                  <td className="px-4 py-2 text-slate-700">{r.fileCount.toLocaleString()}</td>
                  <td className="px-4 py-2 text-slate-700">
                    {r.commits.length === 0
                      ? <span className="text-slate-400">—</span>
                      : <code className="text-xs">{r.commits.join(", ")}</code>}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => { setPending({ kind: "repo", repoId: r.repoId }); setTyped(""); }}
                      className="text-xs text-red-600 hover:underline"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Typed-confirmation modal */}
      {pending && (
        <div
          className="fixed inset-0 bg-slate-900/50 flex items-center justify-center z-50 p-4"
          onClick={() => !working && setPending(null)}
        >
          <div
            className="bg-white rounded-lg shadow-xl border border-slate-200 p-5 max-w-md w-full space-y-3"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-base font-semibold text-slate-900">
              {pending.kind === "all" ? "Delete entire graph?" : `Delete repo '${pending.repoId}'?`}
            </h3>
            {pending.kind === "all" ? (
              <p className="text-sm text-slate-600">
                This wipes every graph node except <code className="bg-slate-100 px-1 rounded">:AppUser</code>
                (so you stay logged in). All ingested repos, classes, methods, REST endpoints, DB tables, test
                cases, etc. are removed. Cannot be undone — you'll need to re-ingest.
              </p>
            ) : (
              <p className="text-sm text-slate-600">
                Removes <code className="bg-slate-100 px-1 rounded">:File</code> nodes tagged{" "}
                <code className="bg-slate-100 px-1 rounded">repo_id = '{pending.repoId}'</code>, the{" "}
                <code className="bg-slate-100 px-1 rounded">:Repo</code> node, and any{" "}
                <code className="bg-slate-100 px-1 rounded">:Commit</code> nodes that aren't shared with another
                repo. Shared class/method nodes are kept (they may belong to other repos). Cannot be undone.
              </p>
            )}
            <p className="text-sm text-slate-600">
              Type <code className="bg-slate-100 px-1 rounded font-mono">{expected}</code> to confirm:
            </p>
            <input
              autoFocus
              className="block w-full rounded border border-slate-300 px-3 py-2 text-sm font-mono"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={expected}
              disabled={working}
            />
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => { setPending(null); setTyped(""); }}
                disabled={working}
                className="text-sm text-slate-700 hover:text-slate-900 px-3 py-1.5"
              >
                Cancel
              </button>
              <button
                onClick={performDelete}
                disabled={!canConfirm}
                className="text-sm bg-red-600 text-white rounded px-3 py-1.5 hover:bg-red-700 disabled:opacity-50"
              >
                {working ? "Deleting…" : "Delete"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
