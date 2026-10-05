/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/admin',
  server: {
    allowedHosts: ['maggie.local'],
    hmr: {
      protocol: 'wss',
      host: 'maggie.local',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    // Vitest's 5 s default is below what a full-page render costs in CI (MAG-248):
    // a test that mounts CalendarView or a MUI dialog and drives it takes 2-4 s
    // alone and 6-17 s with v8 coverage on a shared runner, where the rendering
    // is the cost, not a wait that could be bounded. A hung test still fails, at
    // 30 s instead of 5.
    testTimeout: 30_000,
    // Line coverage of the unit tests (MAG-105), read by scripts/coverage/.
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.{test,spec}.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts'],
    },
  },
})
