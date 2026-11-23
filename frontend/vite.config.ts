import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies the API to a locally running backend (or the compose load balancer).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/health': { target: 'http://localhost:8080', changeOrigin: false },
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
