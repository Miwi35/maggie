import type { APIRequestContext } from '@playwright/test'
import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * Se réadapter — MAG-656 (« Partir à l'heure », part 7, extends MAG-100).
 *
 * No screen yet: when the plan of the day no longer holds, the API wakes Maggie
 * by filing a « Replanifier les trajets » proaction, asserted on `list_proactions`; a departure that only slides is kept silently, asserted on `/api/trips`.
 * The API recomputes the trips itself (MAG-729) and wakes her only for a real choice. Google is WireMock, as in
 * agenda-trips.spec.ts.
 */

interface ListedProaction {
  id: string
  prompt: string
  summary: string | null
  scheduledAt: string
  status: string
}

const SUMMARY = 'Replanifier les trajets'
const HOME = { latitude: 48.11, longitude: -1.68 }
/** About 2 km north of home: well beyond the 300 m that still counts as « there ». */
const TWO_KM_AWAY = { latitude: 48.128, longitude: -1.68 }

async function replanProactions(api: APIRequestContext): Promise<ListedProaction[]> {
  const listed = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]

  return listed.filter((proaction) => proaction.summary === SUMMARY)
}

/** Nothing pending: the dedup would swallow the replan the journey waits for. */
async function clearPendingReplans(api: APIRequestContext, eventId: string): Promise<void> {
  // The event's creation files its own replan asynchronously: wait for the one that names this event (a replan
  // left by an earlier test or attempt must not satisfy the wait), so it cannot land after the snapshot.
  await expect
    .poll(async () => (await replanProactions(api)).some((proaction) => proaction.prompt.includes(eventId)), {
      message: 'the replan due to the creation should have been filed',
    })
    .toBe(true)

  for (const proaction of (await replanProactions(api)).filter((candidate) => candidate.status === 'pending')) {
    const cancelled = await callMcpTool(api, 'cancel_proaction', { id: proaction.id })
    // The worker may have run it between the list and the cancel: then it is no longer pending.
    if (cancelled.error !== undefined && !JSON.stringify(cancelled).includes('not found')) {
      expect(cancelled.error, JSON.stringify(cancelled)).toBeUndefined()
    }
  }
}

/** The replan proaction filed since `known`, whatever it became meanwhile (the worker may already run it). */
async function newReplan(api: APIRequestContext, known: Set<string>): Promise<ListedProaction> {
  let found: ListedProaction | undefined

  await expect
    .poll(async () => {
      found = (await replanProactions(api)).find((proaction) => !known.has(proaction.id))

      return found !== undefined
    }, { message: 'a « Replanifier les trajets » proaction should have been filed' })
    .toBe(true)

  return found!
}

async function dentistWithTrip(api: APIRequestContext): Promise<{ eventId: string; day: string }> {
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
  await expect
    .poll(async () => (await api.put('/api/me/location', { data: { ...HOME, accuracy: 10, capturedAt: now } })).status(), {
      message: 'the position should be accepted',
    })
    .toBe(204)
  const kept = await callMcpTool(api, 'set_trip', { eventId, mode: 'walk', origin: 'position' })
  expect(kept.error, JSON.stringify(kept)).toBeUndefined()

  return { eventId, day }
}

async function tripDepartAt(api: APIRequestContext, day: string, eventId: string): Promise<number | undefined> {
  const listed = await api.get(`/api/trips?date=${day}`, { headers: { Accept: 'application/ld+json' } })
  expect(listed.status()).toBe(200)
  const trip = ((await listed.json()) as { member: { event: string; departAt: string }[] }).member.find(
    (candidate) => candidate.event === `/api/events/${eventId}`,
  )

  return trip === undefined ? undefined : new Date(trip.departAt).getTime()
}

async function moveOneHourLater(api: APIRequestContext, eventId: string, day: string, from: number): Promise<void> {
  const moved = await callMcpTool(api, 'update_event', {
    id: eventId,
    start_date: day,
    start_time: '15:00',
    end_date: day,
    end_time: '16:00',
  })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()

  await expect
    .poll(async () => await tripDepartAt(api, day, eventId), { message: 'the departure should slide with the event, without the model' })
    .toBe(from + 3_600_000)
}

test('an event of today that moves slides the departure, and wakes nobody', async ({ replanUser }) => {
  const { api } = replanUser
  const { eventId, day } = await dentistWithTrip(api)
  await clearPendingReplans(api, eventId)
  const known = new Set((await replanProactions(api)).map((proaction) => proaction.id))
  const before = (await tripDepartAt(api, day, eventId))!

  await moveOneHourLater(api, eventId, day, before)

  expect((await replanProactions(api)).filter((proaction) => !known.has(proaction.id))).toEqual([])
  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})

test('a position 2 km from the origin of the next trip is recomputed by the API, and wakes nobody', async ({ replanUser }) => {
  const { api } = replanUser
  const { eventId, day } = await dentistWithTrip(api)
  await clearPendingReplans(api, eventId)
  const known = new Set((await replanProactions(api)).map((proaction) => proaction.id))
  const before = (await tripDepartAt(api, day, eventId))!

  const now = e2eNow().toISOString()
  expect((await api.put('/api/me/location', { data: { ...TWO_KM_AWAY, accuracy: 10, capturedAt: now } })).status()).toBe(204)
  // The move after it is the barrier: the worker handles the messages in order, so once its effect shows
  // the position's replan has run too.
  await moveOneHourLater(api, eventId, day, before)

  expect((await replanProactions(api)).filter((proaction) => !known.has(proaction.id))).toEqual([])
  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})

test('an event of today moved to a place Google cannot find wakes Maggie, who is told which', async ({ replanUser }) => {
  const { api } = replanUser
  const { eventId, day } = await dentistWithTrip(api)
  await clearPendingReplans(api, eventId)
  const known = new Set((await replanProactions(api)).map((proaction) => proaction.id))

  const moved = await callMcpTool(api, 'update_event', { id: eventId, location: 'Lieu Introuvable' })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()

  const replan = await newReplan(api, known)
  expect(replan.prompt).toContain(`Replanifier les trajets du ${day}`)
  expect(replan.prompt).toContain('Dentiste')
  expect(replan.prompt).toContain(eventId)
  // Scheduled for now: the stack's clock, within a minute either way.
  expect(Math.abs(new Date(replan.scheduledAt).getTime() - e2eNow().getTime())).toBeLessThan(60_000)

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})
