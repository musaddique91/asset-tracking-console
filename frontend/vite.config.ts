import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API to a running backend. The target comes from ZW_API, read from
// frontend/.env (see .env.example) or the shell; default: a backend on localhost:8080 (IDE run).
// ZW_API has no VITE_ prefix, so it is only used here and never reaches the browser bundle.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, new URL('.', import.meta.url).pathname, '');
  const target = env.ZW_API || 'http://localhost:8080';
  return {
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
  };
});
