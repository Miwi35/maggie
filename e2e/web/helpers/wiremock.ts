import { expect } from '@playwright/test'
import type { APIRequestContext, PlaywrightWorkerArgs } from '@playwright/test'

/**
 * Reading WireMock's request journal — what the stack asked Google for.
 *
 * Why a journey needs it at all: the Google pull is asynchronous (a
 * `PullFromGoogleCommand` on RabbitMQ, handled by the worker) and it leaves no
 * trace the API exposes. `lastGoogleSyncAt` is on the agenda but not in its
 * indexed projection, so `/api/agendas` cannot say whether a sync has run. The
 * journal can: one more `GET …/events` means one more pull completed its request.
 *
 * Polling that instead of sleeping is what makes the "a local change survives the
 * next pull" assertion mean anything. Asserting it *before* the second pull has
 * run would pass on a sync guard that does not exist.
 *
 * Reached by service name on the e2e network, not through Traefik: WireMock has no
 * router label, and this is the container the agenda's Google calls really go to
 * (`GOOGLE_API_BASE_URL=http://wiremock:8080/google/`). Its own request context,
 * because the `api` fixture is pinned to the admin's origin.
 */

const WIREMOCK_URL = process.env.E2E_WIREMOCK_URL ?? 'http://wiremock:8080'

export interface WiremockJournal {
  /** How many requests so far whose method and path match. */
  count(method: string, pathPattern: RegExp): Promise<number>
  /** Waits until that count reaches `target`, or fails the test. */
  waitForCount(method: string, pathPattern: RegExp, target: number, timeout?: number): Promise<void>
  /** The bodies of the requests so far whose method and path match, oldest first. */
  bodies(method: string, pathPattern: RegExp): Promise<string[]>
  /**
   * Registers a stub for this journey alone and returns its id.
   *
   * `DELETE /__admin/mappings` would reset every stub to what is on disk, other
   * journeys' included — a journey that needs a one-off answer removes only its own,
   * with {@link removeStub}.
   */
  addStub(mapping: Record<string, unknown>): Promise<string>
  removeStub(id: string): Promise<void>
  /** Puts every stateful stub back in its first state. */
  resetScenarios(): Promise<void>
  dispose(): Promise<void>
}

interface JournalEntry {
  request?: { method?: string; url?: string; body?: string }
}

export async function openWiremockJournal(playwright: PlaywrightWorkerArgs['playwright']): Promise<WiremockJournal> {
  const api: APIRequestContext = await playwright.request.newContext({ baseURL: WIREMOCK_URL })

  const entries = async (): Promise<JournalEntry[]> => {
    const response = await api.get('/__admin/requests')
    expect(response.ok(), `WireMock's journal answered ${response.status()} — is the stack up?`).toBe(true)

    const body = (await response.json()) as { requests?: JournalEntry[] }

    return body.requests ?? []
  }

  const matching = async (method: string, pathPattern: RegExp): Promise<JournalEntry[]> =>
    (await entries()).filter(
      (entry) =>
        entry.request?.method === method &&
        typeof entry.request.url === 'string' &&
        pathPattern.test(entry.request.url),
    )

  const count = async (method: string, pathPattern: RegExp): Promise<number> =>
    (await matching(method, pathPattern)).length

  return {
    count,

    async bodies(method, pathPattern) {
      return (await matching(method, pathPattern)).map((entry) => entry.request?.body ?? '').reverse()
    },

    async addStub(mapping) {
      const response = await api.post('/__admin/mappings', { data: mapping })
      expect(response.status(), `WireMock refused the stub: ${await response.text()}`).toBe(201)

      return ((await response.json()) as { id: string }).id
    },

    async removeStub(id) {
      await api.delete(`/__admin/mappings/${id}`)
    },

    async resetScenarios() {
      await api.post('/__admin/scenarios/reset')
    },

    async waitForCount(method, pathPattern, target, timeout = 30_000) {
      await expect
        .poll(() => count(method, pathPattern), {
          timeout,
          message: `WireMock should have seen ${target} ${method} request(s) matching ${pathPattern}`,
        })
        .toBeGreaterThanOrEqual(target)
    },

    async dispose() {
      await api.dispose()
    },
  }
}
