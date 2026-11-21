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
  /** `return`: the way home at the end of a stay (MAG-734); absent or `outbound` otherwise. */
  leg?: string
  mode: string
  steps: TripStep[]
  durationMinutes: number
  /** The occurrence of a series this trip serves (MAG-862). */
  occurrenceStartAt?: string
  departAt: string
  navigationUrl: string
  originLabel: string
  /** A return's arrival: `departAt` + the duration. */
  arriveAt?: string | null
  /** When the API last computed the route. */
  computedAt?: string | null
}

export const hourOf = (iso: string): string => {
  const at = new Date(iso)

  return Number.isNaN(at.getTime())
    ? '?'
    : at.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', hour12: false })
}

export const isReturn = (trip: Pick<Trip, 'leg'>): boolean => trip.leg === 'return'

/** « Partir à 13:34 · depuis la maison » — a return reads « Retour · Partir à 11:00 · arrivée 11:14 ». */
export function departureText(trip: Pick<Trip, 'departAt' | 'originLabel' | 'leg' | 'arriveAt'>): string {
  if (isReturn(trip)) {
    const arrival = trip.arriveAt ? ` · arrivée ${hourOf(trip.arriveAt)}` : ''

    return `Retour · Partir à ${hourOf(trip.departAt)}${arrival}`
  }

  return `Partir à ${hourOf(trip.departAt)} · depuis ${trip.originLabel}`
}

/** The trips by the IRI of their event: an event can have an outbound and a return. */
export function tripsByEvent(trips: Trip[]): Map<string, Trip[]> {
  const byEvent = new Map<string, Trip[]>()
  for (const trip of trips) byEvent.set(trip.event, [...(byEvent.get(trip.event) ?? []), trip])

  return byEvent
}

const TRIP_IRI = /^\/api\/trips\/[0-9A-Za-z-]{1,64}$/

export const isTripIri = (iri: string | null | undefined): iri is string => Boolean(iri && TRIP_IRI.test(iri))

/** The URL comes from the API but is opened in the owner's browser: web links only. */
const isWebUrl = (url: unknown): url is string => typeof url === 'string' && /^https?:\/\//i.test(url)

/** What reading a trip gave: its link, and whether it is gone for good (404 or 410) rather than just unreadable. */
export interface NavigationLookup {
  url: string | null
  gone: boolean
  durationMinutes?: number
  /** The event's IRI. */
  event?: string
}

export async function lookupNavigation(tripIri: string): Promise<NavigationLookup> {
  if (!isTripIri(tripIri)) return { url: null, gone: false }
  try {
    const token = getToken()
    const response = await fetch(tripIri, {
      headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
    if (!response.ok) return { url: null, gone: response.status === 404 || response.status === 410 }
    const body = (await response.json()) as { navigationUrl?: unknown; durationMinutes?: unknown; event?: unknown }

    return {
      url: isWebUrl(body.navigationUrl) ? body.navigationUrl : null,
      gone: false,
      durationMinutes: typeof body.durationMinutes === 'number' ? body.durationMinutes : undefined,
      event: typeof body.event === 'string' ? body.event : undefined,
    }
  } catch {
    return { url: null, gone: false }
  }
}

const EVENT_IRI = /^\/api\/events\/[0-9A-Za-z-]{1,64}$/

/** « 17:30 · École Jean-Moulin · 21 min porte à porte »: what the departure alert says of the event and the trip. */
export async function describeDeparture(lookup: NavigationLookup): Promise<string | null> {
  let hour: string | null = null
  let place: string | null = null
  if (lookup.event && EVENT_IRI.test(lookup.event)) {
    try {
      const token = getToken()
      const response = await fetch(lookup.event, {
        headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      })
      if (response.ok) {
        const event = (await response.json()) as { startAt?: unknown; location?: unknown; allDay?: unknown }
        if (typeof event.startAt === 'string' && event.allDay !== true) hour = hourOf(event.startAt)
        if (typeof event.location === 'string' && event.location.trim()) place = event.location.trim()
      }
    } catch {
      // The line then reads without the event.
    }
  }
  const duration = lookup.durationMinutes === undefined ? null : `${lookup.durationMinutes} min porte à porte`
  const parts = [hour, place, duration].filter((part): part is string => part !== null && part !== '?')

  return parts.length > 0 ? parts.join(' · ') : null
}

/**
 * The « Y aller » link of a trip: Waze for the car, Google Maps otherwise, built by the API.
 * `null` when the trip is gone or unreadable — the alert then has nowhere to go.
 */
export async function fetchNavigationUrl(tripIri: string): Promise<string | null> {
  return (await lookupNavigation(tripIri)).url
}

/** Opens a navigation link in a new tab, without handing it the admin's window. */
export function openNavigation(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}
