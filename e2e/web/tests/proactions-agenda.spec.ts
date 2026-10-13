import { test, expect } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { callMcpTool } from '../helpers/mcp.js'
import { e2eNow, parisDay } from '../fixtures/clock.js'

/**
 * A proaction is an event of the internal « Maggie » agenda — MAG-326 (extends MAG-103).
 *
 * Scheduled the way Maggie does, through the MCP tool, it is served by the
 * event route at the time asked, in the module agenda and not in Google;
 * deleted through that route, it is gone for Maggie too. Asserted on the API:
 * like a meal, it has its own index (`proactions`), so the `/api/events`
 * collection never lists it, and drawing it on the web agenda is MAG-328.
 */

interface StoredEvent {
  id: string
  summary: string
  startAt: string | null
  endAt: string | null
  agenda: string
}

interface StoredAgenda {
  '@id': string
  name: string
  module: string | null
  googleCalendarId: string | null
}

/** Tomorrow in Paris on the stack's clock (MAG-234): the API refuses a past `scheduled_at` against `E2E_NOW`, not the wall clock. */
function tomorrowInParis(): string {
  return parisDay(new Date(e2eNow().getTime() + 86_400_000))
}

/** A name the current attempt alone writes: CI retries once and nothing reseeds in between. */
function promptOfThisAttempt(): string {
  const { retry } = test.info()

  return `Rappelle de déposer Ael à l'école MAG-326${0 === retry ? '' : ` essai ${retry}`}`
}

function inParis(instant: string | null): string {
  return new Date(String(instant)).toLocaleTimeString('fr-FR', { timeZone: 'Europe/Paris', hour: '2-digit', minute: '2-digit' })
}

test('a proaction scheduled for tomorrow 8:30 sits in the « Maggie » agenda, and deleting it from the agenda cancels it', async ({ api }) => {
  const prompt = promptOfThisAttempt()

  const scheduled = await callMcpTool(api, 'schedule_proaction', { prompt, scheduled_at: `${tomorrowInParis()}T08:30:00` })
  expect(scheduled.error, JSON.stringify(scheduled)).toBeUndefined()
  const id = String((scheduled.proaction as { id: string }).id)

  const read = await api.get(`/api/events/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(read.status()).toBe(200)
  const event = (await read.json()) as StoredEvent
  expect(event.summary).toBe(prompt)
  expect(inParis(event.startAt)).toBe('08:30')
  expect(inParis(event.endAt)).toBe('08:35')

  // Created by this first proaction, so indexed after it: waited for, never read once.
  const agenda = await waitForIndexed<StoredAgenda>(api, '/api/agendas?module=agent', (a) => a['@id'] === event.agenda, { what: 'The « Maggie » agenda' })
  expect(agenda.name).toBe('Maggie')
  expect(agenda.module).toBe('agent')
  expect(agenda.googleCalendarId ?? null).toBeNull()

  const deleted = await api.delete(`/api/events/${id}`)
  expect(deleted.status()).toBe(204)

  expect((await api.get(`/api/events/${id}`)).status(), 'the deleted proaction should leave the agenda').toBe(404)

  const listed = await callMcpTool(api, 'list_proactions', {})
  expect((listed.proactions as { id: string }[]).map((p) => p.id)).not.toContain(id)
})
