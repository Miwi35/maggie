import { parseDay } from '../../dates'
import { isReturn, type Trip } from './trips'

/** What the day list needs to know of an entry of the agenda (an event, a task, a meal…). */
export interface DayEntry {
  id?: string
  start?: unknown
  end?: unknown
  allDay?: boolean
  extendedProps?: { masterEventId?: string; isVirtualOccurrence?: boolean }
}

export type DayRow<T extends DayEntry = DayEntry> =
  | { kind: 'allDay'; entry: T; past: false; next: false; highlighted: false }
  | { kind: 'event'; entry: T; at: Date; endAt: Date | null; past: boolean; next: false; highlighted: boolean }
  | { kind: 'trip'; trip: Trip; entry: T; at: Date; past: boolean; next: boolean; highlighted: false }

const toDate = (value: unknown): Date | null => {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const date = new Date(value)

  return Number.isNaN(date.getTime()) ? null : date
}

/** The entries that fall on [start, end): an all-day entry is a pair of local dates, the end excluded. */
export function entriesOfDay<T extends DayEntry>(entries: T[], start: Date, end: Date): T[] {
  const bound = (value: unknown, allDay: boolean): Date | null => {
    if (typeof value === 'string' && allDay && value.length === 10) return parseDay(value)

    return toDate(value)
  }

  return entries.filter((entry) => {
    const allDay = Boolean(entry.allDay)
    const from = bound(entry.start, allDay)
    if (!from) return false
    const to = bound(entry.end, allDay) ?? (allDay ? new Date(from.getFullYear(), from.getMonth(), from.getDate() + 1) : from)

    return from < end && (to > start || from >= start)
  })
}

/**
 * The day as a list (MAG-858): the all-day entries first, then the events and the trips by hour. A trip
 * sits at its departure, before its event on a tie. The next departure is the first outbound trip still
 * to leave; the highlighted event is the first one not started.
 */
export function dayRows<T extends DayEntry>(entries: T[], trips: Trip[], now: Date): DayRow<T>[] {
  const byId = new Map(entries.map((entry) => [entry.id, entry]))
  const iri = (id: string) => (id.startsWith('/') ? id : `/api/events/${id}`)
  const occurrenceOf = (trip: Trip): T | undefined => {
    const from = toDate(trip.occurrenceStartAt)?.getTime()
    if (from === undefined) return undefined

    return entries.find(
      (entry) =>
        entry.extendedProps?.isVirtualOccurrence &&
        entry.extendedProps.masterEventId !== undefined &&
        iri(entry.extendedProps.masterEventId) === trip.event &&
        toDate(entry.start)?.getTime() === from,
    )
  }
  const allDay: DayRow<T>[] = []
  const timed: { row: DayRow<T>; at: number; order: number }[] = []

  for (const entry of entries) {
    const at = toDate(entry.start)
    if (entry.allDay || !at) {
      allDay.push({ kind: 'allDay', entry, past: false, next: false, highlighted: false })
      continue
    }
    const endAt = toDate(entry.end)
    timed.push({
      row: { kind: 'event', entry, at, endAt, past: (endAt ?? at).getTime() <= now.getTime(), next: false, highlighted: false },
      at: at.getTime(),
      order: 1,
    })
  }

  for (const trip of trips) {
    const entry = byId.get(trip.event) ?? occurrenceOf(trip)
    const at = toDate(trip.departAt)
    // An outbound trip of an all-day entry has no hour to leave for: only the way home shows.
    if (!entry || !at || (entry.allDay && !isReturn(trip))) continue
    timed.push({
      row: { kind: 'trip', trip, entry, at, past: at.getTime() <= now.getTime(), next: false, highlighted: false },
      at: at.getTime(),
      order: 0,
    })
  }

  timed.sort((a, b) => a.at - b.at || a.order - b.order)
  const rows = timed.map(({ row }) => row)

  const nextTrip = rows.find((row) => row.kind === 'trip' && !row.past && !isReturn(row.trip))
  if (nextTrip) nextTrip.next = true
  const upcoming = rows.find((row) => row.kind === 'event' && row.at.getTime() > now.getTime())
  if (upcoming) upcoming.highlighted = true

  return [...allDay, ...rows]
}

/** « dans 12 min », « dans 3 h 04 » beyond the hour. */
export function timeLeft(minutes: number): string {
  const total = Math.max(0, Math.round(minutes))
  if (total < 60) return `dans ${total} min`

  return `dans ${Math.floor(total / 60)} h ${String(total % 60).padStart(2, '0')}`
}
