import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

/**
 * P10 — Vite config for the impact-cli SPA.
 *
 *  - Dev: `npm run dev` serves on http://localhost:5173 with a proxy that forwards
 *    /api/* and /ws/* to the embedded Spring Boot server on :8080. Avoids CORS
 *    while iterating.
 *  - Build: emits to `../../impact-cli/src/main/resources/static/` so the shaded
 *    jar serves the SPA at the same origin as the REST API. Path is relative to
 *    this config file's location (impact-web/ui).
 */
export default defineConfig({
  plugins: [react()],
  build: {
    // Spring Boot's default static-resource handler serves /static/**, so we
    // emit straight into the resources/static dir. The build wipes that dir
    // first — Vite's default `emptyOutDir: true` behaviour.
    outDir: path.resolve(__dirname, "../../impact-cli/src/main/resources/static"),
    emptyOutDir: true,
    sourcemap: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/api":  { target: "http://localhost:8080", changeOrigin: true },
      "/ws":   { target: "ws://localhost:8080",   ws: true, changeOrigin: true },
    },
  },
});
