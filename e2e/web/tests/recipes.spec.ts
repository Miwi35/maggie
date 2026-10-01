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
})
