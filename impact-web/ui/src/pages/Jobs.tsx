import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type JobSummary } from "../api/client";

const STATUS_COLOR: Record<string, string> = {
  PENDING:    "bg-slate-100 text-slate-700",
  RUNNING:    "bg-blue-100 text-blue-700",
  SUCCEEDED:  "bg-green-100 text-green-700",
  FAILED:     "bg-red-100 text-red-700",
  CANCELLED:  "bg-amber-100 text-amber-700",
};

export function Jobs() {
  const [jobs, setJobs] = useState<JobSummary[] | null>(null);
  const [err,  setErr]  = useState<string | null>(null);

  useEffect(() => {
    let cancel = false;
    function load() {
      api.jobs()
        .then((j) => { if (!cancel) setJobs(j); })
        .catch((e) => { if (!cancel) setErr((e as Error).message); });
    }
    load();
    // Light polling — refreshes the list every 3s so running jobs show progress
    // without needing a WS just for the index page.
    const t = setInterval(load, 3000);
    return () => { cancel = true; clearInterval(t); };
  }, []);

  return (
    <div className="space-y-4">
      <header>
        <h2 className="text-lg font-semibold text-slate-900">Jobs</h2>
        <p className="text-sm text-slate-500">Async ingest + analyze runs. Click an ID for live log tail.</p>
      </header>

      {err && (
        <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">{err}</div>
      )}

      {!jobs ? (
        <div className="text-sm text-slate-500">Loading…</div>
      ) : jobs.length === 0 ? (
        <div className="text-sm text-slate-500">No jobs yet. Start one from the Analyze tab or via the API.</div>
      ) : (
        <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-slate-600 text-left">
              <tr>
                <th className="px-4 py-2 font-medium">ID</th>
                <th className="px-4 py-2 font-medium">Kind</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 font-medium">Started</th>
                <th className="px-4 py-2 font-medium">Finished</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {jobs.map((j) => (
                <tr key={j.id}>
                  <td className="px-4 py-2 font-mono text-xs">
                    <Link to={`/jobs/${j.id}`} className="text-slate-900 hover:underline">{j.id}</Link>
                  </td>
                  <td className="px-4 py-2 text-slate-700">{j.kind}</td>
                  <td className="px-4 py-2">
                    <span className={`inline-block text-xs px-2 py-0.5 rounded ${STATUS_COLOR[j.status] ?? ""}`}>
                      {j.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-slate-600 text-xs">{j.startedAt ?? "—"}</td>
                  <td className="px-4 py-2 text-slate-600 text-xs">{j.finishedAt ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
