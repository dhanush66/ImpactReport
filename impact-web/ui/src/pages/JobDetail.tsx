import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, downloadAuthed, getToken, type JobDetail as JobDetailT, wsJobsUrl } from "../api/client";

interface LogLine { seq: number; stream: "stdout" | "stderr"; text: string; at: string; }

const STATUS_COLOR: Record<string, string> = {
  PENDING:    "bg-slate-100 text-slate-700",
  RUNNING:    "bg-blue-100 text-blue-700",
  SUCCEEDED:  "bg-green-100 text-green-700",
  FAILED:     "bg-red-100 text-red-700",
  CANCELLED:  "bg-amber-100 text-amber-700",
};

/**
 * P10 — Live log tail for a single job via WebSocket /ws/jobs/{id}.
 * When the job is an ANALYZE that has SUCCEEDED, also renders the Impact summary
 * + HTML/MD/JSON download buttons (populated from the JobState.result the server
 * stashes when the analyze pipeline finishes).
 */
export function JobDetail() {
  const { id = "" } = useParams();
  const [detail, setDetail] = useState<JobDetailT | null>(null);
  const [log, setLog]       = useState<LogLine[]>([]);
  const [wsOpen, setWsOpen] = useState(false);
  const [err, setErr]       = useState<string | null>(null);
  const logEndRef = useRef<HTMLDivElement>(null);

  // Initial REST fetch
  useEffect(() => {
    if (!id) return;
    api.jobDetail(id, 0)
      .then((d) => setDetail(d))
      .catch((e) => setErr((e as Error).message));
  }, [id]);

  // WebSocket live tail
  useEffect(() => {
    if (!id) return;
    const token = getToken();
    const url = wsJobsUrl(id) + (token ? `?token=${encodeURIComponent(token)}` : "");
    const ws = new WebSocket(url);
    ws.onopen = () => setWsOpen(true);
    ws.onclose = () => setWsOpen(false);
    ws.onerror = () => setErr("WebSocket error — server may still be starting; falling back to polling.");
    ws.onmessage = (ev) => {
      try {
        const frame = JSON.parse(ev.data);
        if (frame.type === "log") {
          setLog((prev) => {
            if (prev.length && prev[prev.length - 1].seq >= frame.seq) return prev;
            return [...prev, frame as LogLine];
          });
        } else if (frame.type === "status") {
          // Job hit a terminal state — re-fetch detail so the result map shows up.
          setDetail((prev) => prev ? { ...prev, status: frame.status } : prev);
          if (frame.status === "SUCCEEDED" || frame.status === "FAILED") {
            api.jobDetail(id, 0).then((d) => setDetail(d)).catch(() => {});
          }
        }
      } catch { /* ignore */ }
    };
    return () => { try { ws.close(); } catch { /* */ } };
  }, [id]);

  // Polling fallback: when WebSocket is disconnected and job is still non-terminal,
  // poll every 5s to detect completion.
  useEffect(() => {
    if (!id || wsOpen) return;
    const isTerminal = detail?.status === "SUCCEEDED" || detail?.status === "FAILED" || detail?.status === "CANCELLED";
    if (isTerminal) return;
    const interval = setInterval(() => {
      api.jobDetail(id, 0).then((d) => {
        setDetail(d);
        if (d.status === "SUCCEEDED" || d.status === "FAILED" || d.status === "CANCELLED") {
          clearInterval(interval);
        }
      }).catch(() => {});
    }, 5000);
    return () => clearInterval(interval);
  }, [id, wsOpen, detail?.status]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [log.length]);

  if (!detail) return <div className="text-sm text-slate-500">Loading…</div>;

  const isAnalyze = detail.kind === "ANALYZE";
  const isSucceeded = detail.status === "SUCCEEDED";
  const result = detail.result;

  return (
    <div className="space-y-4">
      <Link to="/jobs" className="text-sm text-slate-500 hover:text-slate-900">← Back to jobs</Link>
      <header className="flex items-center gap-3">
        <h2 className="text-lg font-semibold text-slate-900">
          {isAnalyze ? "Analyze" : "Ingest"} job <code className="font-mono text-base">{detail.id}</code>
        </h2>
        <span className={`text-xs px-2 py-0.5 rounded ${STATUS_COLOR[detail.status] ?? ""}`}>
          {detail.status}
        </span>
        <span className="text-xs text-slate-500">
          {detail.kind} · {wsOpen ? "● live" : "○ polling"}
        </span>
      </header>

      {detail.params && Object.keys(detail.params).length > 0 && (
        <div className="rounded-lg border border-slate-200 bg-white p-3 text-xs">
          <div className="text-slate-500 mb-1">Params</div>
          <pre className="text-slate-700 whitespace-pre-wrap break-all">
            {JSON.stringify(detail.params, null, 2)}
          </pre>
        </div>
      )}

      {/* P9.8 — when an analyze job succeeds, render the Impact summary inline */}
      {isAnalyze && isSucceeded && result && (
        <AnalyzeSummary result={result} />
      )}

      {detail.error && (
        <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">
          {detail.error}
        </div>
      )}
      {err && (
        <div className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-3 py-2">{err}</div>
      )}

      <div className="rounded-lg border border-slate-200 bg-slate-900 text-slate-100 p-3 max-h-[60vh] overflow-y-auto font-mono text-xs">
        {log.length === 0
          ? <div className="text-slate-500">(no log lines yet — waiting for worker to start)</div>
          : log.map((l) => (
              <div key={l.seq} className={l.stream === "stderr" ? "text-red-300" : ""}>
                <span className="text-slate-500 mr-2">{l.seq.toString().padStart(4, "0")}</span>
                {l.text}
              </div>
            ))}
        <div ref={logEndRef} />
      </div>
    </div>
  );
}

