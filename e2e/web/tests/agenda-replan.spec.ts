import type { APIRequestContext } from '@playwright/test'
import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * Se réadapter — MAG-656 (« Partir à l'heure », part 7, extends MAG-100).
 *
 * No screen yet: when the plan of the day no longer holds, the API wakes Maggie
 * by filing a « Replanifier les trajets » proaction, asserted on `list_proactions`.
 * The recompute itself is hers (the agent). Google is WireMock, as in
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
async function clearPendingReplans(api: APIRequestContext): Promise<void> {
  for (const proaction of (await replanProactions(api)).filter((candidate) => candidate.status === 'pending')) {
    const cancelled = await callMcpTool(api, 'cancel_proaction', { id: proaction.id })
    expect(cancelled.error, JSON.stringify(cancelled)).toBeUndefined()
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
  expect((await api.put('/api/me/location', { data: { ...HOME, accuracy: 10, capturedAt: now } })).status()).toBe(204)
  const kept = await callMcpTool(api, 'set_trip', { eventId, mode: 'walk', origin: 'position' })
  expect(kept.error, JSON.stringify(kept)).toBeUndefined()

  return { eventId, day }
}

test('an event of today that moves wakes Maggie to replan the trips, now', async ({ api }) => {
  const { eventId, day } = await dentistWithTrip(api)
  await clearPendingReplans(api)
  const known = new Set((await replanProactions(api)).map((proaction) => proaction.id))

  const moved = await callMcpTool(api, 'update_event', {
    id: eventId,
    start_date: day,
    start_time: '15:00',
    end_date: day,
    end_time: '16:00',
  })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()

  const replan = await newReplan(api, known)
  expect(replan.prompt).toContain(`Replanifier les trajets du ${day}`)
  // Scheduled for now: the stack's clock, within a minute either way.
  expect(Math.abs(new Date(replan.scheduledAt).getTime() - e2eNow().getTime())).toBeLessThan(60_000)

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})

test('a position 2 km from the origin of the next trip wakes Maggie, once no replan is pending', async ({ api }) => {
  const { eventId } = await dentistWithTrip(api)
  await clearPendingReplans(api)
  const known = new Set((await replanProactions(api)).map((proaction) => proaction.id))

  const now = e2eNow().toISOString()
  expect((await api.put('/api/me/location', { data: { ...TWO_KM_AWAY, accuracy: 10, capturedAt: now } })).status()).toBe(204)

  const replan = await newReplan(api, known)
  expect(replan.prompt).toContain('Replanifier les trajets du')

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})
