import { parseDay } from '../../../dates'

/** An event of `GET /api/home/agenda`, as the API describes it (MAG-562). */
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
  /** The server's clock, in the owner's zone: `null` for tomorrow, which has no « maintenant » line. */
  now: string | null
  events: AgendaHomeEvent[]
}

export type RowKind = 'past' | 'current' | 'next' | 'later' | 'plain'

export interface TimelineRow {
  event: AgendaHomeEvent
  kind: RowKind
}

export interface Timeline {
  allDay: TimelineRow[]
  /** Ended events, above the line (empty when there is no line). */
  past: TimelineRow[]
  /** In progress, next and later events, below the line. */
  following: TimelineRow[]
  /** Whether the « maintenant » line is drawn: only today has one. */
  hasNowLine: boolean
}

const ms = (instant: string | null | undefined): number => (instant ? Date.parse(instant) : Number.NaN)

/**
 * The card's rows: whole-day events apart, then the timed ones either side of the line.
 * An event is past once it has ended; one in progress sits below the line, since it is
 * not over; the first one still to start is the « next » one.
 *
 * `nowMs` is null on tomorrow: nothing is past, nothing is next, no line.
 */
export function buildTimeline(events: AgendaHomeEvent[], nowMs: number | null): Timeline {
  const allDay = events.filter((event) => event.allDay).map((event): TimelineRow => ({ event, kind: 'plain' }))
  const timed = events.filter((event) => !event.allDay)

  if (nowMs === null) {
    return { allDay, past: [], following: timed.map((event) => ({ event, kind: 'plain' })), hasNowLine: false }
  }

  const past: TimelineRow[] = []
  const following: TimelineRow[] = []
  let nextTaken = false
  for (const event of timed) {
    const start = ms(event.startAt)
    const end = ms(event.endAt)
    if (end <= nowMs) {
      past.push({ event, kind: 'past' })
    } else if (start <= nowMs) {
      following.push({ event, kind: 'current' })
    } else {
      following.push({ event, kind: nextTaken ? 'later' : 'next' })
      nextTaken = true
    }
  }

  return { allDay, past, following, hasNowLine: true }
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
