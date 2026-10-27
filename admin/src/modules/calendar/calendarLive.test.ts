import { describe, expect, test } from 'vitest'
import { ALL_LIVE_KINDS, touchedKinds } from './calendarLive'

describe('touchedKinds', () => {
  test.each(['events', 'tasks', 'meals', 'proactions', 'trips'])('reads the kind from the IRI of a %s message', (kind) => {
    expect(touchedKinds(JSON.stringify({ '@id': `/api/${kind}/01A`, summary: 'x' }))).toEqual([kind])
  })

  test('a deletion names its row like any other message', () => {
    expect(touchedKinds(JSON.stringify({ '@id': '/api/tasks/01A', deleted: true }))).toEqual(['tasks'])
  })

  test.each([undefined, '', '{}', 'not json', JSON.stringify({ '@id': '/api/agendas/01A' }), JSON.stringify({ '@id': 42 })])(
    'reads everything when the message names no calendar row: %s',
    (data) => {
      expect(touchedKinds(data)).toEqual(ALL_LIVE_KINDS)
    },
  )
})
