import { describe, test, expect } from 'vitest'
import { buildTimeline, clock, countLabel, dayShort, rowSub, rowTime, scheduleLabel, tripLine, untilLabel } from './agendaTimeline'
import type { Trip } from '../../calendar/trips'
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

  test('dayShort reads « mer. 14 oct. »', () => {
    expect(dayShort('2026-10-14')).toBe('mer. 14 oct.')
    expect(dayShort('2026-10-15')).toBe('jeu. 15 oct.')
  })

  test('countLabel says what is done and to come today, what is planned tomorrow, and leaves out a zero', () => {
    const today = (events: AgendaHomeEvent[]) => countLabel(buildTimeline(events, NOW))
    expect(today([done, next])).toBe('1 passé · 1 à venir')
    expect(today([done, running, next, later])).toBe('1 passé · 3 à venir')
    expect(today([done, done])).toBe('2 passés')
    expect(today([next, later])).toBe('2 à venir')
    expect(today([done, allDay])).toBe('1 passé · 1 à venir')
    expect(countLabel(buildTimeline([next], null))).toBe('1 prévu')
    expect(countLabel(buildTimeline([next, later, allDay], null))).toBe('3 prévus')
  })

  test('rowSub joins the place and the agenda, and ends a done event with « fait »', () => {
    expect(rowSub({ ...next, locationShort: 'Cabinet Martin' }, false)).toBe('Cabinet Martin · Perso')
    expect(rowSub({ ...done, locationShort: 'Salle 2' }, true)).toBe('Salle 2 · Perso · fait')
    expect(rowSub(next, false)).toBe('Perso')
    expect(rowSub({ ...done, agendaName: '' }, true)).toBe('fait')
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

const tripOf = (id: string, event: string, departAt: string, extra: Partial<Trip> = {}): Trip => ({
  id,
  event: `/api/events/${event}`,
  mode: 'drive',
  steps: [],
  durationMinutes: 21,
  departAt,
  navigationUrl: 'https://waze.com/x',
  originLabel: 'la maison',
  ...extra,
})

describe('trips on the timeline', () => {
  const out = tripOf('out', 'next', '2026-10-14T14:30:00+02:00')
  const home = tripOf('home', 'next', '2026-10-14T16:10:00+02:00', { leg: 'return', arriveAt: '2026-10-14T16:24:00+02:00' })
  const eve = tripOf('eve', 'later', '2026-10-14T17:30:00+02:00')

  test('trips sit with their event, soonest first', () => {
    const { following } = buildTimeline([next, later], NOW, [home, eve, out])

    expect(following.map((row) => row.trips.map((t) => t.id))).toEqual([['out', 'home'], ['eve']])
  })

  test('the next departure is the first trip still to leave', () => {
    expect(buildTimeline([next, later], NOW, [eve, out]).nextTripId).toBe('out')
  })

  test('once its time passes the next departure is the following trip', () => {
    expect(buildTimeline([next, later], Date.parse('2026-10-14T14:31:00+02:00'), [out, eve]).nextTripId).toBe('eve')
  })

  test('tomorrow has trips and no next departure', () => {
    const timeline = buildTimeline([next], null, [out])

    expect(timeline.nextTripId).toBeNull()
    expect(timeline.following[0].trips).toHaveLength(1)
  })

  test('a trip line reads Partir à then depuis, and Parti à once left', () => {
    expect(tripLine(eve, NOW)).toEqual({ verb: 'Partir à 17:30', from: '· depuis la maison', past: false })
    expect(tripLine(out, Date.parse('2026-10-14T14:31:00+02:00'))).toMatchObject({ verb: 'Parti à 14:30', past: true })
    expect(tripLine({ ...eve, originLabel: '' }, NOW).from).toBe('')
  })

  test('a return keeps Retour · Partir à … · arrivée …', () => {
    expect(tripLine(home, NOW).verb).toBe('Retour · Partir à 16:10 · arrivée 16:24')
  })
})
