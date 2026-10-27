import { getToken } from '../../auth/session'

export interface TripStep {
  kind: string
  minutes: number
  label?: string
}

/** The trip Maggie chose to an event (`GET /api/trips`, MAG-654). */
export interface Trip {
  id: string
  /** The event's IRI. */
  event: string
  mode: string
  steps: TripStep[]
  durationMinutes: number
  departAt: string
  navigationUrl: string
  originLabel: string
}

const STEP_ICONS: Record<string, string> = {
  walk: '\u{1F6B6}',
  drive: '\u{1F697}',
  transit: '\u{1F68C}',
  bicycle: '\u{1F6B2}',
}

export const stepIcon = (kind: string) => STEP_ICONS[kind] ?? '\u{1F4CD}'

/** « Partir à 13:34 · depuis la maison » */
export function departureText(trip: Pick<Trip, 'departAt' | 'originLabel'>): string {
  const at = new Date(trip.departAt)
  const time = Number.isNaN(at.getTime())
    ? '?'
    : at.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })

  return `Partir à ${time} · depuis ${trip.originLabel}`
}

/** The trips by the IRI of their event. */
export function tripsByEvent(trips: Trip[]): Map<string, Trip> {
  return new Map(trips.map((trip) => [trip.event, trip]))
}

const TRIP_IRI = /^\/api\/trips\/[0-9A-Za-z-]{1,64}$/

export const isTripIri = (iri: string | null | undefined): iri is string => Boolean(iri && TRIP_IRI.test(iri))

/** The URL comes from the API but is opened in the owner's browser: web links only. */
const isWebUrl = (url: unknown): url is string => typeof url === 'string' && /^https?:\/\//i.test(url)

/**
 * The « Y aller » link of a trip: Waze for the car, Google Maps otherwise, built by the API.
 * `null` when the trip is gone or unreadable — the alert then has nowhere to go.
 */
export async function fetchNavigationUrl(tripIri: string): Promise<string | null> {
  if (!isTripIri(tripIri)) return null
  try {
    const token = getToken()
    const response = await fetch(tripIri, {
      headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
    if (!response.ok) return null
    const url = ((await response.json()) as { navigationUrl?: unknown }).navigationUrl

    return isWebUrl(url) ? url : null
  } catch {
    return null
  }
}

/** Opens a navigation link in a new tab, without handing it the admin's window. */
export function openNavigation(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}
