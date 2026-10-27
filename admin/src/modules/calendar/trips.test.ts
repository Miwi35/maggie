import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { departureText, fetchNavigationUrl, isTripIri, openNavigation, stepIcon, tripsByEvent, type Trip } from './trips'

const trip = (over: Partial<Trip> = {}): Trip => ({
  id: 't1',
  event: '/api/events/e1',
  mode: 'drive',
  steps: [],
  durationMinutes: 21,
  departAt: new Date(2026, 9, 27, 13, 34).toISOString(),
  navigationUrl: 'https://waze.com/ul?navigate=yes',
  originLabel: 'la maison',
  ...over,
})

describe('departureText', () => {
  test('says when to leave and from where, on 24 hours', () => {
    expect(departureText(trip())).toBe('Partir à 13:34 · depuis la maison')
    expect(departureText(trip({ departAt: new Date(2026, 9, 27, 7, 5).toISOString() }))).toBe(
      'Partir à 07:05 · depuis la maison',
    )
  })

  test('does not crash on a date it cannot read', () => {
    expect(departureText(trip({ departAt: 'nope' }))).toBe('Partir à ? · depuis la maison')
  })
})

test('stepIcon: a pictogram per kind, a pin for the unknown', () => {
  expect(stepIcon('walk')).toBe('🚶')
  expect(stepIcon('drive')).toBe('🚗')
  expect(stepIcon('teleport')).toBe('📍')
})

test('tripsByEvent: indexes by the event IRI', () => {
  const byEvent = tripsByEvent([trip(), trip({ id: 't2', event: '/api/events/e2' })])

  expect(byEvent.get('/api/events/e2')?.id).toBe('t2')
  expect(byEvent.get('/api/events/e3')).toBeUndefined()
})

test.each([
  ['/api/trips/01K0TRIP', true],
  ['/api/trips/', false],
  ['/api/trips/a/b', false],
  ['/api/events/e1', false],
  ['https://evil.example/api/trips/x', false],
  [null, false],
  [undefined, false],
])('isTripIri(%s) is %s', (iri, expected) => {
  expect(isTripIri(iri)).toBe(expected)
})

describe('fetchNavigationUrl', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => vi.unstubAllGlobals())

  test('reads the navigation link of the trip', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ navigationUrl: 'https://waze.com/ul?x=1' }) })

    await expect(fetchNavigationUrl('/api/trips/t1')).resolves.toBe('https://waze.com/ul?x=1')
    expect(fetchMock).toHaveBeenCalledWith('/api/trips/t1', expect.anything())
  })

  test.each([
    ['a missing trip', { ok: false, json: async () => ({}) }],
    ['a script link', { ok: true, json: async () => ({ navigationUrl: 'javascript:alert(1)' }) }],
    ['no link', { ok: true, json: async () => ({}) }],
  ])('is null for %s', async (_name, response) => {
    fetchMock.mockResolvedValue(response)

    await expect(fetchNavigationUrl('/api/trips/t1')).resolves.toBeNull()
  })

  test('is null when the network fails, and asks nothing for a foreign IRI', async () => {
    fetchMock.mockRejectedValue(new Error('offline'))
    await expect(fetchNavigationUrl('/api/trips/t1')).resolves.toBeNull()

    fetchMock.mockClear()
    await expect(fetchNavigationUrl('/api/events/e1')).resolves.toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

test('openNavigation: a new tab with no hold on the admin', () => {
  const open = vi.fn()
  vi.stubGlobal('open', open)

  openNavigation('https://waze.com/ul')

  expect(open).toHaveBeenCalledWith('https://waze.com/ul', '_blank', 'noopener,noreferrer')
  vi.unstubAllGlobals()
})
