import { request } from '@playwright/test'
import { SEED_USER_EMAIL, signIn } from './fixtures/session.js'
import { parisDay } from './fixtures/clock.js'

/**
 * Files one throw-away meal before any journey starts, so the signed-in user's
 * « Repas » module agenda exists.
 *
 * The seed has no meal for this user, and the agenda is created by whichever
 * request files the first one: two journeys planning a meal at the same instant
 * both see no agenda, both insert it, and the second answers 500 on
 * `uniq_agenda_user_module`. Which journeys start together depends on the shard,
 * so the failure moved around.
 */
export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost'
  const session = await signIn(baseURL, SEED_USER_EMAIL)
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const api = await request.newContext({
    baseURL,
    extraHTTPHeaders: { Authorization: `Bearer ${session.token}` },
  })

  try {
    const recipe = await api.post('/api/recipes', { headers, data: { name: 'Warm-up agenda des repas', servings: 1 } })
    if (201 !== recipe.status()) {
      throw new Error(`global setup: POST /api/recipes answered ${recipe.status()}: ${await recipe.text()}`)
    }
    const planted = (await recipe.json()) as { id: string; '@id': string }

    const meal = await api.post('/api/meals', {
      headers,
      data: { summary: 'Warm-up', date: parisDay(), slot: 'lunch', recipes: [planted['@id']] },
    })
    if (201 !== meal.status()) {
      throw new Error(`global setup: POST /api/meals answered ${meal.status()}: ${await meal.text()}`)
    }
    const filed = (await meal.json()) as { id: string }

    await api.delete(`/api/meals/${filed.id}`)
    await api.delete(`/api/recipes/${planted.id}`)
  } finally {
    await api.dispose()
  }
}
