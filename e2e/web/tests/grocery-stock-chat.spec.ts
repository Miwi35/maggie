import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Stock et courses au plus juste (MAG-294, extends MAG-101): the stock is told
 * to Maggie, not typed into a form.
 *
 * « Je n'ai presque plus de riz » is `update_stock`, not `add_grocery_item`:
 * the product's state changes, and a product set to restock by itself goes back
 * on the list in its usual packaging. « Je n'ai plus de légumes pour couscous »
 * changes the state too, adds nothing — no automatic restock on that one — and
 * leaves Maggie a couscous planned this week to warn about.
 *
 * What the journey asserts is the data, never her wording: the fake does not
 * read tool results, so the scripted sentence says « deux paquets » whatever
 * happened. What the tool answers about the meals is `UpdateStockToolTest`'s
 * business; whether a real model passes it on is `task e2e:eval`'s.
 *
 * ## Why a fourth account, and `retries: 0`
 *
 * Both tests write to a grocery list and to the chat history. On the owner's
 * account that pushes `chat.spec.ts` off its twenty-message page; on the
 * shopper's it lands inside the windows `grocery-errand.spec.ts` asserts the
 * *first* Mercure message of. So this file signs in as `e2e-stock@maggie.local`
 * and owns everything that account has — which is why the product names below
 * are plain constants: the scenarios match « Riz » and « Légumes pour couscous »
 * by name, and a retry would start on the stock the first attempt left.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

const RICE = 'Riz'
const VEGETABLES = 'Légumes pour couscous'

interface ProductRow {
  id: string
  name: string
  stockState?: string
  restockQuantity?: number | null
  autoRestock?: boolean
}

interface Line {
  id: string
  label: string
  quantity: number | null
  unit?: string | null
  checked: boolean
  source: string
}

interface StoredList {
  items: Line[]
}

async function items(api: APIRequestContext): Promise<Line[]> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  return list?.items ?? []
}

async function stockOf(api: APIRequestContext, name: string): Promise<string | undefined> {
  return (await getCollection<ProductRow>(api, '/api/products?itemsPerPage=200')).find((row) => row.name === name)
    ?.stockState
}

test.beforeEach(async ({ stockUser }) => {
  const { api } = stockUser

  const known = await getCollection<ProductRow>(api, '/api/products?itemsPerPage=200')
  if (known.some((row) => RICE === row.name || VEGETABLES === row.name)) {
    return
  }

  // Given « Riz », in stock: a 500 g pack, two packs when it runs low, by itself.
  const rice = await api.post('/api/products', {
    headers: LD,
    data: {
      name: RICE,
      category: 'grain',
      packagingUnit: 'pack',
      packagingSize: 500,
      packagingSizeUnit: 'g',
      restockQuantity: 2,
      autoRestock: true,
    },
  })
  expect(rice.status(), `the API refused the rice: ${await rice.text()}`).toBe(201)

  // And « Légumes pour couscous », in stock: a jar, nothing set to come back by itself.
  const vegetables = await api.post('/api/ingredients', {
    headers: LD,
    data: { name: VEGETABLES, category: 'produce', packagingUnit: 'jar' },
  })
  expect(vegetables.status(), `the API refused the vegetables: ${await vegetables.text()}`).toBe(201)
  const vegetablesId = ((await vegetables.json()) as { id: string }).id

  // And a couscous planned for dinner in the coming days, which needs them.
  const recipe = await api.post('/api/recipes', {
    headers: LD,
    data: {
      name: 'Couscous MAG-294',
      servings: 4,
      ingredients: [{ ingredientId: vegetablesId, quantity: 1, unit: 'jar' }],
    },
  })
  expect(recipe.status(), `the API refused the recipe: ${await recipe.text()}`).toBe(201)
  const recipeIri = ((await recipe.json()) as { '@id': string })['@id']

  const meal = await api.post('/api/meals', {
    headers: LD,
    data: { summary: 'Couscous du vendredi', date: seedDate(2), slot: 'dinner', recipes: [recipeIri] },
  })
  expect(meal.status(), `the API refused the meal: ${await meal.text()}`).toBe(201)

  await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => RICE === row.name, {
    what: 'The rice',
  })
  await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => VEGETABLES === row.name, {
    what: 'The couscous vegetables',
  })
})

test('« je n\'ai presque plus de riz » puts the rice at low and two packs on the list', async ({ stockUser }) => {
  const { api, page } = stockUser

  const grocery = new GroceryListPage(page)
  await grocery.open()
  await expect(grocery.line(RICE), 'the rice is on the list before anyone said it was running out').toHaveCount(0)

  const chat = new ChatPanel(page)
  const events = await chat.send("Je n'ai presque plus de riz")

  // update_stock, and not add_grocery_item: the scenario is what pins the tool,
  // its outcome is what says the call went through.
  expect(calledTools(events)).toContain('update_stock')
  expect(calledTools(events)).not.toContain('add_grocery_item')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'update_stock', status: 'success' })]),
  )

  // Then Riz is « low »…
  await waitForIndexed<ProductRow>(
    api,
    '/api/products?itemsPerPage=200',
    (row) => RICE === row.name && 'low' === row.stockState,
    { what: 'The rice, nearly out' },
  )

  // …and the list holds « Riz — 2 paquets », filed as a restock.
  await expect
    .poll(async () => (await items(api)).find((line) => RICE === line.label), {
      message: 'the restock line never reached the list',
      timeout: 30_000,
    })
    .toMatchObject({ quantity: 2, unit: 'pack', source: 'restock', checked: false })
  expect((await items(api)).filter((line) => RICE === line.label)).toHaveLength(1)

  // The open list shows it without a reload: the restock was published.
  await expect(grocery.line(RICE)).toBeVisible()
})

test('« je n\'ai plus de légumes pour couscous » marks them out, adds nothing, and the couscous is flagged', async ({
  stockUser,
}) => {
  const { api, page } = stockUser

  // The meal brought its own ingredients onto the list when it was planned
  // (that is MAG-116, not this ticket): what counts is that Maggie adds nothing
  // more, so the list is read before and after.
  const before = await items(api)
  expect(before.filter((line) => 'restock' === line.source).map((line) => line.label)).not.toContain(VEGETABLES)

  const grocery = new GroceryListPage(page)
  await grocery.open()

  const chat = new ChatPanel(page)
  const events = await chat.send("Je n'ai plus de légumes pour couscous")

  expect(calledTools(events)).toContain('update_stock')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'update_stock', status: 'success' })]),
  )

  // Then the product is « out »…
  await waitForIndexed<ProductRow>(
    api,
    '/api/products?itemsPerPage=200',
    (row) => VEGETABLES === row.name && 'out' === row.stockState,
    { what: 'The couscous vegetables, out' },
  )
  expect(await stockOf(api, RICE), 'the other product moved with it').toBe('low')

  // …nothing was added: no restock line for them, and the lines already there
  // keep their quantity.
  const after = await items(api)
  expect(after.filter((line) => 'restock' === line.source).map((line) => line.label)).not.toContain(VEGETABLES)
  for (const line of before) {
    expect(after.find((candidate) => line.id === candidate.id)?.quantity, `${line.label} changed`).toBe(line.quantity)
  }

  // And she warns about the couscous planned this week (scripted; the data
  // behind the warning is UpdateStockToolTest's).
  expect(assistantText(events)).toContain('couscous')
})
