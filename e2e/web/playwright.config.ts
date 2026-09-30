import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright for the admin web app (MAG-97).
 *
 * Two things about this config are load-bearing.
 *
 * `baseURL` defaults to `http://traefik`, the stack's own router as seen from
 * inside its network — not the ephemeral host port. The journeys run in a
 * container on the e2e network (`task e2e:web`), so they never have to resolve
 * a port, and the admin's relative URLs (`/api`, `/.well-known/mercure`) all
 * land on the same origin, exactly as they do in production behind Traefik.
 * Override it to point a local `npx playwright test` at `task e2e:url`.
 *
 * `tablet` and `phone` only run tests tagged `@responsive`. Every journey on
 * three widths would triple a suite whose slowest steps — the chat stream, the
 * two-tab Mercure wait — have nothing to do with layout. The tag is what MAG-38
 * and MAG-90 will grow: put it on a test whose *layout* is the point.
 */

const RESPONSIVE = /@responsive/

// The widths the admin is expected to work at. Deliberately straddling MUI's
// `md` breakpoint (900px), which is where react-admin folds its sidebar away:
// desktop keeps the menu open, tablet and phone have to reach it through the
// AppBar toggle.
export default defineConfig({
  testDir: './tests',
  outputDir: './test-results',

  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // One retry in CI, none locally: a flake that only reproduces under load
  // should still be visible in the report, not hidden by three retries.
  retries: process.env.CI ? 1 : 0,
  // Two, and not "half the CPUs" as Playwright would default to. The whole
  // stack — Elasticsearch, RabbitMQ, a PHP-FPM pool, the agent — is already on
  // this machine, and the admin is a 2 MB React bundle each worker parses
  // again. At eight workers a page that loads in under a second took fifteen,
  // and every journey failed on a timeout that had nothing to teach anyone.
  // Override with PLAYWRIGHT_WORKERS on a machine with room to spare.
  workers: process.env.PLAYWRIGHT_WORKERS ? Number(process.env.PLAYWRIGHT_WORKERS) : 2,

  reporter: process.env.CI
    ? [['github'], ['list'], ['html', { outputFolder: './playwright-report', open: 'never' }]]
    : [['list'], ['html', { outputFolder: './playwright-report', open: 'never' }]],

  // The chat journey waits on a full AG-UI round trip through the agent, MCP
  // and the database; 30s (the default) is not enough on a cold container.
  timeout: 90_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://traefik',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // Pinned, not inherited from the container: the admin formats dates with
    // `toLocaleString('fr-FR')` and the dashboard buckets events by the
    // browser's day. A container on UTC and a developer on Europe/Paris would
    // otherwise disagree about what "today" holds.
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
  },

  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'tablet',
      grep: RESPONSIVE,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 834, height: 1112 },
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'phone',
      grep: RESPONSIVE,
      use: { ...devices['Pixel 5'] },
    },
  ],
})
