import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { HomePage } from '../pages/HomePage.js'
import { ROUTES } from '../pages/routes.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' }

// The journeys share `nutritionUser` and the timer journeys both run an « Eau »: one at a time.
test.describe.configure({ mode: 'serial' })

/**
 * The timers the journey left running, paused or rung on its own recipes: stopped, so the next journey starts
 * clean (MAG-582). Another journey's timers are left alone.
 */
async function cancelTimers(api: APIRequestContext, recipeIris: string[]): Promise<void> {
  const response = await api.get('/api/cooking/timers', { headers: { Accept: 'application/json' } }).catch(() => null)
  if (!response?.ok()) return
  const { member = [] } = (await response.json()) as { member?: { id: string; recipe: string | null }[] }
  for (const timer of member.filter((row) => row.recipe !== null && recipeIris.includes(row.recipe))) {
    await api.patch(`/api/cooking/timers/${timer.id}`, { headers: JSON_HEADERS, data: { action: 'cancel' } }).catch(() => undefined)
  }
}

/**
 * The Cuisine activity, launched from the Accueil (MAG-580, extends MAG-99): the full-screen frame, the
 * preparation of tonight's meal with its two recipes in order, and the way out.
 *
 * The account of `65-nutrition-home.yaml`: tonight's dinner has « Pâtes au pesto » (two steps) then
 * « Salade de saison » (one step). What each screen holds is the Vitest suite's; this is the request and the
 * real route, the real browser's full screen and the way back to where the cook came from.
 */
test('« ▶ Lancer » on tonight’s meal opens the Cuisine activity, which lists both recipes, and Échap goes back to the Accueil', async ({
  nutritionUser,
}) => {
  const { page } = nutritionUser
  const home = new HomePage(page)

  // Given tonight's dinner with two recipes, when I tap « Lancer » on the Accueil…
  await home.open()
  await home.card('nutrition').getByTestId('home-launch-nutrition').click()

  // …the activity opens over the whole window, on the preparation of the meal.
  await expect(page).toHaveURL(/\/activites\/cuisine\?meal=/)
  await expect(page.getByTestId('activity-shell')).toBeVisible()
  const preparation = page.getByTestId('cooking-preparation')
  await expect(preparation).toBeVisible()
  await expect(preparation.getByTestId('cooking-queue-item')).toHaveCount(2)
  // A meal keeps no order among its recipes (the API serves them as the database returns them): both are
  // there, and the first one served is the current one.
  const queue = preparation.getByTestId('cooking-queue-item')
  await expect(queue.filter({ hasText: 'Pâtes au pesto' })).toHaveCount(1)
  await expect(queue.filter({ hasText: 'Salade de saison' })).toHaveCount(1)
  await expect(queue.nth(0)).toHaveAttribute('data-current', 'true')
  await expect(queue.nth(1)).not.toHaveAttribute('data-current', 'true')

  // When I press Échap with nothing running, I leave without being asked, back on the Accueil.
  const left = page.waitForURL((url) => !url.pathname.startsWith('/activites/cuisine'))
  await page.keyboard.press('Escape')
  // A real browser leaves full screen itself on Échap, which is what ends the activity; a synthetic key does
  // not move the browser's own full screen, so it is given the same signal.
  await page.evaluate(() => (document.fullscreenElement ? document.exitFullscreen() : undefined)).catch(() => undefined)
  await left

  await expect(page.getByText("Quitter l'activité ?")).toHaveCount(0)
  await home.expectReady()
  await expect(home.card('nutrition')).toBeVisible()
})

/**
 * The recipe, one step at a time (MAG-581, extends MAG-99): the steps spec's reference example, launched from
 * the recipe's « Cuisiner », followed with the arrow keys. The Vitest suite owns every screen and key; this is
 * the real route, the real keyboard and the API that serves the steps with what they wait for. Since the
 * timers (MAG-582), → on a step in the background starts its timer: the water and the pasta run meanwhile.
 */
