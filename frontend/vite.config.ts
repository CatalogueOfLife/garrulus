/// <reference types="vitest/config" />
import { execSync } from 'node:child_process';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The commit the bundle is built from, shown in the footer: GIT_COMMIT (Jenkins sets it; the npm container gets it
 * passed, as git may refuse a checkout owned by another user there), else the checkout's HEAD, else empty.
 */
function commit(): string {
  if (process.env.GIT_COMMIT) return process.env.GIT_COMMIT.trim();
  try {
    return execSync('git rev-parse HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return '';
  }
}

export default defineConfig({
  plugins: [react()],
  define: { __APP_COMMIT__: JSON.stringify(commit()) },
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
