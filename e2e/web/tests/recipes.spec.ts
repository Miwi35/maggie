import { test, expect, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
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

    // The user has no "Repas" agenda in the seed, so the default one is used.
    expect(JSON.stringify(stored.agenda)).toContain(seedId('e2e_agenda_personal'))
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

    const withParmesan = await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => JSON.stringify(list.items).includes(parmesan),
      { what: 'The gratin’s parmesan' },
    )
    // The lines that were there before, so the cancellation below can be shown
    // to take back the meal's share and nothing else.
    const before = withParmesan.items.length

    // The week view fetches when it mounts and once on its own write, both
    // within a blink of the POST and so inside Elasticsearch's refresh — the
    // cell can still look empty, and nothing refetches on its own afterwards.
    // Wait for the meal to be findable, then reload, or the delete button is
    // one that never appears.
    await waitForIndexed<MealRow>(api, '/api/meals', (m) => String(m.summary).includes('Gratin'), {
      what: 'The planned gratin',
    })
    await page.reload()

    const planned = shell.content.getByTestId('meal-cell-lunch-3')
    await expect(planned.getByText('Gratin de courgettes')).toBeVisible()
    await planned.getByRole('button').first().click()

    const after = await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => !JSON.stringify(list.items).includes(parmesan),
      { what: 'A grocery list without the cancelled meal’s parmesan' },
    )
    // Only the meal's own line goes: the seeded shopping is still to be done.
    expect(after.items).toHaveLength(before - 1)
  })
})
