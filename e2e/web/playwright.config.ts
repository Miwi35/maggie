import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright for the admin web app (MAG-97).
 *
 * Three things about this config are load-bearing.
 *
 * `baseURL` defaults to `http://localhost`, which is the stack's own router as
 * seen from the journeys' container: it shares Traefik's network namespace
 * (`playwright-journeys` in `docker-compose.e2e.yml`, MAG-145). Not the
 * ephemeral host port, so the journeys never have to resolve one, and the
 * admin's relative URLs (`/api`, `/.well-known/mercure`) all land on the same
 * origin, exactly as they do in production behind Traefik. And not
 * `http://traefik`, which is what the stack answered on before: plain HTTP on a
 * hostname is not a trustworthy origin, so `navigator.mediaDevices` did not
 * exist and nothing could drive the microphone. `localhost` is trustworthy by
 * definition, with no flag involved — none of Playwright's own levers worked
 * (`permissions`, Chromium's fake capture device,
 * `--unsafely-treat-insecure-origin-as-secure`, which this build ignores even
 * with a persistent profile). Override it to point a local
 * `npx playwright test` at `task e2e:url`.
 *
 * The microphone is then a fake one: the permission is granted and Chromium
 * answers `getUserMedia` with its synthetic beep, so "Dicter" records real
 * bytes without a prompt and without hardware.
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
  globalSetup: './global-setup.ts',

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

  // In CI the journeys run in shards (MAG-180), each with a half-report of its
  // own: `blob` is what `playwright merge-reports` turns back into the one HTML
  // report, traces and videos included. `json` is what `scripts/e2e/verdict.sh`
  // reads to tell a journey in quarantine from the others (e2e/impact-map.yml).
  reporter: process.env.CI
    ? [
        ['github'],
        ['list'],
        ['blob', { outputDir: './blob-report' }],
        ['json', { outputFile: './results/results.json' }],
      ]
    : [['list'], ['html', { outputFolder: './playwright-report', open: 'never' }]],

  // The chat journey waits on a full AG-UI round trip through the agent, MCP
  // and the database; 30s (the default) is not enough on a cold container.
  timeout: 90_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost',
    permissions: ['microphone'],
    launchOptions: {
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
    },
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
