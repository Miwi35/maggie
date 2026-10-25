import { describe, expect, test } from 'vitest'
import { composeAgendaState, shortLocation } from './agendaCardState'
import type { ApiEvent } from './agendaCardState'

const agendas = [{ id: 'a1', name: 'Perso', color: '#4285F4' }, { id: 'a2', name: 'Travail' }]

const timed = (id: string, summary: string, startAt: string, extra: Partial<ApiEvent> = {}): ApiEvent => ({
  id,
  summary,
  allDay: false,
  startAt,
  endAt: startAt,
  agenda: '/api/agendas/a1',
  ...extra,
})

describe('composeAgendaState', () => {
  test('puts whole-day events first, then orders by start, then by name', () => {
    const state = composeAgendaState(
      '2026-10-14',
      Date.parse('2026-10-14T12:00:00Z'),
      [
        timed('late', 'Yoga', '2026-10-14T18:00:00+02:00'),
        timed('b', 'Bilan', '2026-10-14T09:00:00+02:00'),
        timed('a', 'Atelier', '2026-10-14T09:00:00+02:00'),
        { id: 'day', summary: 'Séjour', allDay: true, startDate: '2026-10-13', endDate: '2026-10-16', agenda: '/api/agendas/a1' },
      ],
      agendas,
    )

    expect(state.events.map((e) => e.id)).toEqual(['day', 'a', 'b', 'late'])
  })

  test('gives a whole-day event its last day, the end of the API being excluded', () => {
    const [stay] = composeAgendaState(
      '2026-10-14',
      null,
      [{ id: 'day', summary: 'Séjour', allDay: true, startDate: '2026-10-13', endDate: '2026-10-16', agenda: '/api/agendas/a1' }],
      agendas,
    ).events

    expect(stay).toMatchObject({ allDay: true, startDate: '2026-10-13', endDate: '2026-10-16', lastDay: '2026-10-15' })
  })

  test('takes the name and the colour of the agenda from its IRI, and none when it is unknown', () => {
    const { events } = composeAgendaState(
      '2026-10-14',
      null,
      [
        timed('perso', 'A', '2026-10-14T09:00:00+02:00'),
        timed('work', 'B', '2026-10-14T10:00:00+02:00', { agenda: '/api/agendas/a2' }),
        timed('gone', 'C', '2026-10-14T11:00:00+02:00', { agenda: '/api/agendas/unknown' }),
      ],
      agendas,
    )

    expect(events.map((e) => [e.agendaId, e.agendaName, e.agendaColor])).toEqual([
      ['a1', 'Perso', '#4285F4'],
      ['a2', 'Travail', null],
      ['unknown', '', null],
    ])
  })

  test('drops a cancelled event', () => {
    const { events } = composeAgendaState(
      '2026-10-14',
      null,
      [timed('ok', 'Garde', '2026-10-14T09:00:00+02:00'), timed('off', 'Annulé', '2026-10-14T10:00:00+02:00', { status: 'cancelled' })],
      agendas,
    )

    expect(events.map((e) => e.id)).toEqual(['ok'])
  })

  test('has no « now » on a day that is not today', () => {
    expect(composeAgendaState('2026-10-15', null, [], agendas).now).toBeNull()
    expect(composeAgendaState('2026-10-14', Date.parse('2026-10-14T12:12:00Z'), [], agendas).now).toBe('2026-10-14T12:12:00.000Z')
  })
})

describe('shortLocation', () => {
  test('keeps what is before the first comma', () => {
    expect(shortLocation('Cabinet Martin, 15 rue de la Paix')).toBe('Cabinet Martin')
    expect(shortLocation('Salle 2')).toBe('Salle 2')
  })

  test('is empty when there is nothing to say', () => {
    expect(shortLocation(null)).toBeNull()
    expect(shortLocation('  ')).toBeNull()
    expect(shortLocation(', 15 rue')).toBeNull()
  })
})
