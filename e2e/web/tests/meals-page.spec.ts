import { test, expect } from '../fixtures/index.js'
import { plannedMealId, waitForIndexed } from '../helpers/api.js'
import { dayOfThisWeek } from '../helpers/week.js'
import { MealsPage } from '../pages/MealsPage.js'

/**
 * Cuisine › Repas, one page with a Menu view and a Liste view — MAG-628, extending MAG-101.
 *
 * A meal of two recipes for four, planned this week through the API: the Menu,
 * which the page opens on, shows it under its day with both recipes and its
 * convives. « + Ajouter un repas » on another day creates a real meal, which
 * the API then holds on that day.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface MealRow {
  id: string
  date: string
  slot: string
  guests?: number
  recipes?: unknown[]
}

// The account of this journey alone: the week it reads is the one it wrote.
test.use({ journeyAccount: 'standard' })

test('Cuisine › Repas opens on the Menu with the week’s meal, its two recipes and its convives, and « + Ajouter un repas » creates a meal — MAG-628', async ({
  page,
  api,
  journeyUser,
}) => {
  const { retry } = test.info()
  const suffix = 0 === retry ? '' : ` essai ${retry}`
  const first = `Soupe MAG-628${suffix}`
  const second = `Tarte MAG-628${suffix}`
  const added = `Salade MAG-628${suffix}`

  const iris: string[] = []
  for (const name of [first, second, added]) {
    const recipe = await api.post('/api/recipes', { headers: jsonLd, data: { name, servings: 4 } })
    expect(recipe.status(), `POST /api/recipes answered ${recipe.status()}`).toBe(201)
    iris.push(((await recipe.json()) as { '@id': string })['@id'])
  }

  // Given a dinner of two recipes for four, on Wednesday of this week.
  const planned = await api.post('/api/meals', {
    headers: jsonLd,
    data: {
      summary: 'Dîner',
      date: dayOfThisWeek(2),
      slot: 'dinner',
      guests: 4,
      agenda: `/api/agendas/${journeyUser.ref('e2e_agenda_personal')}`,
      recipes: [iris[0], iris[1]],
    },
  })
  expect(planned.status(), `POST /api/meals answered ${planned.status()}: ${await planned.text()}`).toBe(201)
  const mealId = ((await planned.json()) as { id: string }).id

  await waitForIndexed<MealRow>(api, '/api/meals?itemsPerPage=200', (m) => m.id === mealId, { what: 'The planned meal' })
  await waitForIndexed<{ name: string }>(api, '/api/recipes?itemsPerPage=200', (r) => r.name === second, {
    what: 'The recipes of the meal',
  })

  // When I open Cuisine › Repas…
  const meals = new MealsPage(page)
  await meals.goto('/')
  await meals.expectLoaded()
  await meals.navigateTo('Repas', 'Cuisine')

  // …it is the Menu, and Wednesday holds the meal with its two recipes and four convives.
  await meals.expectMenuView()
  const card = meals.cardOf(mealId)
  await expect(meals.day(2)).toContainText('Mercredi')
  await expect(meals.day(2).getByTestId(`meal-${mealId}`)).toBeVisible()
  await expect(card).toContainText(first)
  await expect(card).toContainText(second)
  await expect(card.getByTestId('guests-value')).toHaveText('4')

  // When I add a meal on Friday lunch with a third recipe…
  const friday = dayOfThisWeek(4)
  const dialog = await meals.openAddDialog(4, 'lunch')
  await expect(dialog.getByLabel('Date')).toHaveValue(friday)
  await meals.pickRecipe(dialog, added, added)
  const createdId = await plannedMealId(page, () => dialog.getByRole('button', { name: 'Créer' }).click())

  // …then the API holds it on that day, and the Menu shows it under Friday.
  const stored = await waitForIndexed<MealRow>(api, '/api/meals?itemsPerPage=200', (m) => m.id === createdId, {
    what: 'The meal added from the page',
  })
  expect(stored.date).toBe(friday)
  expect(stored.slot).toBe('lunch')
  expect(stored.recipes).toHaveLength(1)

  // The step after the meal — which ingredients go on the list — is not this journey's.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await page.reload()
  await expect(meals.day(4)).toContainText(added)
})
