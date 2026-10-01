import { test, expect, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * Recipes and meals from the admin — the two forms MAG-117 found sending the
 * API something it does not accept: tags as typed text instead of a list, and
 * a meal posted to `/api/agendas` (the collection) instead of to an agenda.
 *
 * Both are asserted on what the API stored, not on what the screen says. The
 * rest of the recipes, menus and groceries journey is MAG-101.
 */

interface RecipeRow {
  name: string
  tags: string[]
}

interface MealRow {
  summary: string
  agenda: unknown
}

interface GroceryListRow {
  items: unknown[]
}

test.describe('Recipes and meals', () => {
  test('a recipe created with comma-separated tags stores them as a list', async ({ page, api }) => {
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/create`)

    await shell.content.getByLabel('Nom').fill('Soupe de potimarron')
    await shell.content.getByLabel(/Tags/).fill('rapide, hiver')
    await shell.content.getByRole('button', { name: 'Enregistrer' }).click()

    const stored = await waitForIndexed<RecipeRow>(api, '/api/recipes', (r) => r.name === 'Soupe de potimarron', {
      what: 'The new recipe',
    })

    expect(stored.tags).toEqual(['rapide', 'hiver'])
  })

  test('a meal planned from the week view lands in one of the user’s agendas', async ({ page, api }) => {
    const shell = new AdminShell(page)
    await shell.goto('/meals')

    // Monday lunch: the seed plans a single dinner, so a lunch cell is empty.
    await shell.content.getByTestId('meal-cell-lunch-0').click()
    await page.getByRole('dialog').getByRole('button', { name: 'Créer' }).click()

    const stored = await waitForIndexed<MealRow>(api, '/api/meals', (m) => m.summary === 'Déjeuner', {
      what: 'The new meal',
    })

    // One of the caller's own agendas, read from the collection rather than
    // named: the seed gives them none called "Repas", so today the week view
    // falls back to the default one — but `MealsWeekView` prefers a "Repas"
    // agenda when it can see one, and MAG-176 is about making the one the API
    // creates visible. Pinning the personal agenda by name would turn this test
    // red the day that lands, for a reason that has nothing to do with MAG-117.
    const mine = (await getCollection<{ '@id': string }>(api, '/api/agendas')).map((agenda) => agenda['@id'])
    expect(mine.length, 'the caller has no agenda at all — did the seed run?').toBeGreaterThan(0)
    expect(mine.some((iri) => JSON.stringify(stored.agenda).includes(iri))).toBe(true)
  })

  test('cancelling a meal takes its ingredients back off the grocery list', async ({ page, api }) => {
    // MAG-116: planning a meal put its ingredients on the list and nothing
    // ever took them off again, so a dinner cancelled on Tuesday was still
    // shopping to do on Saturday.
    const parmesan = seedId('e2e_ingredient_parmesan')
    const shell = new AdminShell(page)
    await shell.goto('/meals')

    // Thursday lunch: the seed plans one dinner and the other journey in this
    // file uses Monday, so this cell is free whatever order they run in.
    const cell = shell.content.getByTestId('meal-cell-lunch-3')
    await cell.click()

    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Recettes').fill('Gratin')
    await page.getByRole('option', { name: 'Gratin de courgettes' }).click()
    await dialog.getByRole('button', { name: 'Créer' }).click()

    await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => JSON.stringify(list.items).includes(parmesan),
      { what: 'The gratin’s parmesan' },
    )

    // The week view fetches when it mounts and once on its own write, both
    // within a blink of the POST and so inside Elasticsearch's refresh — the
    // cell can still look empty, and nothing refetches on its own afterwards.
    // Wait for the meal to be findable, then reload, or the delete button is
    // one that never appears.
    await waitForIndexed<MealRow>(api, '/api/meals', (m) => String(m.summary).includes('Gratin'), {
      what: 'The planned gratin',
    })
    await page.reload()

    // Found by its own name rather than by the cell it was planned in: the
    // grid places a meal on the day its `startAt` string begins with, and a
    // meal stored at midnight in Paris can come back from Elasticsearch in
    // UTC and be drawn a day early. That is its own bug (MAG-166) and not
    // what this journey is about.
    const planned = shell.content
      .locator('[data-testid^="meal-cell-"]')
      .filter({ hasText: 'Gratin de courgettes' })
    await expect(planned).toBeVisible()
    await planned.getByRole('button').first().click()

    const after = await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => !JSON.stringify(list.items).includes(parmesan),
      { what: 'A grocery list without the cancelled meal’s parmesan' },
    )
    // Only the meal's own line goes. Asserted on the seeded lines rather than
    // on a count: the suite runs fully parallel over one shared list, and the
    // chat journey adds to it from the other worker.
    const remaining = JSON.stringify(after.items)
    expect(remaining).toContain(seedId('e2e_ingredient_tomato'))
    expect(remaining).toContain(seedId('e2e_ingredient_pasta'))
    expect(remaining).toContain('Pile LR03')
  })

  test('editing a recipe updates the grocery line of the meals already planned with it', async ({ api }) => {
    // MAG-167: the list followed the meals (MAG-116) but not the recipes
    // behind them — 400 g became 600 g in the recipe and the list kept 400.
    // Driven through the API with an ingredient of its own, so the line is
    // this journey's and nobody else's on the shared list.
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const name = `Boulgour MAG-167 ${Date.now()}`

    const ingredient = await api.post('/api/ingredients', { headers, data: { name, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const ingredientIri = ((await ingredient.json()) as { '@id': string })['@id']

    const recipe = await api.post('/api/recipes', {
      headers,
      data: { name: 'Taboulé MAG-167', servings: 2, ingredients: [{ ingredient: ingredientIri, quantity: 400, unit: 'g' }] },
    })
    expect(recipe.status()).toBe(201)
    const recipeBody = (await recipe.json()) as { '@id': string; id: string }

    const tomorrow = new Date(Date.now() + 24 * 3600 * 1000).toISOString().slice(0, 10)
    const meal = await api.post('/api/meals', {
      headers,
      data: {
        summary: 'Dîner',
        startAt: `${tomorrow}T00:00:00+02:00`,
        endAt: `${tomorrow}T23:59:59+02:00`,
        slot: 'dinner',
        allDay: true,
        agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
        recipes: [recipeBody['@id']],
      },
    })
    expect(meal.status()).toBe(201)

    interface Line {
      label: string
      quantity: number | null
    }
    const lineOf = (list: GroceryListRow): Line | undefined =>
      (list.items as Line[]).find((item) => item.label === name)

    const planned = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => lineOf(list)?.quantity === 400, {
      what: 'The meal’s 400 g of boulgour',
    })
    const lineCount = planned.items.length

    const updated = await api.patch(recipeBody['@id'], {
      headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
      data: { ingredients: [{ ingredient: ingredientIri, quantity: 600, unit: 'g' }] },
    })
    expect(updated.ok()).toBeTruthy()

    const after = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => lineOf(list)?.quantity === 600, {
      what: 'The recipe’s new 600 g of boulgour',
    })

    // Same line, not a second one beside it.
    expect(after.items).toHaveLength(lineCount)
  })
})
