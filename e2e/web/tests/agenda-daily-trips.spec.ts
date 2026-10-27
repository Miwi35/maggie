import type { APIRequestContext } from '@playwright/test'
import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * The morning card of the trips — MAG-657 (« Partir à l'heure », part 8, extends MAG-100).
 *
 * No screen yet: Maggie plans the day's trips (estimate, keep by car, two checks before the
 * departure) and plans them again when an event moves. Asserted on `/api/trips` and on
 * `list_proactions`. The model is the fake (`06-planning-trips.yaml`, `06-replan-trips.yaml`)
 * and Google is WireMock, as in agenda-trips.spec.ts: a walk to the car 4 min, the drive 14, the
 * walk from the dentist's parking 3, 5 minutes of margin — an event at 14:00 leaves at 13:34.
 *
 * The planning is fired through `POST /agent/proaction`, as the chat journeys do: the nightly
 * trigger needs a role no journey account has. Whether a real model decides as well is judged
 * on the real model (`task e2e:eval`).
 *
 * An account of its own: the « Dentiste » of these scenarios is the only one the fake reads.
 */

test.use({ journeyAccount: 'standard' })

interface Trip {
  event: string
  mode: string
  departAt: string
}

interface ListedProaction {
  id: string
  prompt: string
  summary: string | null
  scheduledAt: string
  status: string
}

const CHECK_SUMMARY = 'Check départ · Dentiste'
const REPLAN_SUMMARY = 'Replanifier les trajets'
const HOME = { latitude: 48.11, longitude: -1.68 }
/** 300 m north of home: far enough to walk to. */
const CAR = { latitude: 48.1127, longitude: -1.68 }

async function proactionsOf(api: APIRequestContext, summary: string): Promise<ListedProaction[]> {
  const listed = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]

  return listed.filter((proaction) => proaction.summary === summary)
}

async function tripOf(api: APIRequestContext, day: string, eventId: string): Promise<Trip | undefined> {
  const listed = await api.get(`/api/trips?date=${day}`, { headers: { Accept: 'application/ld+json' } })
  expect(listed.status()).toBe(200)

  return ((await listed.json()) as { member: Trip[] }).member.find((trip) => trip.event === `/api/events/${eventId}`)
}

async function run(api: APIRequestContext, message: string): Promise<void> {
  const response = await api.post('/agent/proaction', { data: { message } })
  expect(response.status(), `the proaction failed: ${await response.text()}`).toBe(200)
}

test('the planning keeps the trip by car and posts two checks, and an event that moves replans it', async ({ api }) => {
  const day = parisDay()
  const now = e2eNow().toISOString()
  expect((await api.put('/api/me/location', { data: { ...HOME, accuracy: 10, capturedAt: now } })).status()).toBe(204)
  expect((await api.put('/api/me/car', { data: { ...CAR, parkedAt: now } })).status()).toBe(204)

  const beforeCreation = new Set((await proactionsOf(api, REPLAN_SUMMARY)).map((proaction) => proaction.id))
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

  await run(api, `C'est le début de la journée du ${day}. Planifie les trajets du jour.`)

  const trip = await tripOf(api, day, eventId)
  expect(trip, 'the planning should have kept a trip for the event').toBeDefined()
  expect(trip!.mode).toBe('drive')
  expect(new Date(trip!.departAt).getTime()).toBe(new Date(parisTime(day, '13:34:00')).getTime())

  const checks = await proactionsOf(api, CHECK_SUMMARY)
  expect(checks.map((check) => new Date(check.scheduledAt).getTime()).sort()).toEqual([
    new Date(parisTime(day, '13:04:00')).getTime(),
    new Date(parisTime(day, '13:24:00')).getTime(),
  ])

  // Creating the event already filed a replan (queued): wait for it, then cancel what is pending — while
  // one is pending the API files no other, and the journey waits for the one the move files.
  await expect
    .poll(async () => (await proactionsOf(api, REPLAN_SUMMARY)).some((proaction) => !beforeCreation.has(proaction.id)), {
      message: 'creating the event should have filed a « Replanifier les trajets » proaction',
    })
    .toBe(true)
  for (const pending of (await proactionsOf(api, REPLAN_SUMMARY)).filter((proaction) => proaction.status === 'pending')) {
    const cancelled = await callMcpTool(api, 'cancel_proaction', { id: pending.id })
    expect(cancelled.error, JSON.stringify(cancelled)).toBeUndefined()
  }
  const known = new Set((await proactionsOf(api, REPLAN_SUMMARY)).map((proaction) => proaction.id))
  const moved = await callMcpTool(api, 'update_event', {
    id: eventId,
    start_date: day,
    start_time: '15:00',
    end_date: day,
    end_time: '16:00',
  })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()

  let replan: ListedProaction | undefined
  await expect
    .poll(async () => {
      replan = (await proactionsOf(api, REPLAN_SUMMARY)).find((proaction) => !known.has(proaction.id))

      return replan !== undefined
    }, { message: 'moving the event should have filed a « Replanifier les trajets » proaction' })
    .toBe(true)

  await run(api, replan!.prompt)

  const replanned = await tripOf(api, day, eventId)
  expect(new Date(replanned!.departAt).getTime()).toBe(new Date(parisTime(day, '14:34:00')).getTime())

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})
