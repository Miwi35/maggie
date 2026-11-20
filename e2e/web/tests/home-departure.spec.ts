import type { APIRequestContext } from '@playwright/test'
import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed } from '../helpers/mercure.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The next departure on the Accueil — MAG-867 (« Partir à l'heure », part 13, extends MAG-100).
 *
 * The Agenda card of the day shows the next departure as a tile above its event and the other trips as
 * one short line; tomorrow has lines only. Google is WireMock (walk 4, drive 14, walk 3).
 */

test.use({ journeyAccount: 'standard' })

const HOME = { latitude: 48.11, longitude: -1.68 }
/** 300 m north of home: far enough to walk to. */
const CAR = { latitude: 48.1127, longitude: -1.68 }
const PLACE = '5 allée du Sourire Fictif, 35000 Rennes'

async function keepTrip(api: APIRequestContext, title: string, start: string, end: string): Promise<{ eventId: string }> {
  const day = parisDay()
  const created = await callMcpTool(api, 'create_event', {
    title,
    start_date: day,
    start_time: start,
    end_date: day,
    end_time: end,
    location: PLACE,
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

test('the next departure is a tile above its event, the others a line, and tomorrow has no tile', async ({ page, api }) => {
  const parisHour = Number(new Intl.DateTimeFormat('en-GB', { hour: '2-digit', hourCycle: 'h23', timeZone: 'Europe/Paris' }).format(e2eNow()))
  test.skip(parisHour >= 13, 'Without the pinned clock the 13:34 departure is already behind us')

  const home = new HomePage(page)
  const dentist = await keepTrip(api, 'Dentiste', '14:00', '15:00')
  const physio = await keepTrip(api, 'Kiné', '17:00', '18:00')

  await openSubscribed(page, () => home.open())

  const tile = home.departureTile
  await expect(tile).toContainText('Partir à 13:34')
  await expect(tile).toContainText('depuis ma position · 21 min porte à porte')
  for (const minutes of ['4 min', '14 min', '3 min']) await expect(tile).toContainText(minutes)
  await expect(tile.getByRole('button', { name: 'Y aller' })).toBeVisible()
  const tileBox = await tile.boundingBox()
  const dentistBox = await home.agendaRow('Dentiste').boundingBox()
  expect(tileBox!.y).toBeLessThan(dentistBox!.y)

  const line = home.tripLine('Kiné')
  await expect(line).toContainText('Partir à 16:34 · depuis ma position')
  await expect(line).not.toContainText('min porte à porte')
  await expect(line.getByText('14 min')).toHaveCount(0)

  // « Dentiste » moves an hour later and its trip is kept again: the tile follows, the page stays.
  const moved = await callMcpTool(api, 'update_event', {
    id: dentist.eventId,
    start_date: parisDay(),
    start_time: '15:00',
    end_date: parisDay(),
    end_time: '16:00',
  })
  expect(moved.error, JSON.stringify(moved)).toBeUndefined()
  expect((await callMcpTool(api, 'set_trip', { eventId: dentist.eventId, mode: 'drive', origin: 'position' })).error).toBeUndefined()
  await expect(tile).toContainText('Partir à 14:34', { timeout: 10_000 })

  await home.day('Demain').click()
  await expect(home.departureTile).toHaveCount(0)

  expect((await api.delete(`/api/events/${dentist.eventId}`)).status()).toBe(204)
  expect((await api.delete(`/api/events/${physio.eventId}`)).status()).toBe(204)
})
