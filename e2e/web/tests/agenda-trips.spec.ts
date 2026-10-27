import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * Kept trips — MAG-654 (« Partir à l'heure », part 5, extends MAG-100).
 *
 * No screen yet: Maggie keeps the trip with `set_trip`, asserted on
 * `/api/trips`, which the phone and the admin read. Google is WireMock, as in
 * agenda-trip-estimate.spec.ts: a walk to the car 4 min, the drive 14, the
 * walk from the dentist's parking 3.
 */

interface Trip {
  id: string
  event: string
  mode: string
  steps: { kind: string; minutes: number; label?: string }[]
  durationMinutes: number
  departAt: string
  originKind: string
  originLabel: string
  originDecidedBy: string
}

const HOME = { latitude: 48.11, longitude: -1.68 }
/** 300 m north of home: far enough to walk to. */
const CAR = { latitude: 48.1127, longitude: -1.68 }

test('the trip Maggie keeps is listed on its day, and goes with its event', async ({ api }) => {
  const day = parisDay()
  const created = await callMcpTool(api, 'create_event', {
    title: 'Dentiste',
    start_date: day,
    start_time: '14:00',
    end_date: day,
    end_time: '15:00',
    location: '5 allée du Sourire Fictif, 35000 Rennes',
  })
  expect(created.error, JSON.stringify(created)).toBeUndefined()
  const eventId = (created.event as { id: string }).id

  const now = e2eNow().toISOString()
  expect((await api.put('/api/me/location', { data: { ...HOME, accuracy: 10, capturedAt: now } })).status()).toBe(204)
  expect((await api.put('/api/me/car', { data: { ...CAR, parkedAt: now } })).status()).toBe(204)

  const kept = await callMcpTool(api, 'set_trip', { eventId, mode: 'drive', origin: 'position' })
  expect(kept.error, JSON.stringify(kept)).toBeUndefined()

  const listed = await api.get(`/api/trips?date=${day}`, { headers: { Accept: 'application/ld+json' } })
  expect(listed.status()).toBe(200)
  const trips = ((await listed.json()) as { member: Trip[] }).member
  const trip = trips.find((candidate) => candidate.event === `/api/events/${eventId}`)
  expect(trip, JSON.stringify(trips)).toBeDefined()
  expect(trip!.mode).toBe('drive')
  expect(trip!.steps).toEqual([
    { kind: 'walk', minutes: 4 },
    { kind: 'drive', minutes: 14 },
    { kind: 'walk', minutes: 3 },
  ])
  expect(trip!.durationMinutes).toBe(21)
  expect(new Date(trip!.departAt).getTime()).toBe(new Date(parisTime(day, '13:34:00')).getTime())
  expect(trip!.originKind).toBe('position')
  expect(trip!.originLabel).toBe('ma position')
  expect(trip!.originDecidedBy).toBe('maggie')

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)

  const after = await api.get(`/api/trips?date=${day}`, { headers: { Accept: 'application/ld+json' } })
  expect(after.status()).toBe(200)
  const left = ((await after.json()) as { member: Trip[] }).member
  expect(left.map((candidate) => candidate.id)).not.toContain(trip!.id)
})

test('a day that is not a day is refused', async ({ api }) => {
  expect((await api.get('/api/trips?date=demain')).status()).toBe(400)
})