test('the reference recipe is cooked step by step: → three times lands on a step that waits for step 1, « C’est prêt » lifts it', async ({
  nutritionUser,
}) => {
  const { page, api } = nutritionUser
  const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = 'Pâtes aux légumes MAG-581'
  const ingredientNames = ['Pâtes MAG-581', 'Légumes MAG-581']
  const utensilName = 'Casserole MAG-581'
  const created: string[] = []

  const post = async (path: string, body: unknown): Promise<{ '@id': string; id: string }> => {
    const response = await api.post(path, { headers: LD, data: body })
    expect(response.ok(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(true)
    const record = (await response.json()) as { '@id': string; id: string }
    created.push(record['@id'])

    return record
  }

  try {
    // Given the reference recipe: water 10 min in the background, cut, pasta 12 min in the background (waits 1),
    // vegetables 8 min, drain (waits 3 and 4).
    const pasta = await post('/api/ingredients', { name: ingredientNames[0], category: 'grain' })
    const vegetables = await post('/api/ingredients', { name: ingredientNames[1], category: 'grain' })
    const recipe = await post('/api/recipes', {
      name,
      servings: 2,
      ingredients: [
        { ingredient: pasta['@id'], quantity: 200, unit: 'g' },
        { ingredient: vegetables['@id'], quantity: 300, unit: 'g' },
      ],
      utensils: [{ name: utensilName }],
      steps: [
        { text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, utensils: [{ name: utensilName }] },
        { text: 'Couper les légumes', ingredientIds: [vegetables['@id']] },
        { text: 'Cuire les pâtes', durationMinutes: 12, background: true, ingredientIds: [pasta['@id']], waitsFor: [1] },
        { text: 'Faire revenir les légumes', durationMinutes: 8, ingredientIds: [vegetables['@id']] },
        { text: 'Égoutter, mélanger', waitsFor: [3, 4] },
      ],
    })
    await waitForIndexed(api, '/api/recipes?itemsPerPage=200', (row: { name?: string }) => row.name === name)

    // When I press « Cuisiner » on its sheet, answer « Pour combien ? » and start…
    await new AdminShell(page).goto(`${ROUTES.recipes}/${encodeURIComponent(recipe['@id'])}/show`)
    await page.getByTestId('recipe-cook').click()
    await expect(page).toHaveURL(/\/activites\/cuisine\?recipe=/)
    await page.getByRole('button', { name: 'Continuer' }).click()
    await expect(page.getByTestId('cooking-preparation')).toBeVisible()
    await page.getByRole('button', { name: "C'est parti" }).click()

    // …the first step is alone on screen, with its utensil and the step after it.
    await expect(page.getByTestId('cooking-step-text')).toHaveText("Mettre l'eau à bouillir")
    await expect(page.getByTestId('cooking-step-utensil')).toHaveText(utensilName)
    await expect(page.getByTestId('cooking-upnext')).toHaveText('Ensuite · Couper les légumes')

    // Then → twice reaches step 3, which waits for the water, and Suivant is held back.
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('cooking-step-number')).toHaveText('2')
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('cooking-step-number')).toHaveText('3')
    await expect(page.getByTestId('cooking-wait')).toContainText("Attend l'eau à bouillir · étape 1 · encore")
    await expect(page.getByRole('button', { name: 'Lancer et continuer' })).toBeDisabled()

    // → does nothing while it waits; « C'est prêt » lifts it and → goes on to step 4.
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('cooking-step-number')).toHaveText('3')
    await page.getByTestId('cooking-ready').click()
    await expect(page.getByTestId('cooking-wait')).toHaveCount(0)
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('cooking-step-number')).toHaveText('4')

    // And ← comes back to step 3, then the page reloads on the same step.
    await page.keyboard.press('ArrowLeft')
    await expect(page.getByTestId('cooking-step-number')).toHaveText('3')
    await page.reload()
    await expect(page.getByTestId('cooking-step-number')).toHaveText('3')
  } finally {
    await cancelTimers(api, created)
    for (const iri of created.reverse()) {
      await api.delete(iri).catch(() => undefined)
    }
    const leftovers = await getCollection<{ '@id': string; name: string }>(api, '/api/utensils?itemsPerPage=200').catch(() => [])
    for (const utensil of leftovers.filter((row) => row.name === utensilName)) {
      await api.delete(utensil['@id']).catch(() => undefined)
    }
  }
})

