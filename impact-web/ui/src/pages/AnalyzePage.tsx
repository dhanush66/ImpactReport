import { FormEvent, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type RepoSummary } from "../api/client";
import { Spinner } from "../components/Spinner";

/**
 * P10 — Analyze form.
 *
 *  - Repo dropdown (driven by /repos.sourceRoot) with "Other (type path)" escape hatch.
 *  - Browser-native file picker for the patch; uploaded via /api/v1/uploads/patch.
 *  - Submits to the now-async POST /api/v1/analyze, gets jobId back, navigates
 *    to /jobs/{id} so the user can watch the WS-tailed log live and see the
 *    impact summary when it finishes (same UX as ingest jobs).
 */
export function AnalyzePage() {
  const nav = useNavigate();
  const [repos, setRepos] = useState<RepoSummary[] | null>(null);
  const [repoChoice, setRepoChoice] = useState<string>("");
  const [repoPath, setRepoPath] = useState<string>("");
  const [patchFile, setPatchFile] = useState<File | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [stage, setStage] = useState<string>("");
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancel = false;
    api.repos()
      .then((r) => {
        if (cancel) return;
        setRepos(r);
        const firstWithPath = r.find((x) => x.sourceRoot && x.sourceRoot.length > 0);
        if (firstWithPath) {
          setRepoChoice(firstWithPath.repoId);
          setRepoPath(deriveRepoPath(firstWithPath.sourceRoot));
        } else {
          setRepoChoice("__OTHER__");
        }
      })
      .catch((e) => { if (!cancel) setErr((e as Error).message); });
    return () => { cancel = true; };
  }, []);

  function onRepoChoiceChange(choice: string) {
    setRepoChoice(choice);
    if (choice === "__OTHER__") {
      setRepoPath("");
      return;
    }
    const repo = repos?.find((r) => r.repoId === choice);
    if (repo) setRepoPath(deriveRepoPath(repo.sourceRoot));
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setErr(null); setBusy(true);
    setStage("");
    try {
      if (!patchFile) {
        setErr("Pick a .patch file first.");
        setBusy(false);
        return;
      }
      setStage("Uploading patch…");
      const up = await api.uploadPatch(patchFile);

      setStage("Starting analysis job…");
      // Coverage is no longer user-selectable; the checkbox was always on by
      // default, so keep sending true to preserve the existing behaviour.
      const r = await api.analyze({
        repoPath,
        patchPath: up.path,
        showCoverage: true,
      });
      // Same redirect pattern as Ingest — user lands on the JobDetail page where the
      // live log streams and the final summary appears when the job hits SUCCEEDED.
      nav(`/jobs/${r.jobId}`);
    } catch (e) {
      setErr((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <header>
        <h2 className="text-lg font-semibold text-slate-900">Analyze a patch</h2>
        <p className="text-sm text-slate-500">
          Runs as a background job. You'll be redirected to its live log; the impact summary
          and download buttons appear there when the job completes.
        </p>
      </header>

      <form onSubmit={onSubmit} className="rounded-lg border border-slate-200 bg-white p-4 space-y-3 max-w-xl">
        {/* Repo dropdown + path text input */}
        <label className="block text-sm">
          <span className="text-slate-700">Repo</span>
          {!repos ? (
            <div className="mt-1 text-xs text-slate-400">Loading…</div>
          ) : (
            <select
              className="mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm"
              value={repoChoice}
              onChange={(e) => onRepoChoiceChange(e.target.value)}
            >
              {repos.map((r) => (
                <option key={r.repoId} value={r.repoId} disabled={!r.sourceRoot}>
                  {r.repoId}
                  {r.sourceRoot ? `  —  ${r.sourceRoot}` : "  (no stored path — pick \"Other\")"}
                </option>
              ))}
              <option value="__OTHER__">Other (type a path manually)</option>
            </select>
          )}
        </label>
        <label className="block text-sm">
          <span className="text-slate-700">Repo path on server disk</span>
          <input
            className="mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm font-mono"
            value={repoPath}
            onChange={(e) => setRepoPath(e.target.value)}
            placeholder="C:\proj\my-product   (project root — parent of source/java)"
            required
          />
          <span className="text-xs text-slate-500 mt-1 block">
            Auto-filled when you pick a repo above. The patch's relative paths (e.g. <code>source/java/com/…</code>) are resolved against this root.
          </span>
        </label>

        {/* File picker for patch */}
        <label className="block text-sm">
          <span className="text-slate-700">Patch file</span>
          <input
            ref={fileInputRef}
            type="file"
            accept=".patch,.diff,.txt"
            onChange={(e) => setPatchFile(e.target.files?.[0] ?? null)}
            className="mt-1 block w-full text-sm text-slate-700
              file:mr-3 file:py-2 file:px-3 file:rounded file:border-0
              file:text-sm file:font-medium
              file:bg-slate-100 file:text-slate-700 hover:file:bg-slate-200
              border border-slate-300 rounded"
            required
          />
          {patchFile && (
            <span className="text-xs text-slate-500 mt-1 block">
              {patchFile.name}  ·  {humanSize(patchFile.size)}
            </span>
          )}
        </label>

        {err && (
          <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">{err}</div>
        )}
        {busy && stage && (
          <div className="flex items-center gap-2 text-sm text-slate-600 bg-slate-50 border border-slate-200 rounded px-3 py-2">
            <Spinner className="text-slate-700" />
            <span>{stage}</span>
          </div>
        )}

        <button
          type="submit"
          disabled={busy || !repoPath || !patchFile}
          className="inline-flex items-center gap-2 bg-slate-900 text-white rounded px-4 py-2 text-sm font-medium hover:bg-slate-800 disabled:opacity-50"
        >
          {busy && <Spinner className="text-white" />}
          {busy ? "Submitting…" : "Run analysis"}
        </button>
      </form>
    </div>
  );
}

/**
 * Heuristic: the stored source_root is the Java source root (e.g. C:\proj\source\java).
 * The analyze endpoint expects the PROJECT root so it can resolve patch paths like
 * `source/java/com/foo/Bar.java`. Strip the trailing source root if present.
 */
function deriveRepoPath(sourceRoot: string): string {
  if (!sourceRoot) return "";
  const SUFFIXES = [
    "\\source\\java_source",
    "\\source\\java",
    "\\src\\main\\java",
    "/source/java_source",
    "/source/java",
    "/src/main/java",
  ];
  for (const sfx of SUFFIXES) {
    if (sourceRoot.endsWith(sfx)) return sourceRoot.slice(0, -sfx.length);
  }
  return sourceRoot;
}

function humanSize(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
