import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type IngestRequest } from "../api/client";
import { Spinner } from "../components/Spinner";

/**
 * P10/P9.8 — Ingest page. Mirrors the CLI `impact ingest` flags as a form, then
 * kicks off an async job via POST /api/v1/ingest. On success, navigates to the
 * job detail page so the user can watch the log stream live over WebSocket.
 */
export function IngestPage() {
  const nav = useNavigate();
  const [srcRoot, setSrcRoot]       = useState("");
  const [repoId,  setRepoId]        = useState("");
  const [commit,  setCommit]        = useState("HEAD");
  const [busy, setBusy] = useState(false);
  const [err,  setErr]  = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setErr(null); setBusy(true);
    try {
      const body: IngestRequest = {
        srcRoot,
        commit: commit || "HEAD",
        repoId: repoId || undefined,
      };
      const r = await api.startIngest(body);
      // Navigate to the job detail page → live WS-tailed log
      nav(`/jobs/${r.jobId}`);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <header>
        <h2 className="text-lg font-semibold text-slate-900">Ingest a repository</h2>
        <p className="text-sm text-slate-500">
          Parses a source tree into the Neo4j call-graph. Runs as a background job —
          you'll be sent to its live log on submit.
        </p>
      </header>

      <form onSubmit={onSubmit} className="rounded-lg border border-slate-200 bg-white p-4 space-y-4 max-w-2xl">

        {/* ── Primary repo ── */}
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-slate-700">Primary repo</h3>
          <TextField
            label="Repository root *"
            value={srcRoot} onChange={setSrcRoot}
            placeholder="C:\proj\my-product   (the top-level dir; sub-modules auto-detected)"
            required
          />
          <p className="text-xs text-slate-500 -mt-2">
            Point at the repository's top directory. The tool auto-detects every Java source sub-module
            (<code>source/java</code>, <code>source/java_source</code>, <code>src/main/java</code>,
            <code>web/&lt;app&gt;/src</code>) and ingests them all under one repo ID — you don't need to
            register sub-modules as separate dependencies.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <TextField label="Repo ID *"                       value={repoId}  onChange={setRepoId}  placeholder="adsm" required />
            <TextField label="Commit / tag (default HEAD)"     value={commit}  onChange={setCommit}  placeholder="HEAD" />
          </div>
        </section>

        {err && (
          <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">{err}</div>
        )}

        <button
          type="submit"
          disabled={busy || !srcRoot || !repoId}
          className="inline-flex items-center gap-2 bg-slate-900 text-white rounded px-4 py-2 text-sm font-medium hover:bg-slate-800 disabled:opacity-50"
        >
          {busy && <Spinner className="text-white" />}
          {busy ? "Submitting…" : "Start ingest"}
        </button>
        {busy && (
          <div className="flex items-center gap-2 text-sm text-slate-600 bg-slate-50 border border-slate-200 rounded px-3 py-2">
            <Spinner className="text-slate-700" />
            <span>Creating job… you'll be redirected to its live log once the server confirms.</span>
          </div>
        )}
        <p className="text-xs text-slate-500">
          Submitting creates a background job. You'll be redirected to its live log; the page is safe
          to close, the job keeps running.
        </p>
      </form>
    </div>
  );
}

function TextField({ label, value, onChange, placeholder, required }: {
  label: string; value: string; onChange: (v: string) => void;
  placeholder?: string; required?: boolean;
}) {
  return (
    <label className="block text-sm">
      <span className="text-slate-700">{label}</span>
      <input
        className="mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm font-mono"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        required={required}
      />
    </label>
  );
}
