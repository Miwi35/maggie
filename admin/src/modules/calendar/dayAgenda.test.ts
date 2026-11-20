import { describe, expect, test } from 'vitest'
import { dayRows, entriesOfDay, timeLeft } from './dayAgenda'
import type { Trip } from './trips'

const at = (hour: number, minute = 0) => new Date(2037, 0, 15, hour, minute)
const entry = (id: string, start: Date, minutes = 60, allDay = false) => ({
  id,
  start: allDay ? '2037-01-15' : start.toISOString(),
  end: allDay ? '2037-01-16' : new Date(start.getTime() + minutes * 60_000).toISOString(),
  allDay,
})
const trip = (event: string, departAt: Date, extra: Partial<Trip> = {}): Trip => ({
  id: `/api/trips/${event}`,
  event,
  mode: 'drive',
  steps: [],
  durationMinutes: 20,
  departAt: departAt.toISOString(),
  navigationUrl: 'https://waze.com',
  originLabel: 'la maison',
  ...extra,
})
const kinds = (rows: ReturnType<typeof dayRows>) => rows.map((row) => (row.kind === 'trip' ? `trip:${row.trip.event}` : `${row.kind}:${row.entry.id}`))

describe('dayRows', () => {
  test('events and trips are ordered by time, a trip before its event on a tie', () => {
    const rows = dayRows([entry('b', at(18, 25), 5), entry('a', at(9)), entry('c', at(20))], [trip('b', at(17, 39)), trip('c', at(20))], at(8))

    expect(kinds(rows)).toEqual(['event:a', 'trip:b', 'event:b', 'trip:c', 'event:c'])
  })

  test('a trip of a series sits with the occurrence it serves', () => {
    const occurrence = (day: number) => ({
      ...entry(`/api/events/S__2037-01-${day}`, new Date(2037, 0, day, 18)),
      extendedProps: { masterEventId: '/api/events/S', isVirtualOccurrence: true },
    })
    const rows = dayRows([occurrence(15)], [trip('/api/events/S', at(17, 30), { occurrenceStartAt: at(18).toISOString() })], at(8))

    expect(rows.map((row) => row.kind)).toEqual(['trip', 'event'])
  })

  test('a trip of a series does not attach to another occurrence', () => {
    const other = {
      ...entry('/api/events/S__2037-01-15', at(18)),
      extendedProps: { masterEventId: 'S', isVirtualOccurrence: true },
    }
    const rows = dayRows([other], [trip('/api/events/S', at(17, 30), { occurrenceStartAt: new Date(2037, 0, 8, 18).toISOString() })], at(8))

    expect(rows.map((row) => row.kind)).toEqual(['event'])
  })

  test('all-day entries come first', () => {
    const rows = dayRows([entry('a', at(9)), entry('j', at(0), 0, true)], [], at(8))

    expect(kinds(rows)).toEqual(['allDay:j', 'event:a'])
  })

  test('an outbound trip of an all-day entry is left out, its return stays', () => {
    const rows = dayRows([entry('j', at(0), 0, true)], [trip('j', at(8)), trip('j', at(11), { leg: 'return' })], at(7))

    expect(kinds(rows)).toEqual(['allDay:j', 'trip:j'])
    expect(rows[1]).toMatchObject({ trip: { leg: 'return' } })
  })

  test('only the first trip still to leave is next', () => {
    const rows = dayRows([entry('a', at(10)), entry('b', at(12)), entry('c', at(15))], [trip('a', at(9)), trip('b', at(11)), trip('c', at(14))], at(10, 30))
    const next = rows.filter((row) => row.next).map((row) => (row.kind === 'trip' ? row.trip.event : ''))

    expect(next).toEqual(['b'])
  })

  test('a past trip and an ended event are past', () => {
    const rows = dayRows([entry('a', at(10)), entry('b', at(15))], [trip('a', at(9))], at(12))

    expect(rows.map((row) => row.past)).toEqual([true, true, false])
  })

  test('the first event not started is highlighted', () => {
    const rows = dayRows([entry('a', at(10)), entry('b', at(15)), entry('c', at(16))], [], at(12))

    expect(rows.filter((row) => row.highlighted).map((row) => row.entry.id)).toEqual(['b'])
  })
})

describe('timeLeft', () => {
  test('reads minutes then hours', () => {
    expect(timeLeft(12)).toBe('dans 12 min')
    expect(timeLeft(59)).toBe('dans 59 min')
    expect(timeLeft(184)).toBe('dans 3 h 04')
  })
})

describe('entriesOfDay', () => {
  test('keeps what falls on the day: timed, all-day, and a stay running through it', () => {
    const day = [new Date(2037, 0, 15), new Date(2037, 0, 16)] as const
    const stay = { id: 'stay', start: '2037-01-14', end: '2037-01-17', allDay: true }
    const gone = { id: 'gone', start: '2037-01-14', end: '2037-01-15', allDay: true }
    const kept = entriesOfDay([entry('a', at(9)), { ...entry('b', at(9)), start: '2037-01-16T09:00:00' }, stay, gone], ...day)

    expect(kept.map((e) => e.id)).toEqual(['a', 'stay'])
  })
})