const RISK_COLOR: Record<string, string> = {
  HIGH:   "text-risk-high",
  MEDIUM: "text-risk-medium",
  LOW:    "text-risk-low",
};

/** Impact summary card — renders when an analyze job's result map is available. */
function AnalyzeSummary({ result }: { result: Record<string, unknown> }) {
  const reportId = String(result.reportId ?? "");
  const htmlUrl  = result.htmlUrl as string | undefined;
  const risk     = String(result.overallRisk ?? "—");
  const symbols  = Number(result.totalChangedSymbols ?? 0);
  const apis     = Number(result.apisAffected ?? 0);
  const schedules = Number(result.schedulesAffected ?? 0);
  const cov = result.coverage as
    { testCasesAvailable?: number; coveringTestCases?: number; coverageGaps?: number } | undefined;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 space-y-4">
      <h3 className="text-base font-semibold text-slate-900">Impact summary</h3>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
        <Stat label="Overall risk" value={<span className={`font-semibold ${RISK_COLOR[risk] ?? ""}`}>{risk}</span>} />
        <Stat label="Changed symbols" value={symbols.toLocaleString()} />
        <Stat label="APIs affected" value={apis} />
        <Stat label="Schedules" value={schedules} />
      </div>
      {cov && cov.testCasesAvailable && cov.testCasesAvailable > 0 && (
        <div className="text-sm text-slate-600 border-t border-slate-100 pt-3">
          Test coverage: <strong>{cov.coveringTestCases}</strong> recommended,{" "}
          <strong>{cov.coverageGaps}</strong> gap{cov.coverageGaps === 1 ? "" : "s"}{" "}
          (library size {cov.testCasesAvailable})
        </div>
      )}
      <div className="flex flex-wrap gap-3 items-center border-t border-slate-100 pt-3">
        {htmlUrl && reportId && (
          <button
            onClick={() => downloadAuthed(htmlUrl, `impact-report-${reportId}.html`)}
            className="bg-slate-900 text-white rounded px-3 py-1.5 text-sm font-medium hover:bg-slate-800"
          >
            ↓ Download HTML report
          </button>
        )}
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded border border-slate-100 p-2">
      <div className="text-xs text-slate-500">{label}</div>
      <div className="text-base text-slate-900">{value}</div>
    </div>
  );
}
