export const BILAN_STATUS_PATH = '/api/activity-status/bilan'

/** The route of the activity, which the chat's card leads to. */
export const BILAN_ACTIVITY_PATH = '/activites/bilan'

/** What the API publishes when the activity in progress changes or ends. */
export const BILAN_STATUS_TOPICS = ['/api/activity-status/{id}']

/** A bilan is started for a day: `{kind: 'day', id: 'YYYY-MM-DD'}`. */
export interface BilanOrigin {
  kind: string
  id: string
}

/** The bilan in progress, as `GET /api/activity-status/bilan` serves it (MAG-747). */
export interface BilanStatus {
  origin: BilanOrigin
  /** `{step, answers, instructions}`: an opaque object for the API. */
  state: Record<string, unknown>
  updatedAt: string
}

const headers = (body: boolean): HeadersInit => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }
}

/** The bilan in progress; null when there is none (404). Anything else that fails throws. */
export const fetchBilanStatus = async (signal?: AbortSignal): Promise<BilanStatus | null> => {
  const response = await fetch(BILAN_STATUS_PATH, { signal, headers: headers(false) })
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`GET ${BILAN_STATUS_PATH} answered ${response.status}`)

  const status = (await response.json()) as Partial<BilanStatus> | null

  return status?.origin?.kind && status.origin.id ? (status as BilanStatus) : null
}

export const putBilanStatus = async (origin: BilanOrigin, state: unknown): Promise<void> => {
  const response = await fetch(BILAN_STATUS_PATH, { method: 'PUT', headers: headers(true), body: JSON.stringify({ origin, state }) })
  if (!response.ok) throw new Error(`PUT ${BILAN_STATUS_PATH} answered ${response.status}`)
}

export const deleteBilanStatus = async (): Promise<void> => {
  const response = await fetch(BILAN_STATUS_PATH, { method: 'DELETE', headers: headers(false) })
  if (!response.ok) throw new Error(`DELETE ${BILAN_STATUS_PATH} answered ${response.status}`)
}

interface Pending {
  origin: BilanOrigin
  state: unknown
}

/**
 * Writes the bilan's state in order: never two requests in flight, and while one is out only the
 * latest state follows. A failure is dropped, the next change writes again.
 */
export const createBilanWriter = (put: typeof putBilanStatus = putBilanStatus, remove: typeof deleteBilanStatus = deleteBilanStatus) => {
  let chain: Promise<void> = Promise.resolve()
  let pending: Pending | null = null

  const flush = async () => {
    const next = pending
    pending = null
    if (next) await put(next.origin, next.state).catch(() => undefined)
  }

  return {
    save: (origin: BilanOrigin, state: unknown): void => {
      const queued = pending !== null
      pending = { origin, state }
      if (!queued) chain = chain.then(flush)
    },
    /** The bilan is over: what waits is dropped, then the status is removed. */
    clear: (): void => {
      pending = null
      chain = chain.then(() => remove().catch(() => undefined))
    },
    /** Resolves once everything asked so far has been sent. */
    settled: (): Promise<void> => chain,
  }
}

export type BilanWriter = ReturnType<typeof createBilanWriter>
