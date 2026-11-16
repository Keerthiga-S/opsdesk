import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const backend = process.env.VITE_BACKEND || "http://localhost:8001"; // the OpsDesk API (FastAPI)

// The browsers the bundle is written for. This is the list Vite 5 used by default; it is set here so that newer Vite
// versions, whose default only covers more recent browsers, keep supporting the same ones.
const browsers = ["es2020", "edge88", "firefox78", "chrome87", "safari14"];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": backend, "/healthz": backend },
  },
  build: { outDir: "dist", sourcemap: false, target: browsers },
});
