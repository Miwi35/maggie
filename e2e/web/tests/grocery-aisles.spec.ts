import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext, Locator, Page } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * A store's aisles, in the order of the way through it, and the grocery list
 * that follows them (MAG-493).
 *
 * The owner walks through Carrefour fruit first, then the dairy, then the
 * bakery; the list has to read in that order whatever order the lines were
 * added in, and when the store is rearranged — an aisle dragged on the stores
 * page — the list follows without anything else to do.
 *
 * Written on the owner's list: nothing makes a publication or a count claim on
 * it (see `cookbook-isolation.spec.ts`). Everything is created here, under names
 * no other journey uses, and removed in `finally`.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

interface Created {
  '@id': string
  id: string
}

interface StoredLine {
  id: string
  label: string
  aisle?: { id: string; name: string; position: number } | null
}

async function create(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<Created> {
  const response = await api.post(path, { headers: LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as Created
}

async function linesOnList(api: APIRequestContext, labels: string[]): Promise<StoredLine[]> {
  const [list] = await getCollection<{ items: StoredLine[] }>(api, '/api/grocery_lists')

  return (list?.items ?? []).filter((item) => labels.includes(item.label))
}

/**
 * Drags one row onto another with the mouse, the way the owner does: dnd-kit
 * starts a drag only after a few pixels of movement, then follows the pointer.
 */
async function dragOnto(page: Page, handle: Locator, target: Locator): Promise<void> {
  await target.scrollIntoViewIfNeeded()
  const from = await handle.boundingBox()
  const to = await target.boundingBox()
  if (!from || !to) throw new Error('the drag handle or the drop target is not on screen')

  const startX = from.x + from.width / 2
  const startY = from.y + from.height / 2
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  await page.mouse.move(startX, startY - 10, { steps: 5 })
  await page.mouse.move(startX, to.y + to.height / 4, { steps: 15 })
  await page.mouse.up()
}

test('the grocery list follows the aisles reordered on the stores page — MAG-493, MAG-623', async ({ page, api }) => {
  const stamp = Date.now()
  const storeName = `Carrefour MAG-493 ${stamp}`
  const apples = `Pommes MAG-493 ${stamp}`
  const milk = `Lait MAG-493 ${stamp}`
  const bread = `Pain MAG-493 ${stamp}`
  const labels = [milk, apples, bread]

  // Removed last-created first: the products, then the aisles, then the store.
  const cleanup: string[] = []
  const created = async (path: string, data: Record<string, unknown>) => {
    const row = await create(api, path, data)
    cleanup.unshift(row['@id'])
    return row
  }

  try {
    // --- Given: a store walked fruit, dairy, bakery, and three lines in it ---
    const store = await created('/api/stores', { name: storeName, visitOrder: 0 })
    const fruits = await created('/api/aisles', { store: store['@id'], name: 'Fruits et légumes' })
    const dairy = await created('/api/aisles', { store: store['@id'], name: 'Crèmerie' })
    const bakery = await created('/api/aisles', { store: store['@id'], name: 'Boulangerie' })

    for (const [name, aisle] of [
      [milk, dairy],
      [apples, fruits],
      [bread, bakery],
    ] as const) {
      const product = await created('/api/products', { name, category: 'other' })
      const placed = await api.patch(`/api/products/${product.id}`, {
        headers: MERGE_PATCH,
        data: { aisles: [{ store: store.id, aisle: aisle.id }] },
      })
      expect(placed.status(), `the product was not put in its aisle: ${await placed.text()}`).toBe(200)
    }

    // Added in the order milk, apples, bread: not the order of the way.
    for (const label of labels) {
      const added = await api.post('/api/grocery/add-item', {
        headers: { 'Content-Type': 'application/json' },
        data: { label, storeId: store.id },
      })
      expect(added.status(), `the line was not added: ${await added.text()}`).toBe(200)
    }
    await expect
      .poll(async () => (await linesOnList(api, labels)).filter((line) => line.aisle).length, {
        message: 'the three lines never reached the list with their aisle',
        timeout: 30_000,
      })
      .toBe(3)

    const grocery = new GroceryListPage(page)
    const linesOfStore = grocery.content
      .locator(`[data-testid="grocery-store-group"][data-store="${storeName}"]`)
      .getByTestId('grocery-item')

    await grocery.open()
    await expect(linesOfStore).toHaveText([new RegExp(apples), new RegExp(milk), new RegExp(bread)])

    // --- When: the bakery is dragged to the head of the store's aisles ------
    const shell = new AdminShell(page)
    await expect
      .poll(
        async () => {
          const found = await api.get(`/api/products?store=${store.id}&itemsPerPage=1`, { headers: { Accept: 'application/ld+json' } })
          return ((await found.json()) as { totalItems?: number }).totalItems
        },
        { message: 'the three products never reached the index', timeout: 30_000 },
      )
      .toBe(3)
    await shell.goto(ROUTES.stores)
    const storeRow = shell.content.getByTestId('store-row').filter({ hasText: storeName })
    await expect(storeRow).toContainText('3 rayons · 3 produits')
    await storeRow.click()
    const aisles = shell.content.getByTestId('store-aisles').getByTestId('store-aisle')
    await expect(aisles).toHaveText(['Fruits et légumes', 'Crèmerie', 'Boulangerie'])

    const moved = page.waitForResponse(
      (response) => response.url().includes(`/api/aisles/${bakery.id}`) && response.request().method() === 'PATCH',
    )
    await dragOnto(
      page,
      shell.content.getByRole('button', { name: 'Déplacer Boulangerie' }),
      aisles.filter({ hasText: 'Fruits et légumes' }),
    )
    const response = await moved
    expect(response.status(), `the API refused the move: ${await response.text()}`).toBe(200)
    expect(response.request().postDataJSON()).toEqual({ position: 1 })
    await expect(aisles).toHaveText(['Boulangerie', 'Fruits et légumes', 'Crèmerie'])

    // --- Then: the list reads bakery, fruit, dairy --------------------------
    await expect
      .poll(
        async () =>
          (await linesOnList(api, labels)).find((line) => line.label === bread)?.aisle?.position,
        { message: 'the list never learnt the new place of the bakery', timeout: 30_000 },
      )
      .toBe(1)

    await grocery.open()
    await expect(linesOfStore).toHaveText([new RegExp(bread), new RegExp(apples), new RegExp(milk)])
  } finally {
    for (const line of await linesOnList(api, labels)) await api.delete(`/api/grocery_items/${line.id}`)
    for (const iri of cleanup) await api.delete(iri)
  }
})
