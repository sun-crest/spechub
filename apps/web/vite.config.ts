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
      // dev 时前端同源代理到 backend（对应 SPECHUB_EXTERNAL_URL）。
      // OIDC 授权 / 回调必须与前端同源，否则会话 Cookie 与回调会落到错误端口。
      "/api": { target: BACKEND, changeOrigin: true },
      "/oauth2": { target: BACKEND, changeOrigin: true },
      "/login/oauth2": { target: BACKEND, changeOrigin: true },
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