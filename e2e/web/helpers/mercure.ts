import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * Real-time helpers.
 *
 * Three past regressions live here rather than in any one feature (MAG-93), so
 * the harness owns their assertions:
 *
 *   b333376, c2d3758 — topics and per-user scoping. An update published for one
 *                      user reached another, or reached nobody.
 *   b16916d          — `VITE_MERCURE_PUBLIC_URL` is relative in production
 *                      (`/.well-known/mercure`), and `new URL()` threw on it, so
 *                      the dashboard's real-time died in prod and only in prod.
 *
 * {@link openMercureProbe} deliberately builds its URL the way the admin's
 * `useMercure` does — a relative path resolved against the current origin, one
 * `topic` parameter per topic, `withCredentials` so the `mercureAuthorization`
 * cookie goes with it. A probe that took a shortcut would be green while the
 * app was broken.
 */

/** What the admin is built with in e2e and in production alike: a relative path. */
export const MERCURE_PATH = '/.well-known/mercure'

export interface MercureMessage {
  /** The raw `data:` line. */
  data: string
  /** The same, parsed — Mercure carries the entity payload, `@id` included. */
  parsed: Record<string, unknown> | null
}

export interface MercureProbe {
  /** Everything received since the probe opened. */
  messages(): Promise<MercureMessage[]>
  /** Resolves on the first message matching `match`, or fails the test on timeout. */
  waitFor(match: (message: MercureMessage) => boolean, options?: { timeout?: number }): Promise<MercureMessage>
  /** Asserts nothing arrives for `ms`. What "the other user must not receive this" looks like. */
  expectSilence(ms?: number): Promise<void>
  close(): Promise<void>
}

/** The scoped topic the API publishes on: `/users/{userId}/api/tasks/{id}`. */
export function userTopic(userId: string, iri: string): string {
  return `/users/${userId}${iri}`
}

interface ProbeState {
  messages: Array<{ data: string }>
  open: boolean
  failed: boolean
  close: () => void
}

declare global {
  interface Window {
    __maggieMercureProbes?: Record<string, ProbeState>
  }
}

let probeCounter = 0

/**
 * Opens an EventSource inside `page` and collects what the hub sends it.
 *
 * The page must already be on the app's origin — the cookie the hub checks is
 * scoped to it, and the relative URL is resolved against it.
 */
export async function openMercureProbe(page: Page, topics: string[]): Promise<MercureProbe> {
  if (topics.length === 0) {
    throw new Error('A Mercure probe with no topic would be silent for the wrong reason.')
  }

  const id = `probe-${++probeCounter}`

  await page.evaluate(
    ({ id, path, topics }) => {
      const url = new URL(path, window.location.origin)
      for (const topic of topics) {
        url.searchParams.append('topic', topic)
      }

      const probes = (window.__maggieMercureProbes ??= {})
      const source = new EventSource(url.toString(), { withCredentials: true })
      const state: ProbeState = {
        messages: [],
        open: false,
        failed: false,
        close: () => source.close(),
      }

      source.onopen = () => {
        state.open = true
      }
      // EventSource retries forever on its own, so an error is not fatal —
      // but it has to be visible. An isolation assertion reads silence as
      // proof; a dropped stream is silence for the wrong reason, and
      // `expectSilence` refuses to pass on it.
      source.onerror = () => {
        state.failed = true
      }
      source.onmessage = (event) => {
        state.messages.push({ data: event.data })
      }

      probes[id] = state
    },
    { id, path: MERCURE_PATH, topics },
  )

  // Nothing published before the hub accepted the subscription is ever
  // delivered. Acting first and subscribing second is the single most common
  // way a real-time journey goes flaky.
  await page.waitForFunction(
    (id) => window.__maggieMercureProbes?.[id]?.open === true,
    id,
    { timeout: 10_000 },
  )

  const read = async (): Promise<MercureMessage[]> => {
    const raw = await page.evaluate((id) => window.__maggieMercureProbes?.[id]?.messages ?? [], id)

    return raw.map((message) => ({ data: message.data, parsed: safeParse(message.data) }))
  }

  const dropped = async (): Promise<boolean> =>
    page.evaluate((id) => window.__maggieMercureProbes?.[id]?.failed === true, id)

  return {
    messages: read,

    async waitFor(match, options = {}) {
      const timeout = options.timeout ?? 15_000
      const deadline = Date.now() + timeout

      for (;;) {
        const found = (await read()).find(match)
        if (found) {
          return found
        }

        if (Date.now() >= deadline) {
          const seen = await read()
          throw new Error(
            `No Mercure update matched within ${timeout}ms. ` +
              `Topics subscribed: ${topics.join(', ')}. ` +
              `Received ${seen.length} message(s): ${seen.map((m) => m.data).join(' | ') || '(none)'}`,
          )
        }

        await page.waitForTimeout(100)
      }
    },

    async expectSilence(ms = 3_000) {
      await page.waitForTimeout(ms)

      // Checked first, and this is the whole point of tracking it: an empty
      // message list proves isolation only if the connection was still open
      // to receive something. A hub that dropped the stream is silent too.
      expect(
        await dropped(),
        `the connection to ${topics.join(', ')} dropped — its silence proves nothing`,
      ).toBe(false)

      const seen = await read()
      expect(
        seen.map((message) => message.data),
        `nothing must reach a subscriber of ${topics.join(', ')}`,
      ).toEqual([])
    },

    async close() {
      await page.evaluate((id) => {
        window.__maggieMercureProbes?.[id]?.close()
        delete window.__maggieMercureProbes?.[id]
      }, id)
    },
  }
}

/**
 * Runs `open` and does not return until the hub has accepted the page's own
 * subscription.
 *
 * The admin subscribes from a `useEffect`, so a tab is "loaded" a beat before
 * it is listening — and an update published in that beat is never delivered.
 * Waiting on the *response* rather than the request is what makes it exact:
 * the hub answers the SSE headers once the subscriber is registered.
 *
 * Acting before the observer is subscribed is the single most common way a
 * real-time journey goes flaky, so no two-tab test should skip this.
 */
export async function openSubscribed(page: Page, open: () => Promise<void>): Promise<void> {
  const subscribed = page.waitForResponse(
    (response) => response.url().includes(MERCURE_PATH) && response.status() === 200,
    { timeout: 30_000 },
  )

  await open()
  await subscribed
}

/**
 * The two-tab check: do something in one tab, watch it land in the other.
 *
 * The navigation guard is the point. Without it a test that happened to reload
 * the observing tab — react-admin does reload on some auth paths — would pass
 * while real-time was dead, which is exactly how b16916d survived to
 * production.
 */
export async function expectRealtimeSync(
  observer: Page,
  act: () => Promise<void>,
  settle: (observer: Page) => Promise<void>,
): Promise<void> {
  const navigations: string[] = []
  const onNavigated = (frame: { parentFrame(): unknown; url(): string }) => {
    if (frame.parentFrame() === null) {
      navigations.push(frame.url())
    }
  }

  observer.on('framenavigated', onNavigated)

  try {
    await act()
    await settle(observer)
  } finally {
    observer.off('framenavigated', onNavigated)
  }

  expect(navigations, 'the observing tab reloaded — that proves nothing about real-time').toEqual([])
}

function safeParse(data: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(data)

    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}
