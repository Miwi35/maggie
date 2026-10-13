import { test, expect } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * A proaction is an event of the internal « Maggie » agenda — MAG-326 (extends MAG-103).
 *
 * Scheduled the way Maggie does, through the MCP tool, it is served by the
 * agenda's own route at the time asked, in the module agenda and not in Google;
 * deleted through that route, it is gone for Maggie too. Asserted on the API,
 * served from Elasticsearch like the grid: drawing it on the web agenda is MAG-328.
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

/** Tomorrow's date in Paris, whatever zone the runner is in. */
function tomorrowInParis(): string {
  return new Date(Date.now() + 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Europe/Paris' })
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

  const event = await waitForIndexed<StoredEvent>(api, '/api/events?itemsPerPage=200', (e) => e.id === id, { what: 'The proaction' })
  expect(event.summary).toBe(prompt)
  expect(inParis(event.startAt)).toBe('08:30')
  expect(inParis(event.endAt)).toBe('08:35')

  const [agenda] = await getCollection<StoredAgenda>(api, '/api/agendas?module=agent')
  expect(agenda.name).toBe('Maggie')
  expect(agenda.googleCalendarId ?? null).toBeNull()
  expect(event.agenda).toBe(agenda['@id'])

  const deleted = await api.delete(`/api/events/${id}`)
  expect(deleted.status()).toBe(204)

  await expect
    .poll(async () => (await getCollection<StoredEvent>(api, '/api/events?itemsPerPage=200')).some((e) => e.id === id), {
      timeout: 30_000,
      message: 'the deleted proaction should leave the agenda',
    })
    .toBe(false)

  const listed = await callMcpTool(api, 'list_proactions', {})
  expect((listed.proactions as { id: string }[]).map((p) => p.id)).not.toContain(id)
})
