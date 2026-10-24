import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { plannedMealId, waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'
import { MealSettingsPage } from '../pages/MealSettingsPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The guests of a meal — MAG-483 (the screens), MAG-482 (the arithmetic), extending MAG-101.
 *
 * A recipe for 4 with 400 g of pasta, a meal for 6: the list holds 600 g. The
 * recipe sheet for 8 reads 800 g and the recipe itself is not touched. The
 * rounding rule is `RecipeScalerTest`'s; what is checked here is that the
 * screens show what the API computed and compute nothing themselves.
 */

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Line {
  label: string
  quantity: number | null
  unit: string | null
}

interface GroceryListRow {
  items: Line[]
}

interface MealRow {
  '@id': string
  id: string
  summary: string
  guests?: number
  guestsIsDefault?: boolean
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<{ '@id': string; id: string }> {
  const response = await api.post(path, { headers: JSON_LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as { '@id': string; id: string }
}

async function defaultGuestsOf(api: APIRequestContext): Promise<number> {
  const response = await api.get('/api/meal-settings/me', { headers: { Accept: 'application/json' } })
  expect(response.ok(), `GET /api/meal-settings/me answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { defaultGuests: number }).defaultGuests
}

function lineOf(list: GroceryListRow, label: string): Line | undefined {
  return list.items.find((item) => item.label === label)
}

test.describe('The guests of a meal', () => {
  test('a meal for 6 puts 600 g of a 400 g-for-4 recipe on the list, rescales with its guests, and the recipe sheet simulates 8 without saving — MAG-483', async ({
    page,
    api,
  }) => {
    const shell = new AdminShell(page)
    const suffix = `${Date.now()}`
    const pastaName = `Pâtes MAG-483 ${suffix}`
    const recipeName = `Pâtes au beurre MAG-483 ${suffix}`
    const cleanup: string[] = []

    // Given a recipe for 4 with 400 g of pasta (no packaging: the need is what goes on the list).
    const pasta = await created(api, '/api/ingredients', { name: pastaName, category: 'grain', stockState: 'out' })
    const recipe = await created(api, '/api/recipes', {
      name: recipeName,
      servings: 4,
      ingredients: [{ ingredient: pasta['@id'], quantity: 400, unit: 'g' }],
    })
    cleanup.push(recipe['@id'], pasta['@id'])
    await waitForIndexed<{ name: string }>(api, '/api/recipes?itemsPerPage=200', (row) => row.name === recipeName, {
      what: 'The recipe to plan',
    })
    const defaultGuests = await defaultGuestsOf(api)

    try {
      // When I plan it on Sunday dinner, the « Convives » field marked « par défaut »…
      await shell.goto(ROUTES.meals)
      await shell.content.getByTestId('meal-cell-dinner-6').click()
      const dialog = page.getByRole('dialog')
      await dialog.getByLabel('Menu').click()
      await page.getByRole('option', { name: recipeName }).click()
      const field = dialog.getByRole('group', { name: 'Convives' })
      await expect(field.getByTestId('guests-value')).toHaveText(String(defaultGuests))
      await expect(field.getByText('par défaut')).toBeVisible()

      // …step it to 6: the mark goes.
      while (Number(await field.getByTestId('guests-value').textContent()) !== 6) {
        const shown = Number(await field.getByTestId('guests-value').textContent())
        await field.getByRole('button', { name: shown < 6 ? /Un convive de plus/ : /Un convive de moins/ }).click()
      }
      await expect(field.getByText('par défaut')).toHaveCount(0)
      const mealId = await plannedMealId(page, () => dialog.getByRole('button', { name: 'Créer' }).click())

      // Then the choice says the quantities are for 6 guests, and reads 600 g.
      const choice = page.getByRole('dialog')
      await expect(choice.getByTestId('meal-guests')).toHaveText('Quantités pour 6 convives')
      const row = choice.getByRole('listitem').filter({ hasText: pastaName })
      await expect(row).toContainText('600 g')
      await choice.getByRole('button', { name: 'Ajouter aux courses' }).click()
      await expect(choice).toBeHidden()

      const meal = await waitForIndexed<MealRow>(api, '/api/meals?itemsPerPage=200', (m) => m.id === mealId, {
        what: 'The planned meal',
      })
      cleanup.unshift(meal['@id'])
      expect(meal.guests).toBe(6)

      // And the list holds 600 g.
      const onList = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => lineOf(list, pastaName)?.quantity === 600, {
        what: 'A single 600 g pasta line',
      })
      expect(lineOf(onList, pastaName)?.unit).toBe('g')
      const grocery = new GroceryListPage(page)
      await grocery.open()
      await expect(grocery.line(pastaName)).toContainText('600 g')

      // When I give the meal back to the default guests…
      await shell.goto(ROUTES.meals)
      await page.getByRole('button', { name: new RegExp(`Convives du repas .*${recipeName}`) }).click()
      const guests = page.getByRole('dialog').filter({ hasText: 'Convives du repas' })
      await guests.getByRole('button', { name: 'Revenir au défaut' }).click()
      await expect(guests).toBeHidden()

      // …then the list follows: the recipe for 4 scaled to the default.
      await waitForIndexed<GroceryListRow>(
        api,
        '/api/grocery_lists',
        (list) => lineOf(list, pastaName)?.quantity === defaultGuests * 100,
        { what: `The pasta line rescaled to ${defaultGuests * 100} g` },
      )

      // When I open the recipe sheet and simulate 8 guests…
      await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(recipe['@id'])}/show`)
      const quantity = shell.content.getByRole('button', { name: /Modifier la quantité de/ })
      await expect(quantity).toHaveText(/400/)
      for (let i = 0; i < 4; i++) {
        await shell.content.getByRole('button', { name: /Un convive de plus/ }).click()
      }

      // …then the quantities are the API's, for 8: 800 g.
      await expect(shell.content.getByTestId('guests-value')).toHaveText('8')
      await expect(quantity).toHaveText(/800/)

      // One click gives the recipe's own servings back, and nothing was saved.
      await shell.content.getByRole('button', { name: 'Revenir à 4' }).click()
      await expect(shell.content.getByTestId('guests-value')).toHaveText('4')
      await expect(quantity).toHaveText(/400/)
      const stored = await api.get(recipe['@id'], { headers: { Accept: 'application/ld+json' } })
      const record = (await stored.json()) as { servings: number; ingredients: Array<{ quantity: number }> }
      expect(record.servings).toBe(4)
      expect(record.ingredients.map((i) => i.quantity)).toEqual([400])
    } finally {
      for (const iri of cleanup) await api.delete(iri)
    }
  })

  test('Réglages › Repas holds the default number of guests, and the API serves it to the phone — MAG-483', async ({ otherUser }) => {
    const { page, api } = otherUser
    const before = await defaultGuestsOf(api)
    const settings = new MealSettingsPage(page)

    try {
      await settings.open()
      await expect(settings.guests).toHaveText(String(before))

      await settings.stepTo(5)
      await settings.save()

      await page.reload()
      await expect(settings.guests).toHaveText('5')
      expect(await defaultGuestsOf(api)).toBe(5)
    } finally {
      const reset = await api.put('/api/meal-settings/me', { data: { defaultGuests: before } })
      expect(reset.ok(), `PUT /api/meal-settings/me answered ${reset.status()}`).toBe(true)
    }
  })
})
