import { describe, test, expect } from 'vitest'
import { buildTimeline, clock, rowTime, scheduleLabel, untilLabel } from './agendaTimeline'
import type { AgendaHomeEvent } from './agendaTimeline'

const timed = (id: string, startAt: string, endAt: string, extra: Partial<AgendaHomeEvent> = {}): AgendaHomeEvent => ({
  id,
  summary: id,
  description: null,
  location: null,
  locationShort: null,
  allDay: false,
  startAt,
  endAt,
  timeZone: 'Europe/Paris',
  status: 'confirmed',
  reminders: null,
  activity: null,
  agendaId: 'a',
  agendaName: 'Perso',
  agendaColor: '#4285F4',
  ...extra,
})

const NOW = Date.parse('2026-10-14T14:12:00+02:00')

const done = timed('done', '2026-10-14T09:00:00+02:00', '2026-10-14T10:00:00+02:00')
const running = timed('running', '2026-10-14T13:30:00+02:00', '2026-10-14T14:30:00+02:00')
const next = timed('next', '2026-10-14T15:00:00+02:00', '2026-10-14T16:00:00+02:00')
const later = timed('later', '2026-10-14T18:00:00+02:00', '2026-10-14T19:00:00+02:00')
const allDay: AgendaHomeEvent = {
  ...timed('stay', '', ''),
  allDay: true,
  startAt: null,
  endAt: null,
  startDate: '2026-10-13',
  endDate: '2026-10-16',
  lastDay: '2026-10-15',
}

describe('buildTimeline', () => {
  test('puts the ended events above the line and the others below, the first to start being the next one', () => {
    const timeline = buildTimeline([allDay, done, running, next, later], NOW)

    expect(timeline.hasNowLine).toBe(true)
    expect(timeline.allDay.map((row) => row.event.id)).toEqual(['stay'])
    expect(timeline.past.map((row) => [row.event.id, row.kind])).toEqual([['done', 'past']])
    expect(timeline.following.map((row) => [row.event.id, row.kind])).toEqual([
      ['running', 'current'],
      ['next', 'next'],
      ['later', 'later'],
    ])
  })

  test('an event that ends exactly now is done', () => {
    const timeline = buildTimeline([timed('edge', '2026-10-14T13:12:00+02:00', '2026-10-14T14:12:00+02:00')], NOW)

    expect(timeline.past).toHaveLength(1)
  })

  test('has no line, no past and no next without a clock (tomorrow)', () => {
    const timeline = buildTimeline([allDay, done, next], null)

    expect(timeline.hasNowLine).toBe(false)
    expect(timeline.past).toEqual([])
    expect(timeline.following.map((row) => [row.event.id, row.kind])).toEqual([
      ['done', 'plain'],
      ['next', 'plain'],
    ])
  })

  test('puts an event begun the day before and over by now above the line', () => {
    const overMidnight = timed('soiree', '2026-10-13T23:00:00+02:00', '2026-10-14T01:00:00+02:00')

    expect(buildTimeline([overMidnight, next], NOW).past.map((row) => row.event.id)).toEqual(['soiree'])
  })
})

describe('labels', () => {
  test('says how long until an event', () => {
    expect(untilLabel(NOW + 48 * 60_000, NOW)).toBe('dans 48 min')
    expect(untilLabel(NOW + 60 * 60_000, NOW)).toBe('dans 1 h')
    expect(untilLabel(NOW + 95 * 60_000, NOW)).toBe('dans 1 h 35')
    expect(untilLabel(NOW + 125 * 60_000, NOW)).toBe('dans 2 h 05')
    expect(untilLabel(NOW + 5_000, NOW)).toBe('dans 1 min')
  })

  test('reads the hour in the owner zone, whatever the machine zone', () => {
    expect(clock('2026-10-14T13:05:00Z')).toBe('15:05')
    expect(clock('2026-10-14T09:00:00+02:00', 'Europe/Paris')).toBe('09:00')
  })

  test('row time: the start, the end when begun the day before, nothing for a whole-day event', () => {
    expect(rowTime(next, '2026-10-14')).toBe('15:00')
    expect(rowTime(timed('s', '2026-10-13T23:00:00+02:00', '2026-10-14T01:00:00+02:00'), '2026-10-14')).toBe('→ 01:00')
    expect(rowTime(allDay, '2026-10-14')).toBe('')
  })

  test('schedule: one day, over midnight, and whole days with the last one included', () => {
    expect(scheduleLabel(next)).toBe('Mercredi 14 octobre, 15:00 – 16:00')
    expect(scheduleLabel(timed('s', '2026-10-13T23:00:00+02:00', '2026-10-14T01:00:00+02:00'))).toBe(
      'Mardi 13 octobre, 23:00 – Mercredi 14 octobre, 01:00',
    )
    expect(scheduleLabel(allDay)).toBe('Mardi 13 octobre – Jeudi 15 octobre')
    expect(scheduleLabel({ ...allDay, startDate: '2026-10-15', endDate: '2026-10-16', lastDay: '2026-10-15' })).toBe('Jeudi 15 octobre')
  })
})