/**
 * The timers (MAG-582, extends MAG-99): a step in the background starts its own and the cook goes on; at the
 * end the API rings it — the alert over the step, the notification for every device — and « OK » dismisses it.
 * The Vitest suite owns the ring, the column, the waits and the quit; this is the real API, its worker and its
 * notification.
 */
test('« Lancer et continuer » starts « Eau »; −1 ends it at once, the alert « Eau : c’est prêt » shows with its notification, and OK takes it away', async ({
  nutritionUser,
}) => {
  const { page, api } = nutritionUser
  const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = 'Pâtes minute MAG-582'
  const ingredientName = 'Pâtes MAG-582'
  const created: string[] = []

  const post = async (path: string, body: unknown): Promise<{ '@id': string; id: string }> => {
    const response = await api.post(path, { headers: LD, data: body })
    expect(response.ok(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(true)
    const record = (await response.json()) as { '@id': string; id: string }
    created.push(record['@id'])

    return record
  }

  try {
    // Given a recipe whose step 1 « Mettre l'eau à bouillir » lasts 1 min in the background…
    const pasta = await post('/api/ingredients', { name: ingredientName, category: 'grain' })
    const recipe = await post('/api/recipes', {
      name,
      servings: 2,
      ingredients: [{ ingredient: pasta['@id'], quantity: 200, unit: 'g' }],
      steps: [
        { text: "Mettre l'eau à bouillir", durationMinutes: 1, background: true },
        { text: 'Cuire les pâtes', ingredientIds: [pasta['@id']], waitsFor: [1] },
      ],
    })
    await waitForIndexed(api, '/api/recipes?itemsPerPage=200', (row: { name?: string }) => row.name === name)

    await new AdminShell(page).goto(`${ROUTES.recipes}/${encodeURIComponent(recipe['@id'])}/show`)
    await page.getByTestId('recipe-cook').click()
    await page.getByRole('button', { name: 'Continuer' }).click()
    await page.getByRole('button', { name: "C'est parti" }).click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText("Mettre l'eau à bouillir")
    await expect(page.getByTestId('cooking-main-timer-label')).toHaveText('Eau')

    // When « Lancer et continuer »: the timer « Eau » runs and the next step is on screen.
    await page.getByRole('button', { name: 'Lancer et continuer' }).click()
    await expect(page.getByTestId('cooking-step-number')).toHaveText('2')
    const water = page.getByTestId('cooking-timers').getByTestId('cooking-timer').filter({ hasText: 'Eau' })
    await expect(water).toHaveAttribute('data-status', 'running')
    await expect(page.getByTestId('cooking-wait')).toContainText('encore')

    // When −1 on it, back on its step (one minute left, none after): it rings at once.
    await page.getByRole('button', { name: /Précédent/ }).click()
    await expect(page.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'running')
    await page.getByRole('button', { name: 'Retirer une minute' }).click()

    // Then the alert « Eau : c'est prêt » shows over the step, and the notification for every device exists.
    const alert = page.getByTestId('cooking-timer-alert')
    await expect(alert).toBeVisible({ timeout: 20_000 })
    await expect(alert).toContainText("Eau : c'est prêt")
    await expect(alert).toContainText("minuteur de l'étape 1")
    const notification = await waitForIndexed(
      api,
      '/api/notifications?itemsPerPage=100',
      (row: { type?: string; title?: string }) => row.type === 'cooking_timer' && row.title === "Eau : c'est prêt",
      { what: 'the notification of the rung timer' },
    )
    created.push(String((notification as { '@id'?: string })['@id'] ?? ''))

    // When OK, the alert leaves.
    await alert.getByRole('button', { name: 'OK' }).click()
    await expect(alert).toHaveCount(0)
  } finally {
    await cancelTimers(api, created)
    for (const iri of created.filter(Boolean).reverse()) {
      await api.delete(iri).catch(() => undefined)
    }
  }
})
