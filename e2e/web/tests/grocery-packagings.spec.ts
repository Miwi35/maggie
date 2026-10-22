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
 * time: the add dialog offers « 2 paquets » (the product's automatic restock),
 * a quantity typed in grams is turned into packs, and the typed amount stays on
 * the line as a reminder. Telling Maggie « ajoute 2 paquets de pâtes » is the
 * same thing through `add_grocery_item(packagings: 2)`.
 *
 * Signed in as the stock account, which owns its data (see grocery-stock-chat):
 * the product is created here and the lines are cleaned up at the end of each
 * test, so a retry starts clean — hence `retries: 0` like its neighbour.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const PASTA = 'Pâtes sèches'

interface ProductRow {
  name: string
}
interface Line {
  id: string
  label: string
  quantity: number | null
  unit?: string | null
  needed?: Array<{ quantity: number; unit: string }> | null
}
interface StoredList {
  items: Line[]
}

async function lineOf(api: APIRequestContext, label: string): Promise<Line | undefined> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  return list?.items.find((line) => label === line.label)
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
    await expect(entry.getByTestId('prefilled-chip')).toHaveText('prérempli · recharge auto')
    await expect(entry.getByTestId('quantity-conversion')).toHaveText('= 1 kg')

    // Switching to a quantity: 300 g is one pack to buy.
    await entry.getByRole('button', { name: 'Quantité', exact: true }).click()
    await entry.getByLabel('Quantité', { exact: true }).fill('300')
    await entry.getByLabel('Unité').click()
    await page.getByRole('option', { name: 'g', exact: true }).click()
    await expect(entry.getByTestId('quantity-conversion')).toHaveText('= 1 paquet de 500 g à acheter')

    await grocery.addDialog.getByRole('button', { name: 'Ajouter' }).click()
    await expect(grocery.addDialog).toBeHidden()

    await expect
      .poll(async () => lineOf(api, PASTA), { message: 'the pasta never reached the list', timeout: 30_000 })
      .toMatchObject({ quantity: 1, unit: 'pack', needed: [{ quantity: 300, unit: 'g' }] })

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
