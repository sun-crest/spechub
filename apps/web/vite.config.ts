import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

const BACKEND = process.env.VITE_API_BASE ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: {
    proxy: {
      // dev 时前端同源代理到 backend（对应 SPECHUB_EXTERNAL_URL）
      "/api": { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    // Monaco 体积大：单独拆 chunk，避免阻塞首屏
    rollupOptions: {
      output: {
        manualChunks: {
          monaco: ["monaco-editor", "@monaco-editor/react"],
        },
      },
    },
  },
});