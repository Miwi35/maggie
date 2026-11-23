import { BILAN_STATUS_PATH } from './bilanStatusApi'
import type { BilanStatus } from './bilanStatusApi'

type Body = { origin?: BilanStatus['origin']; state?: Record<string, unknown> }

/**
 * `activity-status` for the bilan in memory, for the tests: `handle()` answers what `fetch` asks under
 * `/api/activity-status/bilan`, `requests` keeps the calls in order.
 */
export const fakeBilanStatusApi = (initial: Pick<BilanStatus, 'origin' | 'state'> | null = null) => {
  let current: BilanStatus | null = initial ? { ...initial, updatedAt: '2026-11-23T12:00:00+00:00' } : null
  const requests: { method: string; body: Body | null }[] = []

  return {
    requests,
    current: () => current,
    /** What another device or Maggie wrote. */
    set: (status: Pick<BilanStatus, 'origin' | 'state'> | null) => {
      current = status ? { ...status, updatedAt: '2026-11-23T12:30:00+00:00' } : null
    },
    writes: () => requests.filter((request) => request.method === 'PUT').map((request) => request.body),
    deletes: () => requests.filter((request) => request.method === 'DELETE'),
    handle: (url: string, init?: RequestInit): Promise<Response> | null => {
      if (url !== BILAN_STATUS_PATH) return null
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Body) : null
      requests.push({ method, body })

      if (method === 'PUT' && body?.origin) {
        current = { origin: body.origin, state: body.state ?? {}, updatedAt: '2026-11-23T12:30:00+00:00' }

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

export type FakeBilanStatusApi = ReturnType<typeof fakeBilanStatusApi>
