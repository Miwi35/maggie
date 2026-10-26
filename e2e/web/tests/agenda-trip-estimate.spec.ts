import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * The trip to an event, door to door — MAG-653 (« Partir à l'heure », part 4,
 * extends MAG-100).
 *
 * No screen yet: Maggie calls `estimate_trip`, asserted on the tool's answer.
 * Google is WireMock (`.docker/e2e/wiremock/mappings/google-routes.json`,
 * `google-geocoding.json`): every address is the seeded « Dentiste », whose
 * parking (`api/fixtures/e2e/21-places.yaml`) is a 3 min walk away; any other
 * walk is 4 min and a drive 14.
 */

interface TripStep {
  kind: 'walk' | 'drive' | 'transit' | 'bicycle'
  minutes: number
  label?: string
}

interface TripEstimate {
  mode: string
  origin: { kind: string; label: string; latitude: number; longitude: number }
  steps: TripStep[]
  durationMinutes: number
  departAt: string
  navigationUrl: string
  parkingKnown: boolean
}

const HOME = { latitude: 48.11, longitude: -1.68 }
/** 300 m north of home: far enough to walk to. */
const CAR = { latitude: 48.1127, longitude: -1.68 }
const DENTIST_PARKING = '48.119,-1.6672'

test('by car from home, the car 300 m away: walk 4, drive 14 to the parking, walk 3 — leave at 13:34', async ({ api }) => {
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

  const trip = await callMcpTool(api, 'estimate_trip', { eventId, modes: ['drive'], origin: 'position' })
  expect(trip.error, JSON.stringify(trip)).toBeUndefined()

  expect(trip.eventId).toBe(eventId)
  // A position of a moment ago: the phone is not asked again.
  expect(trip.locationRequested).toBe(false)
  const [drive] = trip.estimates as TripEstimate[]
  expect(drive.mode).toBe('drive')
  expect(drive.origin).toMatchObject({ kind: 'position', label: 'ma position', ...HOME })
  expect(drive.steps).toEqual([
    { kind: 'walk', minutes: 4 },
    { kind: 'drive', minutes: 14 },
    { kind: 'walk', minutes: 3 },
  ])
  expect(drive.durationMinutes).toBe(21)
  expect(drive.departAt).toBe(parisTime(day, '13:34:00'))
  expect(drive.parkingKnown).toBe(true)
  expect(drive.navigationUrl.startsWith('https://waze.com/ul?')).toBe(true)
  expect(drive.navigationUrl).toContain(`ll=${DENTIST_PARKING}`)
})

test('an event without a location is refused, naming the event', async ({ api }) => {
  const day = parisDay()
  const created = await callMcpTool(api, 'create_event', {
    title: 'Appel sans lieu',
    start_date: day,
    start_time: '16:00',
    end_date: day,
    end_time: '16:30',
  })
  expect(created.error, JSON.stringify(created)).toBeUndefined()
  const eventId = (created.event as { id: string }).id

  const refused = await callMcpTool(api, 'estimate_trip', { eventId, modes: ['walk'] })

  expect(refused.field).toBe('eventId')
  expect(String(refused.error)).toMatch(/has no location/)
})
