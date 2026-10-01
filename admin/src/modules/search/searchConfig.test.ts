import { describe, test, expect } from 'vitest'
import { SEARCH_INDEX_CONFIG, getResultPath, getResultRecordId, type SearchResult } from './searchConfig'

const result = (index: string, id: string): SearchResult => ({ index, id, score: 1, data: {}, highlights: {} })

// react-admin's Hydra data provider takes the IRI as a record's id: `getOne` fetches
// `new URL(id, origin)`. A bare identifier resolves to `/<ulid>` and answers 404.
describe('getResultRecordId', () => {
  test.each([
    ['events', '/api/events/01ABC'],
    ['meals', '/api/meals/01ABC'],
    ['tasks', '/api/tasks/01ABC'],
    ['recipes', '/api/recipes/01ABC'],
    ['products', '/api/products/01ABC'],
    ['agendas', '/api/agendas/01ABC'],
    ['grocery_lists', '/api/grocery_lists/01ABC'],
    ['recurring_grocery_items', '/api/recurring_grocery_items/01ABC'],
    ['notifications', '/api/notifications/01ABC'],
    ['users', '/api/users/01ABC'],
  ])('builds the IRI of a %s hit from its bare identifier', (index, iri) => {
    expect(getResultRecordId(result(index, '01ABC'))).toBe(iri)
  })

  test('covers every configured index', () => {
    for (const index of Object.keys(SEARCH_INDEX_CONFIG)) {
      expect(getResultRecordId(result(index, '01ABC'))).toMatch(/^\/api\/[a-z_]+\/01ABC$/)
    }
  })

  test('leaves an identifier that already is an IRI alone', () => {
    expect(getResultRecordId(result('events', '/api/events/01ABC'))).toBe('/api/events/01ABC')
  })
})

describe('getResultPath', () => {
  test('sends a record to the show page that resolves its IRI', () => {
    expect(getResultPath(result('recipes', '01ABC'))).toBe(`/recipes/${encodeURIComponent('/api/recipes/01ABC')}/show`)
  })

  test('deep-links an event to the calendar with its IRI', () => {
    expect(getResultPath(result('events', '01ABC'))).toBe(`/calendar?eventId=${encodeURIComponent('/api/events/01ABC')}`)
  })

  test('deep-links a meal as a meal, never as an event', () => {
    const path = getResultPath(result('meals', '01ABC'))

    expect(path).toBe(`/calendar?mealId=${encodeURIComponent('/api/meals/01ABC')}`)
    expect(path).not.toContain('eventId')
  })

  test('has no destination for an index it does not know', () => {
    expect(getResultPath(result('mystery', '01ABC'))).toBe('#')
  })
})
