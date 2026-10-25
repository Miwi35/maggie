import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { HomePage } from '../pages/HomePage.js'
import { ROUTES } from '../pages/routes.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'

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
 * the real route, the real keyboard and the API that serves the steps with what they wait for.
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
    await expect(page.getByTestId('cooking-wait')).toContainText("Attend l'eau à bouillir · étape 1")
    await expect(page.getByRole('button', { name: /Suivant/ })).toBeDisabled()

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
    for (const iri of created.reverse()) {
      await api.delete(iri).catch(() => undefined)
    }
    const leftovers = await getCollection<{ '@id': string; name: string }>(api, '/api/utensils?itemsPerPage=200').catch(() => [])
    for (const utensil of leftovers.filter((row) => row.name === utensilName)) {
      await api.delete(utensil['@id']).catch(() => undefined)
    }
  }
})
