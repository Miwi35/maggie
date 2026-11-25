import { parseDay } from '../../../dates'
import { departureText, isReturn } from '../../calendar/trips'
import type { Trip } from '../../calendar/trips'

/** An event of the day as the card draws it (MAG-562), composed from `GET /api/events` and `GET /api/agendas`. */
export interface AgendaHomeEvent {
  id: string
  summary: string
  description: string | null
  location: string | null
  locationShort: string | null
  allDay: boolean
  /** Timed events: an instant with its offset, in the event's own zone. */
  startAt?: string | null
  endAt?: string | null
  /** All-day events: their days, `YYYY-MM-DD`, the end excluded; `lastDay` is the last one covered. */
  startDate?: string | null
  endDate?: string | null
  lastDay?: string | null
  timeZone?: string | null
  status: string
  reminders: { useDefault: boolean; overrides: { method: string; minutes: number }[] } | null
  activity: string | null
  agendaId: string
  agendaName: string
  agendaColor: string | null
}

export interface AgendaHomeState {
  date: string
  /** The instant of the read: `null` for tomorrow, which has no « maintenant » line. */
  now: string | null
  events: AgendaHomeEvent[]
  /** The day's trips whose event is in `events`, soonest first. */
  trips: Trip[]
}

export type RowKind = 'past' | 'current' | 'next' | 'later' | 'plain'

export interface TimelineRow {
  event: AgendaHomeEvent
  kind: RowKind
  /** The trips of this event, by departure. */
  trips: Trip[]
}

export interface Timeline {
  allDay: TimelineRow[]
  /** Ended events, above the line (empty when there is no line). */
  past: TimelineRow[]
  /** In progress, next and later events, below the line. */
  following: TimelineRow[]
  /** Whether the « maintenant » line is drawn: only today has one. */
  hasNowLine: boolean
  /** The first outbound trip still to leave: the tile. `null` on tomorrow. */
  nextTripId: string | null
}

const ms = (instant: string | null | undefined): number => (instant ? Date.parse(instant) : Number.NaN)

/**
 * The card's rows: whole-day events apart, then the timed ones either side of the line.
 * An event is past once it has ended; one in progress sits below the line, since it is
 * not over; the first one still to start is the « next » one.
 *
 * `nowMs` is null on tomorrow: nothing is past, nothing is next, no line.
 */
export function buildTimeline(events: AgendaHomeEvent[], nowMs: number | null, trips: Trip[] = []): Timeline {
  const tripsOf = (event: AgendaHomeEvent): Trip[] =>
    trips.filter((trip) => trip.event.slice(trip.event.lastIndexOf('/') + 1) === event.id).sort((a, b) => ms(a.departAt) - ms(b.departAt))
  const row = (event: AgendaHomeEvent, kind: RowKind): TimelineRow => ({ event, kind, trips: tripsOf(event) })

  const allDay = events.filter((event) => event.allDay).map((event) => row(event, 'plain'))
  const timed = events.filter((event) => !event.allDay)

  if (nowMs === null) {
    return { allDay, past: [], following: timed.map((event) => row(event, 'plain')), hasNowLine: false, nextTripId: null }
  }

  const past: TimelineRow[] = []
  const following: TimelineRow[] = []
  let nextTaken = false
  for (const event of timed) {
    const start = ms(event.startAt)
    const end = ms(event.endAt)
    if (end <= nowMs) {
      past.push(row(event, 'past'))
    } else if (start <= nowMs) {
      following.push(row(event, 'current'))
    } else {
      following.push(row(event, nextTaken ? 'later' : 'next'))
      nextTaken = true
    }
  }

  const nextTrip = [...trips]
    .sort((a, b) => ms(a.departAt) - ms(b.departAt))
    .find((trip) => !isReturn(trip) && ms(trip.departAt) > nowMs)

  return { allDay, past, following, hasNowLine: true, nextTripId: nextTrip?.id ?? null }
}

const ZONE = 'Europe/Paris'

const timeFormat = (zone: string) => new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: zone })

/** « 14:12 » — the hour of an instant in the event's zone. */
export const clock = (instant: string | number | Date, zone: string | null | undefined = ZONE): string =>
  timeFormat(zone ?? ZONE).format(new Date(instant))

