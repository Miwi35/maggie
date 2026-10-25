import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext, Page } from '@playwright/test'
import { getCollection, plannedMealId, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed, userTopic } from '../helpers/mercure.js'
import { dayOfThisWeek } from '../helpers/week.js'
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
  id: string
  summary: string
  agenda: unknown
  /** The day, `YYYY-MM-DD` — a meal has no time (MAG-251). */
  date: string
  slot: string
}

/**
 * A name no other attempt of this journey uses: a retry plans a recipe of its
 * own, and the one the previous attempt left behind must not be found instead.
 */
function recipeName(retry: number): string {
  return `Blanquette MAG-251${0 === retry ? '' : ` essai ${retry}`}`
}

interface GroceryListRow {
  items: unknown[]
}

/**
 * A meal puts nothing on the list by itself (MAG-300): the owner chooses which
 * of its ingredients to buy, which is what the « Courses du repas » step of the
 * form does through this endpoint. The journeys below that watch a meal's lines
 * follow the meal choose them first.
 */
async function chooseIngredients(api: APIRequestContext, mealId: string, ingredientIds: string[]): Promise<void> {
  const chosen = await api.post(`/api/meals/${mealId}/grocery_items`, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    data: { ingredients: ingredientIds.map((ingredientId) => ({ ingredientId })) },
  })
  expect(chosen.ok(), `POST grocery_items answered ${chosen.status()}: ${await chosen.text()}`).toBeTruthy()
}

// One account per test, with the seed's recipes, ingredients and agendas, and no journey beside it:
// the grocery list and the planning each test reads were written by that test or by the profile.
test.use({ journeyAccount: 'standard' })

