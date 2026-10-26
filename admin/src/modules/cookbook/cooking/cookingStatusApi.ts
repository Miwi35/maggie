import type { CookingOrigin } from './cookingSession'

export const COOKING_STATUS_PATH = '/api/activity-status/cuisine'

/** What the API publishes when the activity in progress changes or ends. Spelled once, for the contract test. */
export const COOKING_STATUS_TOPICS = ['/api/activity-status/{id}']

/** The Cuisine activity in progress, as `GET /api/activity-status/cuisine` serves it (MAG-613). */
export interface CookingStatus {
  origin: CookingOrigin
  /** The session of the activity, an opaque object for the API. */
  state: unknown
  updatedAt: string
}

const headers = (body: boolean): HeadersInit => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }
}

/** The activity in progress; null when there is none (404). Anything else that fails throws. */
export const fetchCookingStatus = async (signal?: AbortSignal): Promise<CookingStatus | null> => {
  const response = await fetch(COOKING_STATUS_PATH, { signal, headers: headers(false) })
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`GET ${COOKING_STATUS_PATH} answered ${response.status}`)

  const status = (await response.json()) as Partial<CookingStatus> | null

  return status?.origin?.kind && status.origin.id ? (status as CookingStatus) : null
}

export const putCookingStatus = async (origin: CookingOrigin, state: unknown): Promise<void> => {
  const response = await fetch(COOKING_STATUS_PATH, { method: 'PUT', headers: headers(true), body: JSON.stringify({ origin, state }) })
  if (!response.ok) throw new Error(`PUT ${COOKING_STATUS_PATH} answered ${response.status}`)
}

export const deleteCookingStatus = async (): Promise<void> => {
  const response = await fetch(COOKING_STATUS_PATH, { method: 'DELETE', headers: headers(false) })
  if (!response.ok) throw new Error(`DELETE ${COOKING_STATUS_PATH} answered ${response.status}`)
}
