import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The products screen by store and aisle, and the aisle under the store on the
 * product sheet (MAG-604).
 *
 * Filtering Super U › Frais keeps the one product on that shelf; the sheet of
 * another product takes Biocoop as its fallback store and a new aisle there,
 * and the grocery list then files the line under that aisle at Biocoop.
 *
 * Written on the owner's data: everything is created here, under names no other
 * journey uses, and removed in `finally`.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

interface Created {
  '@id': string
  id: string
}

async function create(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<Created> {
  const response = await api.post(path, { headers: LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as Created
}

test('filter a store and an aisle, then give a product an aisle at its fallback store — MAG-604', async ({
  page,
  api,
}) => {
  const stamp = Date.now()
  const superU = `Super U MAG-604 ${stamp}`
  const biocoop = `Biocoop MAG-604 ${stamp}`
  const yogurt = `Yaourt MAG-604 ${stamp}`
  const pasta = `Pâtes MAG-604 ${stamp}`
  const biscuits = `Biscuits MAG-604 ${stamp}`
  const labels = [biscuits]

  const cleanup: string[] = []
  const created = async (path: string, data: Record<string, unknown>) => {
    const row = await create(api, path, data)
    cleanup.unshift(row['@id'])
    return row
  }
  const shelve = async (product: Created, store: Created, aisle: Created) => {
    const placed = await api.patch(`/api/products/${product.id}`, {
      headers: MERGE_PATCH,
      data: { aisles: [{ store: store.id, aisle: aisle.id }] },
    })
    expect(placed.status(), `the product was not put in its aisle: ${await placed.text()}`).toBe(200)
  }

  try {
    // --- Given: Super U with two aisles, a product in each, and Biocoop ------
    const superUStore = await created('/api/stores', { name: superU, visitOrder: 0 })
    const biocoopStore = await created('/api/stores', { name: biocoop, visitOrder: 1 })
    const fresh = await created('/api/aisles', { store: superUStore['@id'], name: 'Frais' })
    const grocery = await created('/api/aisles', { store: superUStore['@id'], name: 'Épicerie' })

    const yogurtProduct = await created('/api/products', {
      name: yogurt,
      category: 'dairy',
      preferredStore: superUStore['@id'],
    })
    const pastaProduct = await created('/api/products', {
      name: pasta,
      category: 'other',
      preferredStore: superUStore['@id'],
    })
    const biscuitsProduct = await created('/api/products', {
      name: biscuits,
      category: 'other',
      preferredStore: superUStore['@id'],
    })
    await shelve(yogurtProduct, superUStore, fresh)
    await shelve(pastaProduct, superUStore, grocery)

    await expect
      .poll(
        async () => {
          const rows = await getCollection<{ name: string }>(api, `/api/products?store=${superUStore.id}`)
          return rows.length
        },
        { message: 'the three products never reached the index', timeout: 30_000 },
      )
      .toBe(3)

    // --- When: the products screen is filtered on Super U › Frais ------------
    const shell = new AdminShell(page)
    await shell.goto(ROUTES.products)
    await shell.content.getByRole('combobox', { name: /^Magasin/ }).click()
    await page.getByRole('option', { name: superU }).click()

    // Grouped by aisle in the order of the way, « Sans rayon » last.
    const headings = shell.content.getByRole('heading', { level: 3 })
    await expect(headings).toHaveText(['Frais · 1', 'Épicerie · 1', 'Sans rayon · 1'])

    await shell.content.getByRole('combobox', { name: /^Rayon/ }).click()
    await page.getByRole('option', { name: 'Frais' }).click()

    // --- Then: only the product on that shelf is left -----------------------
    await expect(shell.content.getByText(yogurt)).toBeVisible()
    await expect(shell.content.getByText(pasta)).toHaveCount(0)
    await expect(shell.content.getByText(biscuits)).toHaveCount(0)

    // --- When: the sheet of Biscuits takes Biocoop as fallback, in a new aisle ---
    await shell.goto(`${ROUTES.products}/${encodeURIComponent(biscuitsProduct['@id'])}`)

    await page.getByRole('combobox', { name: 'À défaut', exact: true }).fill(biocoop)
    await page.getByRole('option', { name: biocoop }).click()
    await page.getByRole('combobox', { name: 'Rayon du magasin à défaut' }).click()
    await page.getByRole('option', { name: 'Nouveau rayon…' }).click()
    await page.getByLabel('Nom du nouveau rayon').fill('Épicerie')
    await page.getByRole('button', { name: 'Créer le rayon' }).click()
    await expect(page.getByRole('combobox', { name: 'Rayon du magasin à défaut' })).toHaveText('Épicerie')

    const saved = page.waitForResponse(
      (response) => response.url().includes(`/api/products/`) && response.request().method() === 'PATCH',
    )
    await page.getByRole('button', { name: /enregistrer|save/i }).click()
    expect((await saved).status()).toBe(200)

    // --- Then: the grocery list files the line under that aisle at Biocoop ----
    const added = await api.post('/api/grocery/add-item', {
      headers: { 'Content-Type': 'application/json' },
      data: { label: biscuits, storeId: biocoopStore.id },
    })
    expect(added.status(), `the line was not added: ${await added.text()}`).toBe(200)

    const list = new GroceryListPage(page)
    await list.open()
    await expect(
      list.content
        .locator(`[data-testid="grocery-store-group"][data-store="${biocoop}"]`)
        .locator('[data-testid="grocery-aisle"][data-aisle="Épicerie"]')
        .getByTestId('grocery-item'),
    ).toHaveText([new RegExp(biscuits)])
  } finally {
    const [groceryList] = await getCollection<{ items: { id: string; label: string }[] }>(api, '/api/grocery_lists')
    for (const line of (groceryList?.items ?? []).filter((item) => labels.includes(item.label))) {
      await api.delete(`/api/grocery_items/${line.id}`)
    }
    for (const iri of cleanup) await api.delete(iri)
  }
})
