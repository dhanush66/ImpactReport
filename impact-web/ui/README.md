# impact-web/ui — React SPA for impact-cli

Vite + React + TypeScript + Tailwind. Talks to the Spring Boot REST + WebSocket
server exposed by `impact web`.

## Layout

```
impact-web/ui/
├── package.json
├── vite.config.ts        ← build output → ../../impact-cli/src/main/resources/static
├── tsconfig.json
├── tailwind.config.js / postcss.config.js
├── index.html
└── src/
    ├── main.tsx          ← entry point
    ├── App.tsx           ← React Router routes + 401 listener
    ├── index.css         ← Tailwind imports
    ├── api/
    │   └── client.ts     ← typed fetch wrapper, token storage, ws URL helper
    └── pages/
        ├── Layout.tsx    ← top nav + outlet
        ├── Login.tsx     ← POST /api/v1/auth/login → store JWT
        ├── Dashboard.tsx ← GET /api/v1/repos table + health badge
        ├── AnalyzePage.tsx ← POST /api/v1/analyze form + summary
        ├── Jobs.tsx      ← GET /api/v1/jobs polling list
        └── JobDetail.tsx ← WS /ws/jobs/{id} live tail
```

## Develop

```powershell
# 1. Install deps (one time)
cd impact-web/ui
npm install

# 2. Start Vite dev server (proxies /api and /ws to localhost:8080)
npm run dev
# → http://localhost:5173

# 3. In another shell, start the backend
java -jar ../../impact-cli/target/impact.jar web --port 8080 --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Vite's dev server has HMR; edits in `src/**` reload the browser instantly. The
proxy in `vite.config.ts` forwards `/api/*` and `/ws/*` to the backend, so the
SPA code uses same-origin relative paths.

## Production build

```powershell
cd impact-web/ui
npm run build
# → emits to impact-cli/src/main/resources/static/index.html + assets/*

cd ../../impact-cli
mvn package -DskipTests
# The shaded jar now bundles the SPA. Start the server and visit http://localhost:8080
java -jar target/impact.jar web --port 8080 --neo4j ... --user ... --pass ...
```

Spring Boot's default static-resource handler serves `/index.html` + `/assets/*`
from `classpath:/static/`. `SpaForwardingConfig` adds history-mode fallback so
deep links (e.g. `/jobs/abc123`) reload cleanly instead of 404-ing.

## How auth works

1. User visits `/`. App.tsx's `RequireAuth` sees no token in localStorage →
   redirects to `/login?next=/`.
2. User submits credentials. `api.login()` POSTs to `/api/v1/auth/login`, gets
   `{token}` back, calls `setToken(token)`.
3. Every subsequent `fetchJson` call attaches `Authorization: Bearer <token>`.
4. On any 401, the client clears the token and dispatches
   `impact:unauthorized` — App.tsx routes the user back to `/login`.

## Bootstrap admin

First server start with no `:AppUser` nodes prints credentials to stdout:

```
[admin-bootstrap] seeded a default admin.
    username: admin
    password: rlMdgWVk_2paYkdTOHETKQXX
```

Sign in with those, then create real users via `impact users create --username … --role …`.
