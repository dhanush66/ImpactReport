import { Link, NavLink, Outlet, useNavigate } from "react-router-dom";
import { setToken } from "../api/client";

/** Top nav + content outlet — wraps every authenticated route. */
export function Layout() {
  const nav = useNavigate();
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-slate-200 px-6 py-3 flex items-center gap-6">
        <Link to="/" className="font-semibold text-slate-900 hover:text-slate-700">
          Impact Analysis
        </Link>
        <nav className="flex gap-4 text-sm text-slate-600">
          <NavLink to="/" end className={navCls}>Repos</NavLink>
          <NavLink to="/ingest" className={navCls}>Ingest</NavLink>
          <NavLink to="/analyze" className={navCls}>Analyze</NavLink>
          <NavLink to="/jobs" className={navCls}>Jobs</NavLink>
        </nav>
        <div className="ml-auto">
          <button
            onClick={() => { setToken(null); nav("/login"); }}
            className="text-sm text-slate-500 hover:text-slate-900"
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="flex-1 px-6 py-6 max-w-6xl w-full mx-auto">
        <Outlet />
      </main>
    </div>
  );
}

function navCls({ isActive }: { isActive: boolean }) {
  return isActive
    ? "text-slate-900 font-medium border-b-2 border-slate-900 pb-3 -mb-3"
    : "hover:text-slate-900";
}
