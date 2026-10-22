import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Adding a line in packagings or in quantity (MAG-573, extends MAG-101).
 *
 * « Pâtes sèches » is bought in packs of 500 g and comes back two packs at a
 * time: the add dialog offers « 2 paquets » (the product's restock), a
 * quantity typed in grams is turned into packs, and the typed amount stays on
 * the line as the wanted quantity. Telling Maggie « ajoute 2 paquets de pâtes »
 * is the same thing through `add_grocery_item(packagings: 2)`.
 *
 * « Riz basmati » (500 g packs, restock 2) is what the week's meals need: the
 * dialog says « Pour les recettes de la semaine · 800 g » with the meals behind
 * it, and when a third meal makes the restock fall short it warns and offers
 * « Ajuster à 3 paquets ».
 *
 * Signed in as the stock account, which owns its data (see grocery-stock-chat):
 * the product is created here and the lines are cleaned up at the end of each
 * test, so a retry starts clean — hence `retries: 0` like its neighbour.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const PASTA = 'Pâtes sèches'
const RICE = 'Riz basmati'
const MEAL_PREFIX = 'MAG-573 web'

interface ProductRow {
  name: string
}
interface Line {
  id: string
  label: string
  quantity: number | null
  unit?: string | null
  desiredQuantity?: number | null
  desiredUnit?: string | null
  needed?: Array<{ quantity: number; unit: string }> | null
}
interface StoredList {
  items: Line[]
}

async function lineOf(api: APIRequestContext, label: string): Promise<Line | undefined> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  return list?.items.find((line) => label === line.label)
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<{ '@id': string; id: string }> {
  const response = await api.post(path, { headers: LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as { '@id': string; id: string }
}

const day = (offset: number): string => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10)

/** A meal in `offset` days that needs `grams` of the rice: a recipe of its own, and the meal that plans it. */
async function planRice(
  api: APIRequestContext,
  rice: { '@id': string },
  name: string,
  grams: number,
  offset: number,
): Promise<{ meal: string; recipe: string }> {
  const recipe = await created(api, '/api/recipes', {
    name: `${name} ${MEAL_PREFIX}`,
    servings: 2,
    ingredients: [{ ingredient: rice['@id'], quantity: grams, unit: 'g' }],
  })
  const meal = await created(api, '/api/meals', { date: day(offset), slot: 'dinner', recipes: [recipe['@id']] })

  return { meal: meal['@id'], recipe: recipe['@id'] }
}

async function expectWeekNeed(api: APIRequestContext, productId: string, grams: number): Promise<void> {
  await expect
    .poll(
      async () => {
        const response = await api.get(`/api/grocery/recipe-need/${productId}`, { headers: { Accept: 'application/json' } })

        return response.ok() ? ((await response.json()) as { recipeNeed: { quantity: number; unit: string } | null }).recipeNeed : null
      },
      { message: `the week never needed ${grams} g of the rice`, timeout: 30_000 },
    )
    .toEqual({ quantity: grams, unit: 'g' })
}

test.beforeEach(async ({ stockUser }) => {
  const { api } = stockUser

  const known = await getCollection<ProductRow>(api, '/api/products?itemsPerPage=200')
  if (!known.some((row) => PASTA === row.name)) {
    const created = await api.post('/api/products', {
      headers: LD,
      data: {
        name: PASTA,
        category: 'grain',
        packagingUnit: 'pack',
        packagingSize: 500,
        packagingSizeUnit: 'g',
        restockQuantity: 2,
        autoRestock: true,
      },
    })
    expect(created.status(), `the API refused the pasta: ${await created.text()}`).toBe(201)
  }

  await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => PASTA === row.name, {
    what: 'The packaged pasta',
  })
})

test('the add dialog offers the restock in packs, and a quantity in grams becomes one pack with 300 g kept — MAG-573', async ({
  stockUser,
}) => {
  const { api, page } = stockUser
  const grocery = new GroceryListPage(page)
  await grocery.open()

  try {
    await grocery.addButton.click()
    await grocery.addDialog.getByLabel('Article').fill(PASTA)

    // The product's restock, in packs, flagged as such.
    const entry = grocery.addDialog.getByTestId('quantity-entry')
    await expect(entry.getByLabel('Nombre de paquets')).toHaveValue('2')
    await expect(entry.getByTestId('packaging-caption')).toHaveText('paquets de 500 g')
    await expect(entry.getByTestId('prefilled-chip')).toHaveText('prérempli · réassort')
    await expect(entry.getByTestId('quantity-summary')).toHaveText('1 kg · 2 paquets')

    // Switching to a quantity: 300 g is one pack to buy.
    await entry.getByRole('button', { name: 'Quantité', exact: true }).click()
    await entry.getByLabel('Quantité', { exact: true }).fill('300')
    await entry.getByLabel('Unité').click()
    await page.getByRole('option', { name: 'g', exact: true }).click()
    await expect(entry.getByTestId('quantity-summary')).toHaveText('300 g · 1 paquet')

    await grocery.addDialog.getByRole('button', { name: 'Ajouter' }).click()
    await expect(grocery.addDialog).toBeHidden()

    await expect
      .poll(async () => lineOf(api, PASTA), { message: 'the pasta never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 1, unit: 'pack', desiredQuantity: 300, desiredUnit: 'g' })

    await grocery.expectItemEventually(PASTA)
    await expect(grocery.line(PASTA)).toContainText('1 paquet')
    await expect(grocery.line(PASTA)).toContainText('300 g')
  } finally {
    await grocery.deleteLineIfPresent(PASTA)
  }
})

test('« ajoute 2 paquets de pâtes » puts two packs on the list through add_grocery_item — MAG-573', async ({
  stockUser,
}) => {
  const { api, page } = stockUser
  const grocery = new GroceryListPage(page)
  await grocery.open()

  try {
    const chat = new ChatPanel(page)
    const events = await chat.send('Ajoute 2 paquets de pâtes')

    expect(calledTools(events)).toContain('add_grocery_item')
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'add_grocery_item', status: 'success' })]),
    )

    await expect
      .poll(async () => lineOf(api, PASTA), { message: 'the packs never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 2, unit: 'pack' })
    await expect(grocery.line(PASTA)).toBeVisible()
  } finally {
    await grocery.deleteLineIfPresent(PASTA)
  }
})