/** The day of an instant in the event's zone, `YYYY-MM-DD`. */
const dayOf = (instant: string, zone: string | null | undefined): string =>
  new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: zone ?? ZONE }).format(new Date(instant))

/** « dans 48 min », « dans 2 h », « dans 1 h 30 ». */
export function untilLabel(startMs: number, nowMs: number): string {
  const minutes = Math.max(1, Math.ceil((startMs - nowMs) / 60_000))
  if (minutes < 60) return `dans ${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60

  return rest === 0 ? `dans ${hours} h` : `dans ${hours} h ${String(rest).padStart(2, '0')}`
}

/** What a row says of the event's time: « 15:00 », « → 01:00 » when it began the day before, empty for a whole-day one. */
export function rowTime(event: AgendaHomeEvent, date: string): string {
  if (event.allDay || !event.startAt || !event.endAt) return ''
  if (dayOf(event.startAt, event.timeZone) < date) return `→ ${clock(event.endAt, event.timeZone)}`

  return clock(event.startAt, event.timeZone)
}

const dayFormat = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })

const capitalize = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1)

const longDay = (day: string): string => capitalize(dayFormat.format(parseDay(day)))

const longInstantDay = (instant: string, zone: string | null | undefined): string =>
  capitalize(new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', timeZone: zone ?? ZONE }).format(new Date(instant)))

/** The schedule as the fiche reads it: « Mercredi 14 octobre, 15:00 – 16:00 », or the days of a whole-day event. */
export function scheduleLabel(event: AgendaHomeEvent): string {
  if (event.allDay) {
    const first = event.startDate ?? ''
    const last = event.lastDay ?? first

    return last > first ? `${longDay(first)} – ${longDay(last)}` : longDay(first)
  }
  if (!event.startAt || !event.endAt) return ''

  const day = longInstantDay(event.startAt, event.timeZone)
  const sameDay = dayOf(event.startAt, event.timeZone) === dayOf(event.endAt, event.timeZone)
  const start = clock(event.startAt, event.timeZone)
  const end = clock(event.endAt, event.timeZone)

  return sameDay
    ? `${day}, ${start} – ${end}`
    : `${day}, ${start} – ${longInstantDay(event.endAt, event.timeZone)}, ${end}`
}

const dayShortFormat = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: 'numeric', month: 'short', timeZone: ZONE })

/** « mer. 14 oct. » — the card's day, short. */
export const dayShort = (date: string): string => dayShortFormat.format(new Date(`${date}T12:00:00Z`))

/** The header's count: « 2 passés · 4 à venir » today (a zero left out), « 3 prévus » on a day with no line. */
export function countLabel(timeline: Timeline): string {
  const coming = timeline.allDay.length + timeline.following.length
  if (!timeline.hasNowLine) return `${coming} ${coming === 1 ? 'prévu' : 'prévus'}`

  const done = timeline.past.length
  return [
    done > 0 ? `${done} ${done === 1 ? 'passé' : 'passés'}` : null,
    coming > 0 ? `${coming} à venir` : null,
  ]
    .filter(Boolean)
    .join(' · ')
}

/** The line under a row's title: « Salle 2 · Perso », ended by « · fait » for a done event. */
export function rowSub(event: AgendaHomeEvent, past: boolean): string {
  return [event.locationShort, event.agendaName, past ? 'fait' : null].filter(Boolean).join(' · ')
}

/** What a trip line says: « Partir à 18:05 » then « · depuis X », « Parti à » once left; a return keeps its own text. */
export function tripLine(trip: Trip, nowMs: number | null): { verb: string; from: string; past: boolean } {
  if (isReturn(trip)) return { verb: departureText(trip), from: '', past: nowMs !== null && ms(trip.departAt) <= nowMs }
  const past = nowMs !== null && ms(trip.departAt) <= nowMs

  return {
    verb: `${past ? 'Parti' : 'Partir'} à ${clock(trip.departAt)}`,
    from: trip.originLabel ? `· depuis ${trip.originLabel}` : '',
    past,
  }
}
