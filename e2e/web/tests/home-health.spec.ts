import { test, expect, e2eNow } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Accueil › carte Santé — MAG-566.
 *
 * Given une séance prévue ce soir, When j'ouvre l'accueil,
 * Then la carte Santé la montre.
 *
 * Nouveau parcours: the card is new, no earlier Accueil journey to extend.
 */

// The account of this journey alone: the card reads the planned session it wrote.
test.use({ journeyAccount: 'empty' })

test('the Accueil Santé card shows the session planned today — MAG-566', async ({ page, api }) => {
  const title = `Course du soir ${test.info().retry}`

  // Given a session planned today (the pinned instant is inside today).
  const created = await api.post('/api/workouts', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: { type: 'run', title, startedAt: e2eNow().toISOString(), durationMinutes: 45, source: 'planned' },
  })
  expect(created.status(), `POST /api/workouts answered ${created.status()}`).toBe(201)

  // When I open the Accueil…
  const home = new HomePage(page)
  await home.open()

  // Then the Santé card shows it.
  await expect(home.card('health').getByTestId('home-health-workout')).toContainText(title)
})
