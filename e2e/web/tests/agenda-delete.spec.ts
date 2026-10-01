import { test, expect, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'

/**
 * A deleted event leaves the search index (MAG-164).
 *
 * The delete reached the database and not Elasticsearch, so the global search
 * kept returning the event and `app:elasticsearch:status --check` called the
 * drift and rolled production back. Asserted through the global search, which
 * is served from the index — the database would say the row is gone either way.
 *
 * Deleting through Maggie with a non-canonical id is covered by the API suite
 * (DeleteEventToolTest); this journey holds the user-visible promise.
 */

const SUMMARY = 'Recette suppression MAG-164'

async function searchHits(api: APIRequestContext, query: string): Promise<string[]> {
  const response = await api.get('/api/search', { params: { q: query, types: 'events' } })
  expect(response.ok()).toBeTruthy()

  const body = (await response.json()) as { results: { id: string }[] }

  return body.results.map((hit) => hit.id)
}

async function waitForSearch(api: APIRequestContext, eventId: string, present: boolean): Promise<void> {
  await expect
    .poll(async () => (await searchHits(api, SUMMARY)).includes(eventId), {
      timeout: 30_000,
      message: `The event should ${present ? 'become' : 'stop being'} findable in the global search`,
    })
    .toBe(present)
}

test('a deleted event no longer shows up in the global search', async ({ api }) => {
  const created = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      summary: SUMMARY,
      startAt: '2030-01-15T10:00:00+00:00',
      endAt: '2030-01-15T11:00:00+00:00',
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
    },
  })
  expect(created.status()).toBe(201)
  const eventId = ((await created.json()) as { id: string }).id

  await waitForSearch(api, eventId, true)

  const deleted = await api.delete(`/api/events/${eventId}`)
  expect(deleted.status()).toBe(204)

  await waitForSearch(api, eventId, false)
})
