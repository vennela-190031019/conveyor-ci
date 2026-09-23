import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` serves the UI on :5173 and proxies /api to Conveyor on :8080.
// `npm run build` writes the bundle into Spring Boot's static resources, so the API serves it at "/".
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
