import { COOKING_STATUS_PATH } from './cookingStatusApi'
import type { CookingStatus } from './cookingStatusApi'

/**
 * `activity-status` (MAG-613) in memory, for the tests that open or resume the activity: `handle()`
 * answers what `fetch` asks under `/api/activity-status/cuisine`, `requests` keeps the writes in order.
 */
export const fakeStatusApi = (initial: Pick<CookingStatus, 'origin' | 'state'> | null = null) => {
  let current: CookingStatus | null = initial ? { ...initial, updatedAt: '2026-10-25T12:00:00+00:00' } : null
  const requests: { method: string; body: { origin?: CookingStatus['origin']; state?: Record<string, unknown> } | null }[] = []

  return {
    requests,
    current: () => current,
    writes: () => requests.filter((request) => request.method === 'PUT').map((request) => request.body),
    deletes: () => requests.filter((request) => request.method === 'DELETE'),
    handle: (url: string, init?: RequestInit): Promise<Response> | null => {
      if (url !== COOKING_STATUS_PATH) return null
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as { origin?: CookingStatus['origin']; state?: Record<string, unknown> }) : null
      requests.push({ method, body })

      if (method === 'PUT' && body?.origin) {
        current = { origin: body.origin, state: body.state, updatedAt: '2026-10-25T12:30:00+00:00' }

        return Promise.resolve(new Response(JSON.stringify(current), { status: 200 }))
      }
      if (method === 'DELETE') {
        current = null

        return Promise.resolve(new Response(null, { status: 204 }))
      }

      return Promise.resolve(current ? new Response(JSON.stringify(current), { status: 200 }) : new Response('{"error":"No activity in progress."}', { status: 404 }))
    },
  }
}

export type FakeStatusApi = ReturnType<typeof fakeStatusApi>
