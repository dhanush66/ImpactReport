/**
 * P10 — Typed REST client for the impact-cli web API.
 *
 *  - Reads the bearer token from localStorage and attaches it as
 *    `Authorization: Bearer <token>` on every call.
 *  - On 401 from a protected endpoint, clears the token and dispatches
 *    a custom `impact:unauthorized` event so the App can route the
 *    user back to /login.
 *  - Throws a typed {@link ApiError} on non-2xx so React Query / catch
 *    blocks get a structured error.
 */

export const TOKEN_KEY = "impact.token";

export class ApiError extends Error {
  constructor(public status: number, public payload: unknown, message: string) {
    super(message);
  }
}

const json = "application/json";

export function getToken(): string | null { return localStorage.getItem(TOKEN_KEY); }
export function setToken(t: string | null) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else   localStorage.removeItem(TOKEN_KEY);
}

async function fetchJson<T>(input: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: json,
    ...(init.headers as Record<string, string> | undefined),
  };
  if (init.body && !headers["Content-Type"]) headers["Content-Type"] = json;
  const token = getToken();
  if (token && !headers["Authorization"]) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(input, { ...init, headers });
  const text = await res.text();
  let body: unknown = text;
  try { if (text) body = JSON.parse(text); } catch { /* keep as raw text */ }

  if (!res.ok) {
    if (res.status === 401) {
      setToken(null);
      window.dispatchEvent(new CustomEvent("impact:unauthorized"));
    }
    const msg = (body && typeof body === "object" && "error" in (body as Record<string, unknown>))
      ? String((body as Record<string, unknown>).error)
      : res.statusText;
    throw new ApiError(res.status, body, msg);
  }
  return body as T;
}

// ── Typed response shapes (intentionally loose — server is source of truth) ──

export interface Health {
  status: string;
  version: string;
  neo4jUri: string;
  neo4jReachable: boolean;
  neo4jError?: string;
}
export interface LoginResponse {
  token: string;
  expiresAt: string;
  username: string;
  roles: string[];
}
export interface RepoSummary {
  repoId: string;
  sourceRoot: string;       // populated from :Repo.source_root if known (empty otherwise)
  commits: string[];
  fileCount: number;
}
export interface UploadResponse {
  path: string;
  originalName: string;
  size: number;
}
export interface JobSummary {
  id: string;
  kind: "INGEST" | "ANALYZE";
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  error?: string;
  params?: Record<string, unknown>;
  /** Populated on SUCCEEDED — analyze jobs include reportId + summary stats. */
  result?: Record<string, unknown>;
}
export interface JobDetail extends JobSummary {
  log?: { seq: number; at: string; stream: "stdout" | "stderr"; text: string }[];
}
export interface IngestStartResponse { jobId: string; status: string; createdAt: string; }
export interface IngestRequest {
  srcRoot: string;
  commit?: string;
  repoId?: string;
}
export interface AnalyzeRequest {
  repoPath: string;
  patchPath?: string;
  base?: string;
  head?: string;
  srcRoot?: string;
  depth?: number;
  showCoverage?: boolean;
}
/** Async start response — same shape as IngestStartResponse now that analyze is also async. */
export interface AnalyzeStartResponse {
  jobId: string;
  status: string;
  createdAt: string;
}

// ── Endpoint methods ──

export const api = {
  health: () => fetchJson<Health>("/api/v1/health"),
  login:  (username: string, password: string) =>
    fetchJson<LoginResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  whoami: () => fetchJson<{ authenticated: boolean; username?: string; roles?: string[] }>("/api/v1/auth/whoami"),
  repos:  () => fetchJson<RepoSummary[]>("/api/v1/repos"),
  jobs:   () => fetchJson<JobSummary[]>("/api/v1/jobs"),
  jobDetail: (id: string, tail = 200) => fetchJson<JobDetail>(`/api/v1/jobs/${id}?tail=${tail}`),
  startIngest: (body: IngestRequest) =>
    fetchJson<IngestStartResponse>("/api/v1/ingest", { method: "POST", body: JSON.stringify(body) }),
  analyze: (body: AnalyzeRequest) =>
    fetchJson<AnalyzeStartResponse>("/api/v1/analyze", { method: "POST", body: JSON.stringify(body) }),
  /** DELETE one repo's data (file nodes, repo node, orphan commits). Shared Class/Method nodes stay. */
  deleteRepo: (repoId: string) =>
    fetchJson<Record<string, unknown>>(`/api/v1/repos/${encodeURIComponent(repoId)}`, { method: "DELETE" }),
  /** DELETE the entire graph. Destructive — caller MUST confirm. */
  wipeAll: () => fetchJson<Record<string, unknown>>("/api/v1/repos/all", { method: "DELETE" }),
  /** Upload a patch file → returns the server-side temp path the analyze endpoint can consume. */
  uploadPatch: async (file: File): Promise<UploadResponse> => {
    const fd = new FormData();
    fd.append("file", file, file.name);
    const token = getToken();
    const headers: Record<string, string> = { Accept: "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const res = await fetch("/api/v1/uploads/patch", { method: "POST", body: fd, headers });
    const text = await res.text();
    let body: unknown = text;
    try { if (text) body = JSON.parse(text); } catch { /* keep raw */ }
    if (!res.ok) {
      if (res.status === 401) {
        setToken(null);
        window.dispatchEvent(new CustomEvent("impact:unauthorized"));
      }
      const msg = (body && typeof body === "object" && "error" in (body as Record<string, unknown>))
        ? String((body as Record<string, unknown>).error)
        : res.statusText;
      throw new ApiError(res.status, body, msg);
    }
    return body as UploadResponse;
  },
};

/** Build a same-origin WebSocket URL (works for both dev proxy + prod). */
export function wsJobsUrl(jobId: string): string {
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${window.location.host}/ws/jobs/${encodeURIComponent(jobId)}`;
}

/**
 * Download an authenticated resource (the server expects Bearer in the Authorization
 * header, which a plain <a download> link can't supply). Fetches the URL, wraps the
 * response in a Blob, programmatically clicks an <a download> with an object URL,
 * then revokes it.
 */
export async function downloadAuthed(url: string, filename: string): Promise<void> {
  const token = getToken();
  const headers: Record<string, string> = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  const res = await fetch(url, { headers });
  if (!res.ok) {
    if (res.status === 401) {
      setToken(null);
      window.dispatchEvent(new CustomEvent("impact:unauthorized"));
    }
    // Try to surface the server's structured error body so the user sees the real cause
    // instead of a generic "500 Server Error". The GlobalExceptionHandler returns JSON
    // shaped {error, type, at, cause}; we extract `error` (with optional `cause` suffix).
    let detail = `${res.status} ${res.statusText}`;
    try {
      const ct = res.headers.get("content-type") || "";
      const text = await res.text();
      if (ct.includes("application/json") && text) {
        const j = JSON.parse(text) as Record<string, unknown>;
        const e = typeof j.error === "string" ? j.error : null;
        const c = typeof j.cause === "string" ? j.cause : null;
        if (e) detail = c ? `${e}  (cause: ${c})` : e;
      } else if (text) {
        detail = `${detail} — ${text.slice(0, 300)}`;
      }
    } catch { /* keep generic detail */ }
    throw new ApiError(res.status, null, `download failed: ${detail}`);
  }
  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = objectUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    // Defer revoke a tick so some browsers (Firefox) have time to start the download
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  }
}
