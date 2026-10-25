import type { CookingTimer } from './cookingTimers'

export const TIMERS_PATH = '/api/cooking/timers'

/** What the API publishes on every change of a timer: the whole timer. Spelled once, for the contract test. */
export const TIMER_TOPICS = ['/api/cooking/timers/{id}']

export type TimerAction = 'pause' | 'resume' | 'add' | 'cancel' | 'dismiss'

/** The API refused or lost the timer: `timer` is how it stands (400), `gone` an unknown one (404). */
export class TimerRequestError extends Error {
  constructor(
    message: string,
    readonly timer: CookingTimer | null = null,
    readonly gone = false,
  ) {
    super(message)
  }
}

const send = async (path: string, init: RequestInit = {}): Promise<CookingTimer | { member: CookingTimer[] }> => {
  const token = localStorage.getItem('token')
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as { error?: string; timer?: CookingTimer } | null
    throw new TimerRequestError(body?.error ?? `${init.method ?? 'GET'} ${path} answered ${response.status}`, body?.timer ?? null, response.status === 404)
  }

  return (await response.json()) as CookingTimer | { member: CookingTimer[] }
}

/** The running, paused and rung timers: the API is the authority a reload reads back. */
export const fetchTimers = async (signal?: AbortSignal): Promise<CookingTimer[]> => {
  const body = (await send(`${TIMERS_PATH}?status=running,paused,done`, { signal })) as { member?: CookingTimer[] }

  return Array.isArray(body.member) ? body.member : []
}

export interface TimerStart {
  label: string
  recipe: string
  stepPosition: number
  durationSeconds: number
}

export const startTimer = async (start: TimerStart): Promise<CookingTimer> =>
  (await send(TIMERS_PATH, { method: 'POST', body: JSON.stringify(start) })) as CookingTimer

export const changeTimer = async (id: string, action: TimerAction, seconds?: number): Promise<CookingTimer> =>
  (await send(`${TIMERS_PATH}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(seconds === undefined ? { action } : { action, seconds }),
  })) as CookingTimer
