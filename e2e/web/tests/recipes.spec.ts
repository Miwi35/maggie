import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import type { Page } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
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

test.describe('Recipes and meals', () => {
  test('a recipe created with comma-separated tags stores them as a list and shows in the list at once', async ({ page, api }) => {
    const name = `Soupe MAG-117 ${Date.now()}`
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.recipes}/create`)

    await shell.content.getByLabel('Nom').fill(name)
    await shell.content.getByLabel(/Tags/).fill('rapide, hiver')
    await shell.content.getByRole('button', { name: 'Enregistrer' }).click()

    // MAG-117 recette: the owner saved a recipe, stayed on the form and found
    // an empty list. Saving leads to the list, and the list already has the
    // recipe — no reload, no waiting for the index.
    await expect(page.getByText('Recette enregistrée')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`#${ROUTES.recipes}$`))
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
    await dialog.getByLabel('Recettes').fill('Velouté MAG-117')
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
    await dialog.getByLabel('Recettes').fill('Blanquette')
    await page.getByRole('option', { name }).click()
    await dialog.getByRole('button', { name: 'Créer' }).click()

    // The day the API stored, as a day.
    const stored = await waitForIndexed<MealRow>(api, '/api/meals', (m) => String(m.summary).includes(name), {
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

  test('cancelling a meal takes its ingredients back off the grocery list', async ({ page, api }) => {
    // MAG-116: planning a meal put its ingredients on the list and nothing
    // ever took them off again, so a dinner cancelled on Tuesday was still
    // shopping to do on Saturday.
    const parmesan = seedId('e2e_ingredient_parmesan')
    const shell = new AdminShell(page)
    await shell.goto('/meals')

    // Thursday lunch: the seed plans one dinner and the other journey in this
    // file uses Tuesday, so this cell is free whatever order they run in.
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

    // In the cell it was planned in. It used not to be: the grid placed a meal
    // on the day its `startAt` string began with, and a meal stored at midnight
    // in Paris comes back from Elasticsearch in UTC and was drawn a day early
    // (MAG-166). The day is a day now (MAG-251), so the cell is addressable.
    const planned = cell.filter({ hasText: 'Gratin de courgettes' })
    await expect(planned).toBeVisible()
    await planned.getByRole('button', { name: /^Supprimer le repas / }).click()

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
  }) => {
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const suffix = `${Date.now()}`
    const ingredientName = `Semoule MAG-289 ${suffix}`
    const name = `Couscous MAG-289 ${suffix}`

    const ingredient = await api.post('/api/ingredients', { headers, data: { name: ingredientName, category: 'grain' } })
    expect(ingredient.status()).toBe(201)
    const ingredientIri = ((await ingredient.json()) as { '@id': string })['@id']

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
          summary: 'Déjeuner',
          date,
          slot: 'lunch',
          agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
          recipes: [recipeIri],
        },
      })
      expect(meal.status(), `POST /api/meals answered ${meal.status()}: ${await meal.text()}`).toBe(201)
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

    // The day after the seed's anchor, not after the wall clock (MAG-234): the two
    // part ways after midnight, and a week-view assertion cannot tell why.
    const tomorrow = seedDate(1)
    const meal = await api.post('/api/meals', {
      headers,
      data: {
        summary: 'Dîner',
        // A day and a slot, no instant (MAG-251).
        date: tomorrow,
        slot: 'dinner',
        agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
        recipes: [recipeBody['@id']],
      },
    })
    expect(meal.status()).toBe(201)

    interface Line {
      label: string
      quantity: number | null
    }
    const linesOf = (list: GroceryListRow): Line[] => (list.items as Line[]).filter((item) => item.label === name)
    const lineOf = (list: GroceryListRow): Line | undefined => linesOf(list)[0]

    const planned = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => lineOf(list)?.quantity === 400, {
      what: 'The meal’s 400 g of boulgour',
    })
    expect(linesOf(planned), 'the meal planned a single line').toHaveLength(1)

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

    const after = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => lineOf(list)?.quantity === 600, {
      what: 'The recipe’s new 600 g of boulgour',
    })

    // Same line, not a second one beside it. Counted on this journey's own
    // label: the list is shared with the other workers, so its total moves.
    expect(linesOf(after), 'the recipe edit added a second line instead of updating the first').toHaveLength(1)
  })

  test('an open recipe sheet shows the quantity and the notes changed elsewhere, and keeps the field being typed — MAG-330', async ({
    page,
    api,
    session,
  }) => {
    // Given a recipe with a line of 400 g, open in the admin.
    const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const ingredient = await api.post('/api/ingredients', { headers, data: { name: `Orge MAG-330 ${Date.now()}`, category: 'grain' } })
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
    await openSubscribed(page, () => shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}`), userTopic(session.user.id, '/api/recipes/{id}'))
    await expect(shell.content.getByLabel(/Quantité/)).toHaveValue('400')

    // When the quantity changes elsewhere, the sheet shows it with no reload.
    await expectRealtimeSync(
      page,
      () => change((record) => { record.ingredients[0].quantity = 600 }),
      async (observer) => {
        await expect(observer.getByLabel(/Quantité/)).toHaveValue('600', { timeout: 10_000 })
      },
    )

    // And when I am typing the notes while they change elsewhere, my text stays,
    // the other fields follow, and the sheet says the recipe changed.
    await shell.content.getByLabel('Notes').fill('Ma version des notes')
    await expectRealtimeSync(
      page,
      () => change((record) => { record.notes = 'Notes de l’autre appareil'; record.ingredients[0].quantity = 750 }),
      async (observer) => {
        await expect(observer.getByLabel(/Quantité/)).toHaveValue('750', { timeout: 10_000 })
      },
    )
    await expect(shell.content.getByLabel('Notes')).toHaveValue('Ma version des notes')
    await expect(page.getByText(/modifiée ailleurs/)).toBeVisible()

    // « Recharger » takes what the other device wrote.
    await page.getByRole('button', { name: 'Recharger' }).click()
    await expect(shell.content.getByLabel('Notes')).toHaveValue('Notes de l’autre appareil')
    await expect(page.getByText(/modifiée ailleurs/)).toHaveCount(0)
  })
})
