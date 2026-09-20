import { FormEvent, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, setToken } from "../api/client";

export function Login() {
  const nav = useNavigate();
  const [params] = useSearchParams();
  const next = params.get("next") || "/";

  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const r = await api.login(username, password);
      setToken(r.token);
      nav(next, { replace: true });
    } catch (e) {
      setError((e as Error).message || "login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen grid place-items-center bg-slate-100 px-4">
      <form
        onSubmit={onSubmit}
        className="bg-white rounded-lg shadow-sm border border-slate-200 p-6 w-full max-w-sm space-y-4"
      >
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Impact Analysis</h1>
          <p className="text-sm text-slate-500 mt-1">Sign in to continue.</p>
        </div>

        <label className="block text-sm">
          <span className="text-slate-700">Username</span>
          <input
            className="mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
          />
        </label>

        <label className="block text-sm">
          <span className="text-slate-700">Password</span>
          <input
            type="password"
            className="mt-1 block w-full rounded border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        {error && (
          <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">{error}</div>
        )}

        <button
          type="submit"
          disabled={busy}
          className="w-full bg-slate-900 text-white rounded py-2 text-sm font-medium hover:bg-slate-800 disabled:opacity-50"
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>

        <p className="text-xs text-slate-500">
          First-time setup? Start the server with no users — it prints an{" "}
          <code className="bg-slate-100 rounded px-1">admin</code> password to its stdout.
        </p>
      </form>
    </div>
  );
}
