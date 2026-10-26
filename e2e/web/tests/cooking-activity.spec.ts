import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { HomePage } from '../pages/HomePage.js'
import { ROUTES } from '../pages/routes.js'
import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'

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

/** The activity in progress, erased: a journey starts without one and leaves none behind (MAG-615). */
async function clearStatus(api: APIRequestContext): Promise<void> {
  await api.delete('/api/activity-status/cuisine', { headers: { Accept: 'application/json' } }).catch(() => undefined)
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
 * The steps column (MAG-614, extends MAG-99): « Suivant » ticks the step it leaves and nothing holds it back, a
 * touched step is shown without being ticked, and a timer that ends lights the line of its step — no dialog.
 * The Vitest suite owns every state of the column; this is the real route, the real keyboard and the API that
 * serves the steps, the timers and their worker.
 */
test('the steps column ticks the steps as I go, jumps to a touched one without ticking it, and lights a step whose timer ended', async ({
  nutritionUser,
}) => {
  const { page, api } = nutritionUser
  const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = 'Pot-au-feu MAG-614'
  const ingredientName = 'Bœuf MAG-614'
  const created: string[] = []

  const post = async (path: string, body: unknown): Promise<{ '@id': string; id: string }> => {
    const response = await api.post(path, { headers: LD, data: body })
    expect(response.ok(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(true)
    const record = (await response.json()) as { '@id': string; id: string }
    created.push(record['@id'])

    return record
  }

  await clearStatus(api)
  try {
    // Given a recipe of five steps, the third a 1 min boil in the background.
    const beef = await post('/api/ingredients', { name: ingredientName, category: 'grain' })
    const recipe = await post('/api/recipes', {
      name,
      servings: 2,
      ingredients: [{ ingredient: beef['@id'], quantity: 500, unit: 'g' }],
      steps: [
        { text: 'Couper le bœuf', ingredientIds: [beef['@id']] },
        { text: 'Faire revenir la viande' },
        { text: "Mettre l'eau à bouillir", durationMinutes: 1, background: true },
        { text: 'Préparer les légumes' },
        { text: 'Dresser et servir' },
      ],
    })
    await waitForIndexed(api, '/api/recipes?itemsPerPage=200', (row: { name?: string }) => row.name === name)

    await new AdminShell(page).goto(`${ROUTES.recipes}/${encodeURIComponent(recipe['@id'])}/show`)
    await page.getByTestId('recipe-cook').click()
    await page.getByRole('button', { name: 'Continuer' }).click()
    await page.getByRole('button', { name: "C'est parti" }).click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText('Couper le bœuf')

    // The column lists the five steps, the first one current, none ticked, « 0 / 5 faites ».
    const column = page.getByTestId('cooking-steps-column')
    const rows = column.getByTestId('cooking-step-row')
    await expect(rows).toHaveCount(5)
    await expect(rows.nth(0)).toHaveAttribute('data-state', 'current')
    await expect(column.getByTestId('cooking-steps-progress')).toHaveText('0 / 5 faites')

    // When I do « Suivant » twice, two steps are ticked and the progress says « 2 / 5 faites ».
    await page.getByRole('button', { name: /Suivant/ }).click()
    await expect(page.getByTestId('cooking-step-number')).toHaveText('2')
    await page.getByRole('button', { name: /Suivant/ }).click()
    await expect(page.getByTestId('cooking-step-number')).toHaveText('3')
    await expect(rows.nth(0)).toHaveAttribute('data-done', 'true')
    await expect(rows.nth(1)).toHaveAttribute('data-done', 'true')
    await expect(rows.nth(2)).toHaveAttribute('data-state', 'current')
    await expect(column.getByTestId('cooking-steps-progress')).toHaveText('2 / 5 faites')

    // When I touch step 5 in the column, it is shown without being ticked, and « Suivant » is never held back.
    await rows.nth(4).click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText('Dresser et servir')
    await expect(rows.nth(4)).toHaveAttribute('data-done', 'false')
    await expect(column.getByTestId('cooking-steps-progress')).toHaveText('2 / 5 faites')
    await expect(page.getByRole('button', { name: /Suivant|Repas servi/ })).toBeEnabled()

    // The page reloads on the same step with the same two steps ticked.
    await page.reload()
    await expect(page.getByTestId('cooking-step-text')).toHaveText('Dresser et servir')
    await expect(column.getByTestId('cooking-steps-progress')).toHaveText('2 / 5 faites')

    // When I go back to step 3 and « Lancer et continuer », its timer runs on its own line and step 3 is not ticked.
    await rows.nth(2).click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText("Mettre l'eau à bouillir")
    await page.getByRole('button', { name: 'Lancer et continuer' }).click()
    await expect(page.getByTestId('cooking-step-number')).toHaveText('4')
    const lineTimer = rows.nth(2).getByTestId('cooking-step-timer')
    await expect(lineTimer).toHaveAttribute('data-status', 'running')
    await expect(rows.nth(2)).toHaveAttribute('data-done', 'false')

    // When I touch the timer, I am back on its step, and −1 ends it at once.
    await lineTimer.click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText("Mettre l'eau à bouillir")
    await page.getByRole('button', { name: 'Retirer une minute' }).click()

    // Then the line lights, without a dialog; the notification for every device exists.
    await expect(rows.nth(2)).toHaveAttribute('data-lit', 'true', { timeout: 20_000 })
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByTestId('cooking-timer-alert')).toHaveCount(0)
    const notification = await waitForIndexed(
      api,
      '/api/notifications?itemsPerPage=100',
      (row: { type?: string; title?: string }) => row.type === 'cooking_timer' && row.title === "Eau : c'est prêt",
      { what: 'the notification of the rung timer' },
    )
    created.push(String((notification as { '@id'?: string })['@id'] ?? ''))

    // When I touch the lit line, it goes out and the step stays unticked: ticking is « Suivant »'s.
    await rows.nth(2).click()
    await expect(rows.nth(2)).toHaveAttribute('data-lit', 'false')
    await expect(rows.nth(2)).toHaveAttribute('data-done', 'false')
  } finally {
    await clearStatus(api)
    await cancelTimers(api, created)
    for (const iri of created.filter(Boolean).reverse()) {
      await api.delete(iri).catch(() => undefined)
    }
  }
})

/**
 * Quitting and resuming (MAG-615, extends MAG-99): ✕ leaves without a question and the cook is saved in
 * `activity-status`; the recipe's sheet then says « Reprendre · étape 3/4 », and the activity reopens at that step
 * with the first two ticked — in a tab that never saw it, which is what another device is. « Recommencer » goes
 * back to the preparation and the sheet offers « Cuisiner » again.
 */
test('quitting at step 3 keeps the cook: the sheet says « Reprendre », the activity reopens at step 3 with two ticked, « Recommencer » starts over', async ({
  nutritionUser,
}) => {
  const { page, api } = nutritionUser
  const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = 'Blanquette MAG-615'
  const created: string[] = []

  const post = async (path: string, body: unknown): Promise<{ '@id': string; id: string }> => {
    const response = await api.post(path, { headers: LD, data: body })
    expect(response.ok(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(true)
    const record = (await response.json()) as { '@id': string; id: string }
    created.push(record['@id'])

    return record
  }

  await clearStatus(api)
  try {
    // Given a recipe of four steps and nothing in progress.
    const veal = await post('/api/ingredients', { name: 'Veau MAG-615', category: 'grain' })
    const recipe = await post('/api/recipes', {
      name,
      servings: 2,
      ingredients: [{ ingredient: veal['@id'], quantity: 500, unit: 'g' }],
      steps: [{ text: 'Couper le veau' }, { text: 'Faire revenir' }, { text: 'Mouiller et laisser cuire' }, { text: 'Dresser' }],
    })
    await waitForIndexed(api, '/api/recipes?itemsPerPage=200', (row: { name?: string }) => row.name === name)
    const sheet = `${ROUTES.recipes}/${encodeURIComponent(recipe['@id'])}/show`

    // When I launch the recipe and go to step 3…
    await new AdminShell(page).goto(sheet)
    await expect(page.getByTestId('recipe-cook')).toHaveText(/Cuisiner/)
    await page.getByTestId('recipe-cook').click()
    await page.getByRole('button', { name: 'Continuer' }).click()
    await page.getByRole('button', { name: "C'est parti" }).click()
    await page.getByRole('button', { name: /Suivant/ }).click()
    await page.getByRole('button', { name: /Suivant/ }).click()
    await expect(page.getByTestId('cooking-step-text')).toHaveText('Mouiller et laisser cuire')

    // …and quit with the ✕, nothing is asked and I am back on the sheet.
    await page.getByRole('button', { name: 'Quitter Cuisine' }).click()
    await expect(page.getByText("Quitter l'activité ?")).toHaveCount(0)
    await expect(page).toHaveURL(/\/recipes\//)

    // Then the sheet offers « Reprendre » with where I stand.
    await expect(page.getByTestId('recipe-cook')).toHaveText(/Reprendre/)
    await expect(page.getByTestId('recipe-cook-detail')).toHaveText('étape 3/4')

    // When a tab that never saw the activity reopens it, it is at step 3 with the first two ticked.
    const other = await page.context().newPage()
    try {
      await new AdminShell(other).goto(sheet)
      await expect(other.getByTestId('recipe-cook-detail')).toHaveText('étape 3/4')
      await other.getByTestId('recipe-cook').click()
      await expect(other.getByTestId('cooking-step-text')).toHaveText('Mouiller et laisser cuire')
      const rows = other.getByTestId('cooking-steps-column').getByTestId('cooking-step-row')
      await expect(rows.nth(0)).toHaveAttribute('data-done', 'true')
      await expect(rows.nth(1)).toHaveAttribute('data-done', 'true')
      await expect(rows.nth(2)).toHaveAttribute('data-state', 'current')

      // When I press « Recommencer », I am back on the preparation, and the sheet offers « Cuisiner » again.
      await other.getByTestId('cooking-restart').click()
      await expect(other.getByTestId('cooking-preparation')).toBeVisible()
      await other.getByRole('button', { name: 'Quitter Cuisine' }).click()
      await expect(other.getByTestId('recipe-cook')).toHaveText(/Cuisiner/)
    } finally {
      await other.close()
    }
  } finally {
    await clearStatus(api)
    await cancelTimers(api, created)
    for (const iri of created.filter(Boolean).reverse()) {
      await api.delete(iri).catch(() => undefined)
    }
  }
})
