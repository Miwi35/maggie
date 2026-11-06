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
  departAt: string
  navigationUrl: string
  originLabel: string
  /** A return's arrival: `departAt` + the duration. */
  arriveAt?: string | null
}

const STEP_ICONS: Record<string, string> = {
  walk: '\u{1F6B6}',
  drive: '\u{1F697}',
  transit: '\u{1F68C}',
  bicycle: '\u{1F6B2}',
}

export const stepIcon = (kind: string) => STEP_ICONS[kind] ?? '\u{1F4CD}'

const hourOf = (iso: string): string => {
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
}

export async function lookupNavigation(tripIri: string): Promise<NavigationLookup> {
  if (!isTripIri(tripIri)) return { url: null, gone: false }
  try {
    const token = getToken()
    const response = await fetch(tripIri, {
      headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
    if (!response.ok) return { url: null, gone: response.status === 404 || response.status === 410 }
    const url = ((await response.json()) as { navigationUrl?: unknown }).navigationUrl

    return { url: isWebUrl(url) ? url : null, gone: false }
  } catch {
    return { url: null, gone: false }
  }
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
