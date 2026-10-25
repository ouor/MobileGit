import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Build output goes straight into the Android assets served by the phone (docs/core/spec.md 4).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(import.meta.dirname, './src') } },
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
    // CSP blocks inline scripts/styles; keep every asset a file (no data: inlining for fonts/svg into JS is fine, but keep it simple).
    assetsInlineLimit: 0,
    reportCompressedSize: true,
  },
  server: {
    // `npm run dev` proxies API calls to the phone (adb forward tcp:8080 tcp:8080).
    proxy: { '/api': process.env.MOBILEGIT_URL ?? 'http://localhost:8080' },
  },
})
