import type { CookingTimer } from './cookingTimers'

/**
 * The timers API of MAG-582 in memory, for the activity's tests: `handle()` answers what `fetch` asks
 * under `/api/cooking/timers`, `ring()` does what the worker does at the end.
 */
export const fakeTimersApi = () => {
  const timers = new Map<string, CookingTimer & { endsAtMs: number | null }>()
  const requests: { method: string; url: string; body: Record<string, unknown> | null }[] = []
  let sequence = 0
  let rung = 0
  let failing = false

  const describe = (timer: CookingTimer & { endsAtMs: number | null }): CookingTimer => {
    const { endsAtMs, ...rest } = timer
    const remainingSeconds = timer.status === 'running' && endsAtMs !== null ? Math.max(0, Math.ceil((endsAtMs - Date.now()) / 1000)) : timer.remainingSeconds

    return { ...rest, remainingSeconds, endsAt: timer.status === 'running' && endsAtMs !== null ? new Date(endsAtMs).toISOString() : null }
  }

  const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

  const change = (timer: CookingTimer & { endsAtMs: number | null }, action: string, seconds: number | undefined) => {
    const left = describe(timer).remainingSeconds
    if (action === 'pause' && timer.status === 'running') Object.assign(timer, { status: 'paused', remainingSeconds: left, endsAtMs: null })
    else if (action === 'resume' && timer.status === 'paused') Object.assign(timer, { status: 'running', endsAtMs: Date.now() + timer.remainingSeconds * 1000 })
    else if (action === 'add' && (timer.status === 'running' || timer.status === 'paused')) {
      const rest = Math.max(0, left + (seconds ?? 60))
      timer.durationSeconds = Math.max(1, timer.durationSeconds + (seconds ?? 60), rest)
      timer.remainingSeconds = rest
      if (timer.status === 'running') timer.endsAtMs = Date.now() + rest * 1000
    } else if (action === 'dismiss' && timer.status === 'done') timer.status = 'dismissed'
    else if (action === 'cancel' && timer.status !== 'dismissed' && timer.status !== 'cancelled') timer.status = 'cancelled'
    else return false

    return true
  }

  return {
    requests,
    /** Every POST and PATCH refused with a 503, as an unreachable API. */
    fail: (value: boolean) => {
      failing = value
    },
    timer: (id: string) => describe(timers.get(id)!),
    all: () => [...timers.values()].map(describe),
    /** The worker's end: done, with the moment it rang. Returns the Mercure payload. */
    ring: (id: string) => {
      const timer = timers.get(id)!
      Object.assign(timer, { status: 'done', remainingSeconds: 0, endsAtMs: null, finishedAt: new Date(Date.now() + rung++).toISOString() })

      return { '@id': `/api/cooking/timers/${id}`, ...describe(timer) }
    },
    handle: (url: string, init?: RequestInit): Promise<Response> | null => {
      if (!url.startsWith('/api/cooking/timers')) return null
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : null
      requests.push({ method, url, body })
      if (failing && method !== 'GET') return Promise.resolve(new Response('', { status: 503 }))

      if (method === 'GET') {
        const member = [...timers.values()].filter((timer) => ['running', 'paused', 'done'].includes(timer.status)).map(describe)

        return json({ member, totalItems: member.length })
      }
      if (method === 'POST' && body) {
        const id = `T${++sequence}`
        const duration = Number(body.durationSeconds)
        timers.set(id, {
          id,
          label: String(body.label),
          recipe: String(body.recipe),
          stepPosition: Number(body.stepPosition),
          durationSeconds: duration,
          endsAt: null,
          endsAtMs: Date.now() + duration * 1000,
          remainingSeconds: duration,
          status: 'running',
          finishedAt: null,
        })

        return json(describe(timers.get(id)!), 201)
      }
      const id = url.split('/').pop() ?? ''
      const timer = timers.get(id)
      if (method === 'PATCH' && timer && body) {
        return change(timer, String(body.action), body.seconds as number | undefined)
          ? json(describe(timer))
          : json({ error: 'Refused', timer: describe(timer) }, 400)
      }

      return json({ error: 'Not found' }, 404)
    },
  }
}

export type FakeTimersApi = ReturnType<typeof fakeTimersApi>
