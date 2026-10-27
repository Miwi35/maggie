import type { APIRequestContext } from '@playwright/test'
import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed, publishOnHub, userTopic } from '../helpers/mercure.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The trip on screen — MAG-659 (« Partir à l'heure », part 10, extends MAG-100).
 *
 * The day view of the agenda draws the trip Maggie kept under its event, and
 * follows it live; the « Pars maintenant » alert interrupts the open admin with
 * « Y aller ». Google is WireMock (walk 4, drive 14, walk 3), as in
 * agenda-trips.spec.ts.
 *
 * The alert itself is produced by the API's cron, which a browser cannot run:
 * `smoke.sh` (step 11d) fires it and checks the notification; here the delivery is
 * published on the hub, as `maggie-interruption.spec.ts` does, and what is under
 * test is the admin — the toaster, and the navigation link it reads from the real trip.
 */

const HOME = { latitude: 48.11, longitude: -1.68 }
/** 300 m north of home: far enough to walk to. */
const CAR = { latitude: 48.1127, longitude: -1.68 }
const DENTIST = '5 allée du Sourire Fictif, 35000 Rennes'

async function keepTrip(api: APIRequestContext, title: string, start: string, end: string): Promise<{ eventId: string }> {
  const day = parisDay()
  const created = await callMcpTool(api, 'create_event', {
    title,
    start_date: day,
    start_time: start,
    end_date: day,
    end_time: end,
    location: DENTIST,
  })
  expect(created.error, JSON.stringify(created)).toBeUndefined()
  const eventId = (created.event as { id: string }).id

  const now = e2eNow().toISOString()
  expect((await api.put('/api/me/location', { data: { ...HOME, accuracy: 10, capturedAt: now } })).status()).toBe(204)
  expect((await api.put('/api/me/car', { data: { ...CAR, parkedAt: now } })).status()).toBe(204)

  const kept = await callMcpTool(api, 'set_trip', { eventId, mode: 'drive', origin: 'position' })
  expect(kept.error, JSON.stringify(kept)).toBeUndefined()

  return { eventId }
}

test('the day view draws the trip under its event, and follows it without a reload', async ({ page, api }) => {
  const title = `Dentiste, trajet ${test.info().retry}`
  const calendar = new CalendarPage(page)
  const { eventId } = await keepTrip(api, title, '14:00', '15:00')

  await openSubscribed(page, () => calendar.open())
  await calendar.chooseView('Jour')

  const line = calendar.tripLine(title)
  await expect(line).toContainText('Partir à 13:34 · depuis ma position')
  await expect(line).toContainText('🚶 4 · 🚗 14 · 🚶 3')

  // The event moves an hour later and Maggie keeps the trip again: the line moves with it, the page stays.
  const moved = await callMcpTool(api, 'update_event', {
    id: eventId,
    start_date: parisDay(),
    start_time: '15:00',
    end_date: parisDay(),
    end_time: '16:00',
  })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()
  expect((await callMcpTool(api, 'set_trip', { eventId, mode: 'drive', origin: 'position' })).error).toBeUndefined()

  await expect(line).toContainText('Partir à 14:34', { timeout: 10_000 })

  // The event goes, its trip with it: the day keeps no line.
  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
  await expect(calendar.tripLine(title)).toHaveCount(0, { timeout: 10_000 })
})

test('« Pars maintenant » interrupts with « Y aller », which opens the navigation link in a new tab', async ({ interruptedUser }) => {
  const { page, api, session } = interruptedUser
  const title = `Dentiste, départ ${test.info().retry}`
  const { eventId } = await keepTrip(api, title, '14:00', '15:00')

  const listed = await api.get(`/api/trips?date=${parisDay()}`, { headers: { Accept: 'application/ld+json' } })
  const trip = ((await listed.json()) as { member: { '@id': string; event: string }[] }).member.find(
    (candidate) => candidate.event === `/api/events/${eventId}`,
  )
  expect(trip, 'the trip Maggie kept').toBeDefined()

  const id = `e2e-departure-${test.info().retry}`
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await page.evaluate(() => {
    const opened: unknown[][] = []
    ;(window as unknown as { __opened: unknown[][] }).__opened = opened
    window.open = (...args: unknown[]) => {
      opened.push(args)

      return null
    }
  })

  await publishOnHub(page, userTopic(session.user.id, `/api/notifications/${id}`), {
    '@id': `/api/notifications/${id}`,
    id,
    type: 'departure',
    title: `Pars maintenant pour ${title}`,
    body: null,
    relatedEntityIri: trip!['@id'],
    readAt: null,
    createdAt: e2eNow().toISOString(),
  })

  const interruption = page.getByRole('alertdialog').filter({ hasText: `Pars maintenant pour ${title}` })
  await expect(interruption).toBeVisible({ timeout: 5_000 })
  await interruption.getByRole('button', { name: 'Y aller' }).click()

  await expect(interruption).toBeHidden()
  const opened = await page.evaluate(() => (window as unknown as { __opened: unknown[][] }).__opened)
  expect(opened).toHaveLength(1)
  expect(String(opened[0][0])).toMatch(/^https:\/\/waze\.com\/ul\?/)
  expect(opened[0][1]).toBe('_blank')

  expect((await api.delete(`/api/events/${eventId}`)).status()).toBe(204)
})