test('the dialog reads the recipes of the week, adds the restock, and warns once a third meal falls short — MAG-573', async ({
  stockUser,
}) => {
  const { api, page } = stockUser
  const grocery = new GroceryListPage(page)
  const planned: Array<{ meal: string; recipe: string }> = []

  // Given « Riz basmati » in 500 g packs, restocked by 2, and two meals this week asking 500 g and 300 g of it.
  const rice = await created(api, '/api/ingredients', {
    name: RICE,
    category: 'grain',
    packagingUnit: 'pack',
    packagingSize: 500,
    packagingSizeUnit: 'g',
    restockQuantity: 2,
    autoRestock: true,
  })

  try {
    planned.push(await planRice(api, rice, 'Risotto', 500, 1))
    planned.push(await planRice(api, rice, 'Riz cantonais', 300, 3))
    await expectWeekNeed(api, rice.id, 800)
    await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => RICE === row.name, { what: 'The rice' })

    // When I add it from the list without choosing anything…
    await grocery.open()
    await grocery.addButton.click()
    await grocery.addDialog.getByLabel('Article').fill(RICE)

    // …the dialog tells the week's need and the meals behind it, starts at the restock, and covers it.
    const entry = grocery.addDialog.getByTestId('quantity-entry')
    await expect(entry.getByTestId('recipe-need')).toContainText('Pour les recettes de la semaine · 800 g')
    await expect(entry.getByTestId('recipe-need-meals')).toContainText('Risotto')
    await expect(entry.getByTestId('recipe-need-meals')).toContainText('Riz cantonais')
    await expect(entry.getByLabel('Nombre de paquets')).toHaveValue('2')
    await expect(entry.getByTestId('prefilled-chip')).toHaveText('prérempli · réassort')
    await expect(entry.getByTestId('quantity-summary')).toHaveText('1 kg (dont 800 g pour les recettes) · 2 paquets')
    await expect(entry.getByTestId('need-shortfall')).toHaveCount(0)

    await grocery.addDialog.getByRole('button', { name: 'Ajouter' }).click()
    await expect(grocery.addDialog).toBeHidden()

    // Then the line holds 2 packs, 1 kg wanted, 800 g for the recipes.
    await expect
      .poll(async () => lineOf(api, RICE), { message: 'the rice never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 2, unit: 'pack', desiredQuantity: 1000, desiredUnit: 'g', needed: [{ quantity: 800, unit: 'g' }] })
    await grocery.expectItemEventually(RICE)
    await expect(grocery.line(RICE)).toContainText('2 paquets')
    await expect(grocery.line(RICE)).toContainText('1 kg (dont 800 g pour les recettes)')

    // When a third meal brings the week to 1,2 kg and I add the rice again…
    await grocery.deleteLine(RICE)
    planned.push(await planRice(api, rice, 'Riz au lait', 400, 4))
    await expectWeekNeed(api, rice.id, 1200)
    await grocery.open()
    await grocery.addButton.click()
    await grocery.addDialog.getByLabel('Article').fill(RICE)

    // …the restock no longer covers it: the dialog warns and offers 3 packs.
    const alert = entry.getByTestId('need-shortfall')
    await expect(alert).toContainText('Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes)')
    await expect(entry.getByLabel('Nombre de paquets')).toHaveValue('2')
    await alert.getByRole('button', { name: 'Ajuster à 3 paquets' }).click()
    await expect(entry.getByLabel('Nombre de paquets')).toHaveValue('3')
    await expect(alert).toHaveCount(0)
    await expect(entry.getByTestId('quantity-summary')).toHaveText('1,5 kg (dont 1,2 kg pour les recettes) · 3 paquets')

    await grocery.addDialog.getByRole('button', { name: 'Ajouter' }).click()
    await expect(grocery.addDialog).toBeHidden()
    await expect
      .poll(async () => lineOf(api, RICE), { message: 'the 3 packs never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 3, unit: 'pack', desiredQuantity: 1500, desiredUnit: 'g' })
  } finally {
    await grocery.deleteLineIfPresent(RICE)
    // The meals first, then their recipes, then the rice: the order the API lets go of them.
    for (const { meal } of planned) await api.delete(meal)
    for (const { recipe } of planned) await api.delete(recipe)
    await api.delete(rice['@id'])
  }
})

test('« ajoute du riz basmati » takes the restock, and says so when the week needs more — MAG-573', async ({ stockUser }) => {
  const { api, page } = stockUser
  const grocery = new GroceryListPage(page)
  const planned: Array<{ meal: string; recipe: string }> = []

  // Given « Riz basmati » in 500 g packs, restocked by 2, and two meals this week asking 800 g of it.
  const rice = await created(api, '/api/ingredients', {
    name: RICE,
    category: 'grain',
    packagingUnit: 'pack',
    packagingSize: 500,
    packagingSizeUnit: 'g',
    restockQuantity: 2,
    autoRestock: true,
  })

  try {
    planned.push(await planRice(api, rice, 'Risotto', 500, 1))
    planned.push(await planRice(api, rice, 'Riz cantonais', 300, 3))
    await expectWeekNeed(api, rice.id, 800)
    await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => RICE === row.name, { what: 'The rice' })
    await grocery.open()
    const chat = new ChatPanel(page)

    // When I tell Maggie « ajoute du riz basmati » with no quantity…
    const events = await chat.send('Ajoute du riz basmati')
    expect(calledTools(events)).toContain('add_grocery_item')

    // …the restock is the default: 2 packs, 1 kg wanted, 800 g for the recipes.
    await expect
      .poll(async () => lineOf(api, RICE), { message: 'the rice never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 2, unit: 'pack', desiredQuantity: 1000, desiredUnit: 'g', needed: [{ quantity: 800, unit: 'g' }] })
    await expect(page.getByText('couvre les repas de la semaine').last()).toBeVisible()
    await grocery.deleteLine(RICE)

    // When a third meal brings the week to 1,2 kg and I ask again…
    planned.push(await planRice(api, rice, 'Riz au lait', 400, 4))
    await expectWeekNeed(api, rice.id, 1200)
    const second = await chat.send('Ajoute du riz basmati, la semaine est chargée')
    expect(calledTools(second)).toContain('add_grocery_item')

    // …the line still holds the restock and Maggie says it falls short and offers 3 packs.
    await expect
      .poll(async () => lineOf(api, RICE), { message: 'the rice never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 2, unit: 'pack' })
    await expect(page.getByText('Je passe à 3 paquets ?').last()).toBeVisible()
  } finally {
    await grocery.deleteLineIfPresent(RICE)
    for (const { meal } of planned) await api.delete(meal)
    for (const { recipe } of planned) await api.delete(recipe)
    await api.delete(rice['@id'])
  }
})
