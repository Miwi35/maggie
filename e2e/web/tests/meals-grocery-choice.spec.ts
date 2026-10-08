import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'

/**
 * Choosing which ingredients of a meal go on the list, in packagings — MAG-295,
 * extending MAG-101.
 *
 * At the API level for now: the web and mobile screens are tickets of their
 * own, and will take this journey over. What it pins is the decision 4 bis of
 * the « Stock et courses au plus juste » spec: once a meal has chosen, its
 * lines are the chosen ones — « Riz — 1 paquet », not « Riz — 300 g » beside
 * it — and neither editing the recipe nor moving the meal derives them again;
 * moving it only moves when the rice is to be bought.
 *
 * The arithmetic (rounding, joining, choosing twice, a line in the basket) is
 * `MealGroceryChoiceTest` and `MealGrocerySyncTest`, on every pull request.
 *
 * On the owner's list, which is shared: every product here is this run's own,
 * so the lines asserted on are this journey's and nobody else's.
 */

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Line {
  label: string
  quantity: number | null
  unit: string | null
  buyAfter: string | null
}

interface GroceryListRow {
  items: Line[]
}

interface PreviewIngredient {
  ingredientId: string
  name: string
  toBuy: { quantity: number; unit: string }
  stockState: string
  suggested: boolean
}

interface Preview {
  groceryChoiceMadeAt: string | null
  ingredients: PreviewIngredient[]
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<{ '@id': string; id: string }> {
  const response = await api.post(path, { headers: JSON_LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as { '@id': string; id: string }
}

function linesOf(list: GroceryListRow, label: string): Line[] {
  return list.items.filter((item) => item.label === label)
}

/** One line of that label, and it says 1 pack. */
function isOnePack(list: GroceryListRow, label: string): boolean {
  const lines = linesOf(list, label)

  return 1 === lines.length && 1 === lines[0].quantity && 'pack' === lines[0].unit
}

test('a meal puts on the list only the ingredients chosen, in packagings, and keeps them — MAG-295', async ({ api }) => {
  const suffix = `${Date.now()}`
  const riceName = `Riz MAG-295 ${suffix}`
  const vegetablesName = `Légumes pour couscous MAG-295 ${suffix}`

  // Given « Riz » out of stock and bought by the 500 g pack, keeping two days,
  // and the vegetables in the cupboard, bought by the jar.
  const rice = await created(api, '/api/ingredients', {
    name: riceName,
    category: 'grain',
    packagingUnit: 'pack',
    packagingSize: 500,
    packagingSizeUnit: 'g',
    stockState: 'out',
  })
  const keeps = await api.patch(`/api/products/${rice.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
    data: { shelfLifeDays: 2 },
  })
  expect(keeps.ok(), `PATCH /api/products answered ${keeps.status()}: ${await keeps.text()}`).toBeTruthy()
  const vegetables = await created(api, '/api/ingredients', {
    name: vegetablesName,
    category: 'produce',
    packagingUnit: 'jar',
    stockState: 'in_stock',
  })
  const recipe = await created(api, '/api/recipes', {
    name: `Riz au curry MAG-295 ${suffix}`,
    servings: 2,
    ingredients: [
      { ingredient: rice['@id'], quantity: 300, unit: 'g' },
      { ingredient: vegetables['@id'], quantity: 1, unit: 'jar' },
    ],
  })

  // Far enough out that the rice's two days of keeping defer its line, from
  // the seed's anchor or from the wall clock alike.
  const meal = await created(api, '/api/meals', { date: seedDate(20), slot: 'dinner', recipes: [recipe['@id']] })

  // When I ask for the meal's preview…
  const previewed = await api.get(`/api/meals/${meal.id}/grocery_preview`, { headers: { Accept: 'application/json' } })
  expect(previewed.ok(), `GET grocery_preview answered ${previewed.status()}`).toBeTruthy()
  const preview = (await previewed.json()) as Preview
  const byName = Object.fromEntries(preview.ingredients.map((i) => [i.name, i]))

  // …then the rice is suggested at 1 pack, and the vegetables are not.
  expect(preview.groceryChoiceMadeAt).toBeNull()
  expect(byName[riceName]).toMatchObject({ suggested: true, stockState: 'out', toBuy: { quantity: 1, unit: 'pack' } })
  expect(byName[vegetablesName]).toMatchObject({ suggested: false, stockState: 'in_stock' })

  // When I choose the rice alone…
  const chosen = await api.post(`/api/meals/${meal.id}/grocery_items`, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    data: { ingredients: [{ ingredientId: rice.id }] },
  })
  expect(chosen.ok(), `POST grocery_items answered ${chosen.status()}: ${await chosen.text()}`).toBeTruthy()
  expect(((await chosen.json()) as Preview).groceryChoiceMadeAt).not.toBeNull()

  const onePack = (list: GroceryListRow): boolean => isOnePack(list, riceName)

  // …then the list holds one « Riz — 1 paquet », and nothing for the vegetables.
  const afterChoice = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', onePack, {
    what: 'A single « Riz — 1 paquet » line',
  })
  expect(linesOf(afterChoice, vegetablesName), 'the vegetables were not chosen').toHaveLength(0)
  const deferredTo = linesOf(afterChoice, riceName)[0].buyAfter
  expect(deferredTo, 'two days of keeping, twenty days out: the rice waits').toBe(seedDate(18))

  // When I change the recipe of the meal — twice the rice…
  const read = await api.get(recipe['@id'], { headers: { Accept: 'application/ld+json' } })
  const record = (await read.json()) as { ingredients: Array<{ quantity: number; unit: string }> }
  record.ingredients
    .filter((ingredient) => 'g' === ingredient.unit)
    .forEach((ingredient) => {
      ingredient.quantity = 600
    })
  const edited = await api.patch(recipe['@id'], {
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
    data: record,
  })
  expect(edited.ok(), `PATCH recipe answered ${edited.status()}: ${await edited.text()}`).toBeTruthy()

  // …and move the meal three days later.
  const moved = await api.patch(`/api/meals/${meal.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
    data: { date: seedDate(23) },
  })
  expect(moved.ok(), `PATCH meal answered ${moved.status()}: ${await moved.text()}`).toBeTruthy()

  // Then the rice is bought three days later, and the line is the same
  // « Riz — 1 paquet »: no 600 g derived beside it, no quantity changed.
  const afterMove = await waitForIndexed<GroceryListRow>(
    api,
    '/api/grocery_lists',
    (list) => linesOf(list, riceName)[0]?.buyAfter === seedDate(21),
    { what: 'The rice’s line deferred with its meal' },
  )
  expect(onePack(afterMove), 'the chosen line was derived again or its quantity moved').toBe(true)
  expect(linesOf(afterMove, vegetablesName)).toHaveLength(0)
})
