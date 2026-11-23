import { describe, expect, test } from 'vitest'
import type { AgendaHomeEvent } from '../../home/cards/agendaTimeline'
import type { Trip } from '../../calendar/trips'
import { tomorrowSay, tripQuestion } from './tripQuestions'

const event = (id: string, summary: string, start: string, location: string | null = 'Rennes'): AgendaHomeEvent =>
  ({ id, summary, location, locationShort: location, allDay: false, startAt: `2026-11-24T${start}:00+01:00`, endAt: `2026-11-24T${start}:00+01:00`, timeZone: 'Europe/Paris', status: 'confirmed' }) as AgendaHomeEvent

const trip = (eventId: string, patch: Partial<Trip> = {}): Trip => ({ id: `trip-${eventId}`, event: `/api/events/${eventId}`, mode: 'drive', durationMinutes: 20, departAt: '2026-11-24T08:00:00+01:00', ...patch }) as Trip

describe('tripQuestion', () => {
  const events = [event('E1', 'Dentiste', '09:00')]

  test('asks the origin, then the mode, then the parking when driving', () => {
    expect(tripQuestion(events, [trip('E1')], new Set())?.kind).toBe('origin')
    expect(tripQuestion(events, [trip('E1')], new Set(['E1:origin']))?.kind).toBe('mode')
    expect(tripQuestion(events, [trip('E1')], new Set(['E1:origin', 'E1:mode']))?.kind).toBe('parking')
    expect(tripQuestion(events, [trip('E1')], new Set(['E1:origin', 'E1:mode', 'E1:parking']))).toBeNull()
  })

  test('no parking question when not driving', () => {
    expect(tripQuestion(events, [trip('E1', { mode: 'walk' })], new Set(['E1:origin', 'E1:mode']))).toBeNull()
  })

  test('an origin the owner decided is not asked again', () => {
    expect(tripQuestion(events, [trip('E1', { originDecidedBy: 'user' })], new Set())?.kind).toBe('mode')
  })

  test('no question without a located event, nor without a trip once the origin is known', () => {
    expect(tripQuestion([event('E2', 'Sieste', '14:00', null)], [], new Set())).toBeNull()
    expect(tripQuestion(events, [], new Set(['E1:origin']))).toBeNull()
  })

  test('the message sent to Maggie names the event', () => {
    const question = tripQuestion(events, [trip('E1')], new Set(['E1:origin']))

    expect(question?.choices[0].message).toBe('Bilan du soir, trajet de demain · événement E1 « Dentiste » : j’y vais en voiture.')
    expect(tripQuestion(events, [trip('E1')], new Set(['E1:origin', 'E1:mode']))?.choices.at(-1)?.message).toBeNull()
  })
})

describe('tomorrowSay', () => {
  test('says the first two appointments, then the question', () => {
    const events = [event('E1', 'Dentiste', '09:00'), event('E2', 'Dîner', '20:30')]

    expect(tomorrowSay(events, null)).toBe('Dentiste à 9 h, puis dîner à 20 h 30.')
    expect(tomorrowSay([], null)).toBe('Rien à l’agenda demain.')
  })
})
