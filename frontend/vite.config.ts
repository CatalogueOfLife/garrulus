/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // 127.0.0.1, not localhost: ORCID accepts http://127.0.0.1 redirect URIs but not http://localhost
    host: '127.0.0.1',
    port: 5173,
    // changeOrigin false keeps the browser's Host header, so web builds the ORCID redirect URI on this origin
    proxy: Object.fromEntries(
      ['/api', '/oauth2', '/login', '/feeds'].map((p) => [p, { target: 'http://localhost:8080', changeOrigin: false }]),
    ),
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