test.describe('Recipes and meals', () => {
  test('a recipe created with comma-separated tags stores them as a list and shows in the list at once', async ({ page, api }) => {
    const name = `Soupe MAG-117 ${Date.now()}`
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/create`)

    // MAG-601: the creation opens the empty sheet with the « Nouvelle recette » popin on top.
    const dialog = page.getByRole('dialog', { name: 'Nouvelle recette' })
    await dialog.getByLabel('Nom').fill(name)
    await dialog.getByLabel(/Tags/).fill('rapide, hiver')
    await dialog.getByRole('button', { name: 'Créer' }).click()

    // MAG-117 recette: the owner saved a recipe and found an empty list. The
    // sheet of the new recipe opens, and the list already has the recipe — no
    // reload, no waiting for the index.
    await expect(page.getByText('Recette créée')).toBeVisible()
    await expect(page).toHaveURL(/#\/recipes\/.+\/show/)
    await expect(shell.content.getByRole('heading', { name })).toBeVisible()
    await shell.goto(ROUTES.recipes)
    // The list is oldest first, ten a page, and the other journeys leave their
    // recipes behind: a new one may be on the second page, so show them all.
    await shell.content.getByRole('combobox', { name: /Lignes par page/ }).click()
    await page.getByRole('option', { name: '50', exact: true }).click()
    const row = shell.content.getByRole('row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await expect(row.getByText('rapide', { exact: true })).toBeVisible()
    await expect(row.getByText('hiver', { exact: true })).toBeVisible()

    const stored = (await getCollection<RecipeRow>(api, '/api/recipes')).find((r) => r.name === name)
    expect(stored?.tags).toEqual(['rapide', 'hiver'])
  })

  /**
   * MAG-266: a delete reached the database and left its document in Elasticsearch
   * — for an ingredient, in `products`, an index its command never named — so a
   * list served from the index kept showing the row and `status --check` rolled
   * the deploy back. Asserted on a list loaded afresh, which is the index's word.
   */
  for (const kind of [
    { label: 'recipe', index: 'recipes', route: ROUTES.recipes, path: '/api/recipes', data: (name: string) => ({ name, servings: 2 }), confirm: (page: Page) => page.getByRole('dialog').getByRole('button', { name: 'Confirmer' }).click() },
    { label: 'ingredient', index: 'products', route: ROUTES.ingredients, path: '/api/ingredients', data: (name: string) => ({ name, category: 'grain' }), confirm: async () => {} },
  ]) {
    test(`a deleted ${kind.label} leaves the list, and stays gone after a reload`, async ({ page, api }) => {
      const name = `Suppression MAG-266 ${kind.label} ${Date.now()}`
      const created = await api.post(kind.path, {
        headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
        data: kind.data(name),
      })
      expect(created.status(), `POST ${kind.path} answered ${created.status()}`).toBe(201)
      const id = ((await created.json()) as { id: string }).id
      await waitForIndexed<{ name: string }>(api, kind.path, (row) => row.name === name, {
        what: `The ${kind.label} this journey deletes`,
      })

      // The index the document lives in, asked through the global search: an
      // ingredient's list is the database's, its document is a product's.
      const inIndex = async (): Promise<boolean> => {
        const found = await api.get('/api/search', { params: { q: name, types: kind.index } })
        expect(found.ok()).toBeTruthy()

        return ((await found.json()) as { results: { id: string }[] }).results.some((hit) => hit.id === id)
      }
      await expect
        .poll(inIndex, { timeout: 30_000, message: `The ${kind.label} never reached the ${kind.index} index` })
        .toBe(true)

      // Newest first, so the row is on the first page whatever the seed holds.
      const shell = new AdminShell(page)
      const list = `${kind.route}?sort=id&order=DESC&perPage=50`
      await shell.goto(list)
      const row = shell.content.getByRole('row').filter({ hasText: name })
      await expect(row).toBeVisible()

      await row.getByRole('button', { name: 'Supprimer' }).click()
      // A recipe asks first, since its planned meals go with it (MAG-289).
      await kind.confirm(page)
      await expect(row).toHaveCount(0)

      // The screen forgets the row at once and sends the delete after the undo
      // window: wait for the API, which is served from the index, to agree.
      await expect
        .poll(async () => (await getCollection<{ name: string }>(api, kind.path)).some((r) => r.name === name), {
          timeout: 30_000,
          message: `The deleted ${kind.label} is still served by the API`,
        })
        .toBe(false)

      await expect
        .poll(inIndex, { timeout: 30_000, message: `The deleted ${kind.label} is still in the ${kind.index} index` })
        .toBe(false)

      await shell.goto(list)
      await expect(shell.content.getByRole('row').first()).toBeVisible()
      await expect(shell.content.getByRole('row').filter({ hasText: name })).toHaveCount(0)
    })
  }

  test('a meal planned from the week view with a recipe saved a moment ago shows in its cell at once', async ({
    page,
    api,
  }) => {
    // No ingredient on purpose: planning it must not touch the grocery list the
    // other journeys of this file assert on.
    const recipeName = `Velouté MAG-117 ${Date.now()}`
    const created = await api.post('/api/recipes', {
      headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
      data: { name: recipeName, servings: 2 },
    })
    expect(created.status()).toBe(201)

    const shell = new AdminShell(page)
    await shell.goto('/meals')

    // Wednesday lunch: a cell with a meal opens no dialog, so each journey of
    // this file owns its own — Tuesday is MAG-251's, Thursday MAG-116's, and
    // the seed's single meal is a dinner.
    const cell = shell.content.getByTestId('meal-cell-lunch-2')
    await cell.click()
    const dialog = page.getByRole('dialog')

    // A meal is a recipe on a day: with none picked, nothing is created.
    await dialog.getByRole('button', { name: 'Créer' }).click()
    await expect(page.getByText('Choisissez au moins une recette pour ce repas')).toBeVisible()
    await expect(dialog).toBeVisible()

    // The recipe list is the API's, and so is the planned meal: both come back
    // from Elasticsearch, which must already hold what was written a moment ago.
    await dialog.getByLabel('Menu').fill('Velouté MAG-117')
    await page.getByRole('option', { name: recipeName }).click()
    await dialog.getByRole('button', { name: 'Créer' }).click()

    await expect(page.getByText('Repas créé')).toBeVisible()
    // Found by its name, not by the cell it was planned in: a meal stored at
    // midnight in Paris can be drawn a day early (MAG-166), which is not what
    // this journey is about.
    await expect(shell.content.locator('[data-testid^="meal-cell-"]').filter({ hasText: recipeName })).toBeVisible()

    const stored = (await getCollection<MealRow>(api, '/api/meals')).find((m) => String(m.summary).includes(recipeName))
    expect(stored, 'the meal is not in the API collection right after the screen showed it').toBeDefined()

    // MAG-324: the week view names no agenda, and the meal lands in the module
    // agenda « Repas » — created on this first need, found by its attribute and
    // not by its name. The seeded user has none called « Repas », and the
    // default agenda (synced with Google) received nothing.
    type AgendaRow = { '@id': string; module?: string | null; isDefault?: boolean }
    await waitForIndexed<AgendaRow>(api, '/api/agendas?module=cookbook', (agenda) => 'cookbook' === agenda.module, {
      what: 'The module agenda the first meal creates',
    })
    const storedAgendas = [
      ...(await getCollection<AgendaRow>(api, '/api/agendas')),
      ...(await getCollection<AgendaRow>(api, '/api/agendas?module=cookbook')),
    ]
    const moduleAgendas = storedAgendas.filter((agenda) => 'cookbook' === agenda.module)
    expect(moduleAgendas, 'exactly one module agenda holds the meals').toHaveLength(1)
    expect(moduleAgendas[0].isDefault).toBeFalsy()
    expect(JSON.stringify(stored?.agenda)).toContain(moduleAgendas[0]['@id'])

    const defaults = storedAgendas.filter((agenda) => agenda.isDefault)
    expect(defaults.some((agenda) => JSON.stringify(stored?.agenda).includes(agenda['@id']))).toBe(false)
    await expect(shell.content.locator('[data-testid^="meal-cell-"]').filter({ hasText: recipeName })).toHaveCount(1)
  })

  /**
   * MAG-251: the owner added a meal in the week view and it came up on the day
   * before. The view labelled its cells with `toISOString()` — the UTC day —
   * so the cell he clicked offered him Monday for Tuesday, and then wrote it
   * there.
   *
   * Tuesday lunch, on purpose: the other two journeys in this file plan Monday
   * and Thursday, and the seed's only meal is a dinner. The recipe is made here
   * and carries no ingredient, so nothing reaches the grocery list the rest of
   * the suite shares.
   *
   * Three checks, because the bug had three faces: the day the cell offers, the
   * day the API stores, and the cell the meal is drawn in once the page is
   * loaded afresh from Elasticsearch.
   */
  test('a lunch planned on Tuesday is on Tuesday, and still is after a reload', async ({ page, api }) => {
    const name = recipeName(test.info().retry)
    const tuesday = dayOfThisWeek(1)

    const created = await api.post('/api/recipes', {
      headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
      data: { name, servings: 2 },
    })
    expect(created.status(), `POST /api/recipes answered ${created.status()}`).toBe(201)
    await waitForIndexed<RecipeRow>(api, '/api/recipes', (r) => r.name === name, {
      what: 'The recipe this journey plans',
    })

    const shell = new AdminShell(page)
    await shell.goto('/meals')

    await shell.content.getByTestId('meal-cell-lunch-1').click()

    const dialog = page.getByRole('dialog')
    // The day the cell offers. This is the bug: it used to offer the day before.
    await expect(dialog.getByLabel('Date')).toHaveValue(tuesday)
    await dialog.getByLabel('Menu').fill('Blanquette')
    await page.getByRole('option', { name }).click()
    const mealId = await plannedMealId(page, () => dialog.getByRole('button', { name: 'Créer' }).click())

    // The day the API stored, as a day.
    const stored = await waitForIndexed<MealRow>(api, '/api/meals', (m) => m.id === mealId, {
      what: 'The lunch planned on Tuesday',
    })
    expect(stored.date).toBe(tuesday)
    expect(stored.slot).toBe('lunch')

    // And the cell it is drawn in, on a page loaded afresh.
    await page.reload()
    await expect(shell.content.getByTestId('meal-cell-lunch-1')).toContainText(name)
    await expect(shell.content.getByTestId('meal-cell-lunch-0')).not.toContainText(name)
    await expect(shell.content.getByTestId('meal-cell-lunch-2')).not.toContainText(name)
  })

  test('cancelling a meal takes its ingredients back off the grocery list', async ({ page, api, journeyUser }) => {
    // MAG-116: planning a meal put its ingredients on the list and nothing
    // ever took them off again, so a dinner cancelled on Tuesday was still
    // shopping to do on Saturday. Since MAG-300 the owner chooses what goes on
    // the list; the lines they chose still leave with the meal.
    const parmesan = journeyUser.ref('e2e_ingredient_parmesan')
    const thursday = dayOfThisWeek(3)

    // A previous attempt that failed half-way leaves its gratin in the cell,
    // maybe with the parmesan it chose: the list would never lose it (MAG-575).
    const leftovers = await getCollection<MealRow & { '@id': string }>(
      api,
      `/api/meals?date[after]=${thursday}&date[before]=${thursday}`,
    )
    for (const leftover of leftovers.filter((m) => 'lunch' === m.slot && String(m.summary).includes('Gratin de courgettes'))) {
      const removed = await api.delete(leftover['@id'])
      expect(removed.ok() || 404 === removed.status(), `DELETE ${leftover['@id']} answered ${removed.status()}`).toBeTruthy()
    }

    const shell = new AdminShell(page)
    await shell.goto('/meals')

    // Thursday lunch: the seed plans one dinner and the other journey in this
    // file uses Tuesday, so this cell is free whatever order they run in.
    const cell = shell.content.getByTestId('meal-cell-lunch-3')
    await cell.click()

    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Menu').fill('Gratin')
    await page.getByRole('option', { name: 'Gratin de courgettes' }).click()
    // The meal is the one this form creates, read off its answer: since MAG-378
    // a meal is named after its recipe, and the user is shared with journeys
    // planning gratins of their own (`meals-module-calendar.spec.ts`).
    const posted = page.waitForResponse(
      (response) => new URL(response.url()).pathname.endsWith('/api/meals') && 'POST' === response.request().method(),
    )
    await dialog.getByRole('button', { name: 'Créer' }).click()
    const answer = await posted
    expect(answer.status(), `POST /api/meals answered ${answer.status()}`).toBe(201)
    const gratin = (await answer.json()) as { '@id': string }
    await dialog.getByRole('button', { name: 'Plus tard' }).click()

    await chooseIngredients(api, gratin['@id'].split('/').pop() ?? '', [parmesan])
    await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => JSON.stringify(list.items).includes(parmesan),
      { what: 'The gratin’s parmesan' },
    )

    // The week view fetches when it mounts and once on its own write, both
    // within a blink of the POST and so inside Elasticsearch's refresh — the
    // cell can still look empty, and nothing refetches on its own afterwards.
    // Reload, or the delete button is one that never appears (the meal is
    // findable by now).
    await page.reload()

    // In the cell it was planned in. It used not to be: the grid placed a meal
    // on the day its `startAt` string began with, and a meal stored at midnight
    // in Paris comes back from Elasticsearch in UTC and was drawn a day early
    // (MAG-166). The day is a day now (MAG-251), so the cell is addressable.
    const planned = cell.getByTestId(`meal-${gratin['@id']}`)
    await expect(planned).toBeVisible()
    await planned.getByRole('button', { name: /^Supprimer le repas / }).click()

    const after = await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => !JSON.stringify(list.items).includes(parmesan),
      { what: 'A grocery list without the cancelled meal’s parmesan' },
    )
    // Only the meal's own line goes: the other seeded lines stay.
    const remaining = JSON.stringify(after.items)
    expect(remaining).toContain(journeyUser.ref('e2e_ingredient_tomato'))
    expect(remaining).toContain(journeyUser.ref('e2e_ingredient_pasta'))
    expect(remaining).toContain('Pile LR03')
  })

  /**
   * MAG-289: deleting a recipe left its planned meals behind, drawn in the week
   * with a recipe that no longer exists and still shopping for it. The
   * confirmation now counts them, and the delete takes them — and their share
   * of the list — with it.
   *
   * Friday and Saturday lunch, and an ingredient of its own: the other journeys
   * of this file plan Monday to Thursday, and the grocery list is shared, so
   * the line asserted on is this journey's and nobody else's.
   */
  test('deleting a recipe deletes its planned meals and takes their ingredients off the list — MAG-289', async ({
    page,
    api,
    journeyUser,
  }) => {
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const suffix = `${Date.now()}`
    const ingredientName = `Semoule MAG-289 ${suffix}`
    const name = `Couscous MAG-289 ${suffix}`

    const ingredient = await api.post('/api/ingredients', { headers, data: { name: ingredientName, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const { '@id': ingredientIri, id: ingredientId } = (await ingredient.json()) as { '@id': string; id: string }

    const recipe = await api.post('/api/recipes', {
      headers,
      data: { name, servings: 2, ingredients: [{ ingredient: ingredientIri, quantity: 300, unit: 'g' }] },
    })
    expect(recipe.status()).toBe(201)
    const recipeIri = ((await recipe.json()) as { '@id': string })['@id']

    for (const date of [dayOfThisWeek(4), dayOfThisWeek(5)]) {
      const meal = await api.post('/api/meals', {
        headers,
        data: {
          date,
          slot: 'lunch',
          agenda: `/api/agendas/${journeyUser.ref('e2e_agenda_personal')}`,
          recipes: [recipeIri],
        },
      })
      expect(meal.status(), `POST /api/meals answered ${meal.status()}: ${await meal.text()}`).toBe(201)
      await chooseIngredients(api, ((await meal.json()) as { id: string }).id, [ingredientId])
    }

    const onList = (list: GroceryListRow): boolean => JSON.stringify(list.items).includes(ingredientName)
    await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', onList, { what: 'The couscous’s semolina' })
    await waitForIndexed<RecipeRow>(api, '/api/recipes', (r) => r.name === name, { what: 'The recipe this journey deletes' })

    const mealsOf = async (): Promise<MealRow[]> =>
      (await getCollection<MealRow>(api, '/api/meals?itemsPerPage=200')).filter((m) => String(m.summary).includes(name))
    await expect.poll(async () => (await mealsOf()).length, { timeout: 30_000, message: 'The two meals were never indexed' }).toBe(2)

    const shell = new AdminShell(page)
    await shell.goto('/meals')
    const week = shell.content.locator('[data-testid^="meal-cell-"]').filter({ hasText: name })
    await expect(week).toHaveCount(2)

    await shell.goto(`${ROUTES.recipes}?sort=id&order=DESC&perPage=50`)
    const row = shell.content.getByRole('row').filter({ hasText: name })
    await row.getByRole('button', { name: 'Supprimer' }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText(`Supprimer « ${name} » et ses 2 repas planifiés ?`)
    await expect(dialog).toContainText('Vous aviez prévu de cuisiner cette recette')
    await expect(dialog.getByRole('listitem')).toHaveCount(2)
    await expect(dialog).toContainText('Ces repas seront supprimés avec elle')
    await dialog.getByRole('button', { name: 'Confirmer' }).click()

    await expect(row).toHaveCount(0)
    await expect.poll(async () => (await mealsOf()).length, { timeout: 30_000, message: 'The recipe’s meals are still planned' }).toBe(0)
    await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => !onList(list), {
      what: 'A grocery list without the deleted recipe’s semolina',
    })

    await shell.goto('/meals')
    await expect(shell.content.getByTestId('meal-cell-lunch-4')).toBeVisible()
    await expect(shell.content.locator('[data-testid^="meal-cell-"]').filter({ hasText: name })).toHaveCount(0)
  })

  test('editing a recipe leaves the grocery line of the meals already planned with it alone — MAG-300', async ({ api, journeyUser }) => {
    // MAG-167 made the list follow the recipes behind the meals: 400 g became
    // 600 g in the recipe and the list went with it. MAG-300 reverses it: once
    // the owner has chosen what to buy, the recipe no longer touches the list.
    // Driven through the API with an ingredient of its own, so the line is
    // this journey's own.
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const name = `Boulgour MAG-167 ${Date.now()}`

    const ingredient = await api.post('/api/ingredients', { headers, data: { name, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const { '@id': ingredientIri, id: ingredientId } = (await ingredient.json()) as { '@id': string; id: string }

    const recipe = await api.post('/api/recipes', {
      headers,
      data: { name: 'Taboulé MAG-167', servings: 2, ingredients: [{ ingredient: ingredientIri, quantity: 400, unit: 'g' }] },
    })
    expect(recipe.status()).toBe(201)
    const recipeBody = (await recipe.json()) as { '@id': string; id: string }

    // The day after the seed's anchor, not after the wall clock (MAG-234): the two
    // part ways after midnight, and a week-view assertion cannot tell why.
    const tomorrow = seedDate(1)
    const meal = await api.post('/api/meals', {
      headers,
      data: {
        // A day and a slot, no instant (MAG-251).
        date: tomorrow,
        slot: 'dinner',
        agenda: `/api/agendas/${journeyUser.ref('e2e_agenda_personal')}`,
        recipes: [recipeBody['@id']],
      },
    })
    expect(meal.status()).toBe(201)

    interface Line {
      label: string
      quantity: number | null
      unit: string | null
    }
    const linesOf = (list: GroceryListRow): Line[] => (list.items as Line[]).filter((item) => item.label === name)

    // Planned, the meal has put nothing on the list.
    const mealId = ((await meal.json()) as { id: string }).id
    await waitForIndexed<MealRow>(api, '/api/meals', (m) => m.id === mealId, { what: 'The planned meal' })
    const beforeChoice = (await getCollection<GroceryListRow>(api, '/api/grocery_lists'))[0]
    expect(linesOf(beforeChoice ?? { items: [] }), 'planning the meal put its ingredient on the list').toHaveLength(0)

    // Chosen, it is on the list as one line.
    await chooseIngredients(api, mealId, [ingredientId])
    const chosen = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => linesOf(list).length > 0, {
      what: 'The chosen boulgour',
    })
    expect(linesOf(chosen), 'the choice made a single line').toHaveLength(1)

    // Saved the way the edit form does: the recipe as the GET returns it — its
    // ingredient an embedded object, not an IRI — with the quantity changed.
    // The first version of this journey sent an IRI and passed while the form's
    // own save answered 500 (refused at recette).
    const read = await api.get(recipeBody['@id'], { headers: { Accept: 'application/ld+json' } })
    expect(read.ok()).toBeTruthy()
    const record = (await read.json()) as { ingredients: Array<{ quantity: number }> }
    record.ingredients[0].quantity = 600

    const updated = await api.patch(recipeBody['@id'], {
      headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
      data: record,
    })
    expect(updated.ok(), `PATCH answered ${updated.status()}: ${await updated.text()}`).toBeTruthy()

    // The recipe is indexed with its new quantity; the list, read after it, is
    // the one the edit had every chance to change.
    await waitForIndexed<{ name: string }>(
      api,
      '/api/recipes',
      (r) => r.name === 'Taboulé MAG-167' && JSON.stringify(r).includes('"quantity":600'),
      { what: 'The recipe with its new quantity' },
    )
    const after = (await getCollection<GroceryListRow>(api, '/api/grocery_lists'))[0]
    const summary = (lines: Line[]) => lines.map(({ label, quantity, unit }) => ({ label, quantity, unit }))
    expect(summary(linesOf(after ?? { items: [] })), 'the recipe edit changed the chosen line').toEqual(
      summary(linesOf(chosen)),
    )
  })

  test('a recipe created with two utensils lists them when read back, and the catalogue holds them — MAG-489', async ({ api }) => {
    // Given a recipe written with two utensils by name, neither in the catalogue yet
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const stamp = Date.now()
    const utensils = [`Casserole MAG-489 ${stamp}`, `Poêle MAG-489 ${stamp}`]
    const created = await api.post('/api/recipes', { headers, data: { name: `Omelette MAG-489 ${stamp}`, utensils } })
    expect(created.status(), `POST answered ${created.status()}: ${await created.text()}`).toBe(201)
    const { '@id': iri, name } = (await created.json()) as { '@id': string; name: string }

    interface Utensil {
      id: string
      name: string
    }
    const namesOf = (list: Utensil[] | undefined) => (list ?? []).map((utensil) => utensil.name)

    // When it is read back — the item and the list are served from Elasticsearch,
    // whose document does not hold the utensils
    const listed = await waitForIndexed<{ name: string; utensils?: Utensil[] }>(api, '/api/recipes?pagination=false', (r) => r.name === name, {
      what: 'The recipe with its utensils',
    })
    expect(namesOf(listed.utensils)).toEqual(utensils)

    const read = await api.get(iri, { headers: { Accept: 'application/ld+json' } })
    expect(read.ok()).toBeTruthy()
    const record = (await read.json()) as { utensils: Utensil[] }

    // Then it lists them, and the catalogue holds them, once each
    expect(namesOf(record.utensils)).toEqual(utensils)
    for (const utensil of record.utensils) {
      await waitForIndexed<Utensil>(api, '/api/utensils?pagination=false', (u) => u.id === utensil.id, { what: `The utensil ${utensil.name}` })
    }
    const catalogue = await getCollection<Utensil>(api, '/api/utensils?pagination=false')
    expect(catalogue.filter((u) => utensils.includes(u.name)).map((u) => u.name).sort()).toEqual(utensils)
  })

  test('the reference recipe created with its five steps reads them back in order, with their durations and what they wait for — MAG-490', async ({
    api,
  }) => {
    // Given the reference example of the steps spec, created through the API
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const stamp = Date.now()
    const ingredientIds: Record<string, string> = {}
    for (const [key, category] of [['Pâtes', 'grain'], ['Courgette', 'produce'], ['Poivron', 'produce']] as const) {
      const created = await api.post('/api/ingredients', { headers, data: { name: `${key} MAG-490 ${stamp}`, category } })
      expect(created.status()).toBe(201)
      ingredientIds[key] = ((await created.json()) as { id: string }).id
    }
    const ingredient = (key: string, quantity: number, unit: string) => ({ ingredient: `/api/ingredients/${ingredientIds[key]}`, quantity, unit })
    const steps = [
      { text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, utensils: [`Casserole MAG-490 ${stamp}`] },
      { text: 'Couper les légumes', ingredientIds: [ingredientIds.Courgette, ingredientIds.Poivron] },
      { text: 'Cuire les pâtes', durationMinutes: 12, background: true, ingredientIds: [ingredientIds['Pâtes']], waitsFor: [1] },
      { text: 'Faire revenir les légumes', durationMinutes: 8, ingredientIds: [ingredientIds.Courgette, ingredientIds.Poivron] },
      { text: 'Égoutter, mélanger', waitsFor: [3, 4] },
    ]
    const created = await api.post('/api/recipes', {
      headers,
      data: {
        name: `Pâtes aux légumes MAG-490 ${stamp}`,
        ingredients: [ingredient('Pâtes', 200, 'g'), ingredient('Courgette', 1, 'piece'), ingredient('Poivron', 1, 'piece')],
        steps,
      },
    })
    expect(created.status(), `POST answered ${created.status()}: ${await created.text()}`).toBe(201)
    const { '@id': iri, name } = (await created.json()) as { '@id': string; name: string }

    interface Step {
      position: number
      text: string
      durationMinutes: number | null
      background: boolean
      waitsFor: number[]
    }

    // When it is read back — served from Elasticsearch, whose hydrated recipe
    // reads its steps from the database
    await waitForIndexed<{ name: string }>(api, '/api/recipes?pagination=false', (r) => r.name === name, { what: 'The recipe with its steps' })
    const read = await api.get(iri, { headers: { Accept: 'application/ld+json' } })
    expect(read.ok()).toBeTruthy()
    const record = (await read.json()) as { steps: Step[] }

    // Then the five steps come back in order, with their durations and their dependencies
    expect(record.steps.map(({ position, text, durationMinutes, background, waitsFor }) => ({ position, text, durationMinutes, background, waitsFor }))).toEqual([
      { position: 1, text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, waitsFor: [] },
      { position: 2, text: 'Couper les légumes', durationMinutes: null, background: false, waitsFor: [] },
      { position: 3, text: 'Cuire les pâtes', durationMinutes: 12, background: true, waitsFor: [1] },
      { position: 4, text: 'Faire revenir les légumes', durationMinutes: 8, background: false, waitsFor: [] },
      { position: 5, text: 'Égoutter, mélanger', durationMinutes: null, background: false, waitsFor: [3, 4] },
    ])
  })

  test('the reference recipe is entered step by step in the popins, with two utensils, and read back on the sheet — MAG-491', async ({ page, api }) => {
    // Given a recipe with its ingredients, and a utensil already in the catalogue
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const stamp = Date.now()
    const names = { pasta: `Pâtes MAG-491 ${stamp}`, courgette: `Courgette MAG-491 ${stamp}` }
    const ingredientIris: Record<string, string> = {}
    for (const [key, category] of [['pasta', 'grain'], ['courgette', 'produce']] as const) {
      const created = await api.post('/api/ingredients', { headers, data: { name: names[key], category } })
      expect(created.status()).toBe(201)
      ingredientIris[key] = ((await created.json()) as { '@id': string })['@id']
    }
    const existing = `Casserole MAG-491 ${stamp}`
    const fresh = `Thermomix MAG-491 ${stamp}`
    const catalogued = await api.post('/api/utensils', { headers, data: { name: existing } })
    expect(catalogued.status()).toBe(201)
    const created = await api.post('/api/recipes', {
      headers,
      data: {
        name: `Pâtes aux légumes MAG-491 ${stamp}`,
        servings: 2,
        ingredients: [
          { ingredient: ingredientIris.pasta, quantity: 200, unit: 'g' },
          { ingredient: ingredientIris.courgette, quantity: 1, unit: 'piece' },
        ],
      },
    })
    expect(created.status(), `POST answered ${created.status()}: ${await created.text()}`).toBe(201)
    const { '@id': iri, name } = (await created.json()) as { '@id': string; name: string }

    // When I enter the five steps of the reference example, and two utensils one of which is new,
    // each in its popin on the recipe's sheet (MAG-601)
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
    await expect(shell.content.getByRole('heading', { name })).toBeVisible()

    const dialog = page.getByRole('dialog')
    const save = async () => {
      await dialog.getByRole('button', { name: 'Enregistrer' }).click()
      await expect(dialog).toBeHidden()
    }
    const chip = async (field: string, option: string, create = false) => {
      const input = dialog.getByRole('combobox', { name: field })
      if (create) await input.fill(option)
      else await input.click()
      await page.getByRole('option', { name: create ? `Ajouter « ${option} »` : option, exact: true }).click()
    }

    await shell.content.getByRole('button', { name: 'Modifier le matériel' }).click()
    await chip('Matériel', existing)
    await chip('Matériel', fresh, true)
    await save()

    const steps = [
      { text: "Mettre l'eau à bouillir", duration: '10', background: true, utensil: existing },
      { text: 'Couper les légumes', ingredient: names.courgette },
      { text: 'Cuire les pâtes', duration: '12', background: true, waits: ['Étape 1'], ingredient: names.pasta },
      { text: 'Faire revenir les légumes', duration: '8', utensil: fresh },
      { text: 'Égoutter, mélanger', waits: ['Étape 3', 'Étape 4'] },
    ]
    for (const step of steps) {
      await shell.content.getByRole('button', { name: 'Ajouter une étape' }).click()
      await dialog.getByLabel('Texte').fill(step.text)
      if (step.duration) await dialog.getByLabel('Durée (min)').fill(step.duration)
      if (step.background) await dialog.getByRole('checkbox', { name: 'En fond' }).check()
      for (const earlier of step.waits ?? []) await chip('Attend', earlier)
      if (step.ingredient) await chip('Ingrédients', step.ingredient)
      if (step.utensil) await chip('Ustensiles', step.utensil)
      await save()
    }

    // Then the API holds the five steps as entered
    interface StoredStep {
      position: number
      text: string
      durationMinutes: number | null
      background: boolean
      waitsFor: number[]
    }
    await expect
      .poll(
        async () => {
          const read = await api.get(iri, { headers: { Accept: 'application/ld+json' } })
          const record = (await read.json()) as { steps?: StoredStep[] }
          return (record.steps ?? []).map(({ position, text, durationMinutes, background, waitsFor }) => ({ position, text, durationMinutes, background, waitsFor }))
        },
        { timeout: 30_000, message: 'The steps entered in the form never reached the API' },
      )
      .toEqual([
        { position: 1, text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, waitsFor: [] },
        { position: 2, text: 'Couper les légumes', durationMinutes: null, background: false, waitsFor: [] },
        { position: 3, text: 'Cuire les pâtes', durationMinutes: 12, background: true, waitsFor: [1] },
        { position: 4, text: 'Faire revenir les légumes', durationMinutes: 8, background: false, waitsFor: [] },
        { position: 5, text: 'Égoutter, mélanger', durationMinutes: null, background: false, waitsFor: [3, 4] },
      ])

    // And the sheet shows the equipment, and the fifth step waiting for 3 and 4
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
    await expect(shell.content.getByRole('heading', { name })).toBeVisible()
    const equipment = shell.content.getByRole('list', { name: 'Matériel' })
    await expect(equipment.getByText(existing)).toBeVisible()
    await expect(equipment.getByText(fresh)).toBeVisible()
    await expect(shell.content.getByText('attend 3, 4')).toBeVisible()
    await expect(shell.content.getByText('⏱ 12 min')).toBeVisible()
  })

  test('on a 2000 px window the recipe sheet keeps its reading width, so a quantity stays next to its ingredient — MAG-612', async ({ page, api }) => {
    // Given a recipe with ingredients, and a very wide window
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const stamp = Date.now()
    const ingredientName = `Lardons MAG-612 ${stamp}`
    const ingredient = await api.post('/api/ingredients', { headers, data: { name: ingredientName, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const ingredientIri = ((await ingredient.json()) as { '@id': string })['@id']
    const created = await api.post('/api/recipes', {
      headers,
      data: { name: `Fiche large MAG-612 ${stamp}`, servings: 4, ingredients: [{ ingredient: ingredientIri, quantity: 200, unit: 'g' }] },
    })
    expect(created.status(), `POST answered ${created.status()}: ${await created.text()}`).toBe(201)
    const { '@id': iri, name } = (await created.json()) as { '@id': string; name: string }
    await page.setViewportSize({ width: 2000, height: 1000 })

    // When I open its sheet
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
    await expect(shell.content.getByRole('heading', { name })).toBeVisible()

    // Then the sheet does not go past the reading width, and the quantity is within it of the ingredient's name
    const area = await shell.content.boundingBox()
    expect(area?.width, 'The content area must be wider than the reading width, or the cap proves nothing').toBeGreaterThan(1100)
    const sheet = await shell.content.getByTestId('reading-page').boundingBox()
    expect(sheet?.width).toBeLessThanOrEqual(1100)
    const label = await shell.content.getByText(ingredientName, { exact: true }).first().boundingBox()
    const quantity = await shell.content.getByRole('button', { name: `Modifier la quantité de ${ingredientName}` }).boundingBox()
    expect(label, 'The ingredient name is not on the sheet').not.toBeNull()
    expect(quantity, 'The quantity is not on the sheet').not.toBeNull()
    expect(quantity!.x + quantity!.width - label!.x).toBeLessThanOrEqual(1100)
  })

  test('an open recipe sheet shows the quantity changed elsewhere, and a popin keeps the text being typed — MAG-330', async ({
    page,
    api,
    session,
  }) => {
    // Given a recipe with a line of 400 g, open in the admin.
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const orge = `Orge MAG-330 ${Date.now()}`
    const ingredient = await api.post('/api/ingredients', { headers, data: { name: orge, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const ingredientIri = ((await ingredient.json()) as { '@id': string })['@id']

    const created = await api.post('/api/recipes', {
      headers,
      data: { name: `Salade d'orge MAG-330 ${Date.now()}`, servings: 2, ingredients: [{ ingredient: ingredientIri, quantity: 400, unit: 'g' }] },
    })
    expect(created.status()).toBe(201)
    const iri = ((await created.json()) as { '@id': string })['@id']

    // What another device, or Maggie, would send: the recipe as the GET returns
    // it, with one part changed.
    const change = async (edit: (record: { ingredients: Array<{ quantity: number }>; notes?: string }) => void): Promise<void> => {
      const read = await api.get(iri, { headers: { Accept: 'application/ld+json' } })
      expect(read.ok()).toBeTruthy()
      const record = (await read.json()) as { ingredients: Array<{ quantity: number }>; notes?: string }
      edit(record)
      const updated = await api.patch(iri, {
        headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
        data: record,
      })
      expect(updated.ok(), `PATCH answered ${updated.status()}: ${await updated.text()}`).toBeTruthy()
    }

    const shell = new AdminShell(page)
    await openSubscribed(page, () => shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`), userTopic(session.user.id, '/api/recipes/{id}'))
    const quantity = (observer: Page) => observer.getByRole('button', { name: `Modifier la quantité de ${orge}`, includeHidden: true })
    await expect(quantity(page)).toHaveText(/400/)

    // When the quantity changes elsewhere, the sheet shows it with no reload.
    await expectRealtimeSync(
      page,
      () => change((record) => { record.ingredients[0].quantity = 600 }),
      async (observer) => {
        await expect(quantity(observer)).toHaveText(/600/, { timeout: 10_000 })
      },
    )

    // And when I am typing the notes while they change elsewhere, my text stays in
    // the popin and the sheet behind follows.
    await shell.content.getByRole('button', { name: 'Modifier les notes' }).click()
    const dialog = page.getByRole('dialog', { name: 'Notes' })
    await dialog.getByLabel('Notes').fill('Ma version des notes')
    await expectRealtimeSync(
      page,
      () => change((record) => { record.notes = 'Notes de l’autre appareil'; record.ingredients[0].quantity = 750 }),
      async (observer) => {
        await expect(quantity(observer)).toHaveText(/750/, { timeout: 10_000 })
      },
    )
    await expect(dialog.getByLabel('Notes')).toHaveValue('Ma version des notes')

    // Closed without saving, the sheet shows what the other device wrote.
    await dialog.getByRole('button', { name: 'Annuler' }).click()
    await expect(shell.content.getByText('Notes de l’autre appareil')).toBeVisible()
  })

  test('replacing the lardons by the smoked belly moves the step links, and rewrites steps 2 and 6 only when asked — MAG-601', async ({ page, api }) => {
    // Given a bœuf bourguignon whose steps 2 and 6 cite the lardons by name and quantity
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const product = async (name: string): Promise<{ '@id': string; id: string }> => {
      const created = await api.post('/api/ingredients', { headers, data: { name, category: 'other' } })
      expect(created.status(), `POST /api/ingredients answered ${created.status()}`).toBe(201)

      return (await created.json()) as { '@id': string; id: string }
    }
    const boeuf = await product('Bœuf à braiser')
    const lardons = await product('Lardons fumés')
    await product('Poitrine fumée')
    const STEP_2 = 'Faire revenir 200 g de lardons fumés dans la cocotte'
    const STEP_6 = 'Servir avec lardons fumés et pain grillé'

    const created = await api.post('/api/recipes', {
      headers,
      data: {
        name: 'Bœuf bourguignon MAG-601',
        servings: 4,
        ingredients: [
          { ingredient: boeuf['@id'], quantity: 800, unit: 'g' },
          { ingredient: lardons['@id'], quantity: 200, unit: 'g' },
        ],
        steps: [
          { text: 'Couper la viande en cubes', ingredientIds: [boeuf.id] },
          { text: STEP_2, ingredientIds: [lardons.id] },
          { text: 'Ajouter le vin et laisser mijoter', durationMinutes: 150, background: true },
          { text: 'Préparer la purée' },
          { text: 'Dresser les assiettes' },
          { text: STEP_6, ingredientIds: [lardons.id] },
        ],
      },
    })
    expect(created.status(), `POST answered ${created.status()}: ${await created.text()}`).toBe(201)
    const iri = ((await created.json()) as { '@id': string })['@id']
    await waitForIndexed<{ name: string }>(api, '/api/products?itemsPerPage=100', (p) => p.name === 'Poitrine fumée', {
      what: 'The product the lardons are replaced by',
    })

    // When I replace the lardons by the smoked belly from their ⋮ menu
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
    const line = shell.content.getByRole('button', { name: 'Modifier la quantité de Lardons fumés' }).locator('xpath=ancestor::li[1]')
    await expect(line).toContainText('utilisé dans les étapes 2, 6')
    // And the sheet offers no edit form: no « Éditer » button, and the old edit URL leads back to the sheet
    await expect(page.getByRole('link', { name: 'Éditer' })).toHaveCount(0)
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}`)
    await expect(page).toHaveURL(/#\/recipes\/.+\/show/)
    await expect(line).toContainText('utilisé dans les étapes 2, 6')
    // A stale link under the recipe (MAG-607) settles on the sheet instead of redirecting forever
    await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/edit`)
    await expect(page).toHaveURL(/#\/recipes\/[^/]+\/show$/)
    await expect(line).toContainText('utilisé dans les étapes 2, 6')
    await line.getByRole('button', { name: 'Actions pour Lardons fumés' }).click()
    await page.getByRole('menuitem', { name: 'Modifier' }).click()

    const dialog = page.getByRole('dialog', { name: /Modifier « Lardons fumés »/ })
    const picker = dialog.getByLabel('Ingrédient')
    await picker.fill('Poitrine fumée')
    await page.getByRole('option', { name: 'Poitrine fumée', exact: true }).click()
    await dialog.getByRole('button', { name: 'Enregistrer' }).click()

    // Then the links have moved, and the texts that cite the lardons are offered before and after, untouched
    const offer = page.getByRole('dialog', { name: 'Mettre à jour les étapes ?' })
    await expect(offer).toBeVisible()
    const list = offer.getByRole('list', { name: 'Étapes à mettre à jour' })
    await expect(list.getByText(STEP_2)).toBeVisible()
    await expect(list.getByText('Faire revenir 200 g de poitrine fumée dans la cocotte')).toBeVisible()
    await expect(list.getByText(STEP_6)).toBeVisible()
    await expect(list.getByText('Servir avec poitrine fumée et pain grillé')).toBeVisible()

    interface StoredStep {
      position: number
      text: string
      ingredients?: Array<{ name: string }>
    }
    const stepsOf = async (): Promise<StoredStep[]> => {
      const read = await api.get(iri, { headers: { Accept: 'application/ld+json' } })

      return ((await read.json()) as { steps: StoredStep[] }).steps
    }
    expect((await stepsOf())[1].text, 'the text was rewritten before the owner said so').toBe(STEP_2)

    // And « Mettre à jour » rewrites steps 2 and 6, and no other
    await offer.getByRole('button', { name: 'Mettre à jour' }).click()
    await expect(offer).toBeHidden()
    const steps = shell.content.getByRole('listitem', { name: /^Étape \d+$/ })
    await expect(steps.nth(1)).toContainText('Faire revenir 200 g de poitrine fumée dans la cocotte')
    await expect(steps.nth(5)).toContainText('Servir avec poitrine fumée et pain grillé')
    await expect(steps.nth(0)).toContainText('Couper la viande en cubes')

    await expect
      .poll(async () => (await stepsOf()).map((step) => step.text), { timeout: 30_000, message: 'The rewritten steps never reached the API' })
      .toEqual([
        'Couper la viande en cubes',
        'Faire revenir 200 g de poitrine fumée dans la cocotte',
        'Ajouter le vin et laisser mijoter',
        'Préparer la purée',
        'Dresser les assiettes',
        'Servir avec poitrine fumée et pain grillé',
      ])
    const stored = await stepsOf()
    expect(stored[1].ingredients?.map((i) => i.name)).toEqual(['Poitrine fumée'])
    expect(stored[5].ingredients?.map((i) => i.name)).toEqual(['Poitrine fumée'])
  })
})
