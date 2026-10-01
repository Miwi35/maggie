import { test, expect } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'

/**
 * Connecting a Google calendar twice leaves one agenda (MAG-148), extending the
 * agenda journey MAG-100.
 *
 * Production ended up with two "Concerts" agendas on the same Google calendar,
 * each syncing on its own and duplicating every event. The guard that was meant
 * to prevent it lived in the admin — and it read a field the agenda collection,
 * served from Elasticsearch, did not carry. So this asserts through the
 * collection the clients actually read, not through the database.
 */

const GOOGLE_CALENDAR_ID = 'e2e@maggie.local'

interface AgendaMember {
  id: string
  name: string
  googleCalendarId?: string
}

async function connect(
  api: import('@playwright/test').APIRequestContext,
): Promise<{ status: number; id: string; name: string }> {
  const response = await api.post('/api/calendar/google/import', {
    headers: { 'Content-Type': 'application/json' },
    data: { googleCalendarId: GOOGLE_CALENDAR_ID },
  })
  const body = (await response.json()) as { id: string; name: string }

  return { status: response.status(), id: body.id, name: body.name }
}

// The seed knows nothing about Google, so the first connection is what creates
// the agenda — unless a retry or a local rerun already did. Starting from a
// clean slate keeps the 201 below meaningful.
test.beforeEach(async ({ api }) => {
  const agendas = await getCollection<AgendaMember>(api, '/api/agendas')

  for (const agenda of agendas.filter((candidate) => candidate.googleCalendarId === GOOGLE_CALENDAR_ID)) {
    await api.delete(`/api/agendas/${agenda.id}`)
  }
})

test('connecting the same Google calendar twice leaves a single agenda', async ({ api }) => {
  const first = await connect(api)
  expect(first.status).toBe(201)
  // The primary calendar is called "Défaut", not after the account holder.
  expect(first.name).toBe('Défaut')

  await waitForIndexed<AgendaMember>(
    api,
    '/api/agendas',
    (agenda) => agenda.id === first.id,
    { what: 'The imported agenda' },
  )

  const second = await connect(api)
  expect(second.status, 'Reconnecting is a success, not a conflict').toBe(200)
  expect(second.id, 'The second connection reuses the first agenda').toBe(first.id)

  const agendas = await getCollection<AgendaMember>(api, '/api/agendas')
  const connected = agendas.filter((agenda) => agenda.googleCalendarId === GOOGLE_CALENDAR_ID)

  expect(
    connected.map((agenda) => agenda.id),
    'The agenda list shows the Google calendar once',
  ).toEqual([first.id])
})
