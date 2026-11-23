import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies the API to a running backend. Default: the backend started from the IDE on
// 8080; override with ZW_API, for example ZW_API=http://localhost:18080 npm run dev (compose stack).
// (read through globalThis so the browser-side tsconfig needs no Node types)
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const target = env.ZW_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target, changeOrigin: false },
      '/health': { target, changeOrigin: false },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  test: {
    environment: 'node',
  },
});
