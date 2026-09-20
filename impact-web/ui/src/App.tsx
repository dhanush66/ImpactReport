import { useEffect } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { Login } from "./pages/Login";
import { Dashboard } from "./pages/Dashboard";
import { Jobs } from "./pages/Jobs";
import { JobDetail } from "./pages/JobDetail";
import { AnalyzePage } from "./pages/AnalyzePage";
import { IngestPage } from "./pages/IngestPage";
import { Layout } from "./pages/Layout";
import { getToken } from "./api/client";

export function App() {
  const nav = useNavigate();
  const loc = useLocation();

  // Listen for 401s from anywhere — redirect to /login preserving the destination.
  useEffect(() => {
    const handler = () => {
      if (loc.pathname !== "/login") nav("/login?next=" + encodeURIComponent(loc.pathname + loc.search));
    };
    window.addEventListener("impact:unauthorized", handler);
    return () => window.removeEventListener("impact:unauthorized", handler);
  }, [loc, nav]);

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/ingest" element={<IngestPage />} />
        <Route path="/analyze" element={<AnalyzePage />} />
        <Route path="/jobs" element={<Jobs />} />
        <Route path="/jobs/:id" element={<JobDetail />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  if (!getToken()) {
    return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  }
  return <>{children}</>;
}
