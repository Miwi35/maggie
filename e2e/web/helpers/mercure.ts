import { createHmac } from 'node:crypto'
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
 * `match` (exact) or `match_urlpattern` (`{id}` → `:id`) parameter per topic,
 * `withCredentials` so the `mercureAuthorization` cookie goes with it. A probe that took a shortcut would be green while the
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

/**
 * How a topic is subscribed on a Mercure 1.0 hub, the way `admin/src/hooks/mercureUrl.ts`
 * spells it: `match` for an exact topic, `match_urlpattern` for a `{id}` pattern.
 * The 0.x `topic` parameter gets a 400.
 */
export function subscribeParam(topic: string): [name: 'match' | 'match_urlpattern', value: string] {
  return topic.includes('{') ? ['match_urlpattern', topic.replace(/\{(\w+)\}/g, ':$1')] : ['match', topic]
}

/** Every topic a subscribe URL asks for, whichever way it spells them. */
export function subscribedTopics(url: URL): string[] {
  return [...url.searchParams.getAll('match'), ...url.searchParams.getAll('match_urlpattern')]
}

/** The scoped topic the API publishes on: `/users/{userId}/api/tasks/{id}`. */
export function userTopic(userId: string, iri: string): string {
  return `/users/${userId}${iri}`
}

interface ProbeState {
  messages: Array<{ data: string }>
  open: boolean
  /** How many times the stream has dropped. A counter, not a flag — see `expectSilence`. */
  drops: number
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
    ({ id, path, params }) => {
      const url = new URL(path, window.location.origin)
      for (const [name, value] of params) {
        url.searchParams.append(name, value)
      }

      const probes = (window.__maggieMercureProbes ??= {})
      const source = new EventSource(url.toString(), { withCredentials: true })
      const state: ProbeState = {
        messages: [],
        open: false,
        drops: 0,
        close: () => source.close(),
      }

      source.onopen = () => {
        state.open = true
      }
      // Counted, not flagged. EventSource reconnects on its own, so a flag set
      // once would make every later assertion red over a blip that had already
      // healed — and a flag cleared on reconnect would hide a drop that healed
      // *inside* the window, which is the one place the gap matters. A counter
      // lets `expectSilence` ask the only useful question: did the stream drop
      // while I was proving it received nothing?
      source.onerror = () => {
        state.drops += 1
      }
      source.onmessage = (event) => {
        state.messages.push({ data: event.data })
      }

      probes[id] = state
    },
    { id, path: MERCURE_PATH, params: topics.map(subscribeParam) },
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

  const dropCount = async (): Promise<number> =>
    page.evaluate((id) => window.__maggieMercureProbes?.[id]?.drops ?? 0, id)

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

        // A custom poller rather than `expect.poll`, because this returns the
        // matching message and not just a verdict.
        // eslint-disable-next-line playwright/no-wait-for-timeout
        await page.waitForTimeout(100)
      }
    },

    async expectSilence(ms = 3_000) {
      const dropsBefore = await dropCount()

      // The one place a fixed wait is the assertion: proving an absence needs
      // a window, and there is no event to wait on instead.
      // eslint-disable-next-line playwright/no-wait-for-timeout
      await page.waitForTimeout(ms)

      // Checked first, and this is the whole point of counting: an empty
      // message list proves isolation only if the connection was open
      // throughout. A stream that dropped and came back is silent too, and
      // nothing published in the gap would ever have arrived.
      expect(
        await dropCount(),
        `the connection to ${topics.join(', ')} dropped while proving its silence — that proves nothing`,
      ).toBe(dropsBefore)

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
 *
 * @param topic wait for the subscription carrying this topic rather than for
 *              the first one to come back. A screen opens several — the
 *              dashboard's entity topics, the chat panel's `/chat/{id}` and
 *              `/contexts/{id}` — and they are registered in whatever order
 *              their effects run, so "the first one" is not the one a journey
 *              is about to publish on.
 */
export async function openSubscribed(page: Page, open: () => Promise<void>, topic?: string): Promise<void> {
  const subscribed = page.waitForResponse(
    (response) =>
      response.url().includes(MERCURE_PATH) &&
      response.status() === 200 &&
      // Parsed rather than matched on the raw URL: `URLSearchParams` percent-
      // encodes every slash, so `/chat/01J…` never appears as itself.
      (topic === undefined || subscribedTopics(new URL(response.url())).includes(subscribeParam(topic)[1])),
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

/** The e2e hub's key — `MERCURE_PUBLISHER_JWT_KEY` in `docker-compose.e2e.yml`. Not a secret: the stack is throw-away. */
const E2E_HUB_KEY = 'e2e-mercure-secret-at-least-32-bytes-long'

const base64url = (input: Buffer | string): string => Buffer.from(input).toString('base64url')

/**
 * Publishes one private update on the e2e hub, the way the agent's
 * `MercurePublisher` does: an RFC 9068 access token whose `aud` is the hub's
 * public URL and whose `authorization_details` name the topic.
 *
 * For what no public route of the stack emits on its own — a scheduled
 * proaction completing on `/proactions/{userId}` has no HTTP trigger a
 * journey may call without the owner's `ROLE_PROACTION_TRIGGER` and a model
 * scenario that schedules one. The delivery under test is the admin's.
 */
export async function publishOnHub(baseURL: string, topic: string, data: Record<string, unknown>): Promise<void> {
  const hubUrl = new URL(MERCURE_PATH, baseURL).toString()
  const now = Math.floor(Date.now() / 1000)
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'at+jwt' }))
  const payload = base64url(
    JSON.stringify({
      iss: 'maggie',
      sub: 'e2e-journey',
      client_id: 'maggie',
      aud: hubUrl,
      iat: now,
      exp: now + 300,
      jti: `urn:uuid:${crypto.randomUUID()}`,
      authorization_details: [
        {
          type: 'https://mercure.rocks/authorization-detail',
          actions: ['publish'],
          topics: [{ match: topic }],
        },
      ],
    }),
  )
  const signature = createHmac('sha256', E2E_HUB_KEY).update(`${header}.${payload}`).digest('base64url')

  const response = await fetch(hubUrl, {
    method: 'POST',
    headers: { Authorization: `Bearer ${header}.${payload}.${signature}` },
    body: new URLSearchParams({ topic, data: JSON.stringify(data), private: 'on' }),
  })
  expect(response.status, `the hub refused the update: ${await response.text()}`).toBe(200)
}
