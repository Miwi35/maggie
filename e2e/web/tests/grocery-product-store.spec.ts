import { test, expect, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * A product's usual shop: set it, change it, clear it, and read it back.
 *
 * "Everything seemed to save, then the shop disappeared" (MAG-190). The write
 * path dropped the stores on the way to the database, and the read path —
 * collections and items are rebuilt from the Elasticsearch document — did not
 * know how to rebuild them. Only a real index sees the second half, which is why
 * this is a journey and not another unit test.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

type ProductRow = {
  id: string
  name: string
  preferredStore?: string | null
  fallbackStore?: string | null
  shelfLifeDays?: number | null
}

test('the usual shop of a product is saved, changed, cleared and read back from the index', async ({ api }) => {
  const name = `Câpres MAG-190 ${Date.now()}`
  const greengrocer = `/api/stores/${seedId('e2e_store_greengrocer')}`
  const supermarket = `/api/stores/${seedId('e2e_store_supermarket')}`

  const created = await api.post('/api/products', {
    headers: LD,
    data: { name, category: 'other', preferredStore: greengrocer, shelfLifeDays: 30 },
  })
  expect(created.status()).toBe(201)
  const productId = ((await created.json()) as { id: string }).id

  const find = (match: (row: ProductRow) => boolean, what: string) =>
    waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => row.name === name && match(row), { what })

  try {
    const saved = await find((row) => row.preferredStore === greengrocer, 'The product with its usual shop')
    expect(saved.shelfLifeDays, 'the shelf life was lost on the way').toBe(30)

    const changed = await api.patch(`/api/products/${productId}`, {
      headers: MERGE_PATCH,
      data: { preferredStore: supermarket, fallbackStore: greengrocer },
    })
    expect(changed.status()).toBe(200)
    const moved = await find(
      (row) => row.preferredStore === supermarket && row.fallbackStore === greengrocer,
      'The product with its new shops',
    )
    expect(moved.shelfLifeDays, 'a field left out of the payload must be left alone').toBe(30)

    const item = await api.get(`/api/products/${productId}`, { headers: { Accept: 'application/ld+json' } })
    expect(((await item.json()) as ProductRow).preferredStore, 'the item read lost the shop').toBe(supermarket)

    const cleared = await api.patch(`/api/products/${productId}`, {
      headers: MERGE_PATCH,
      data: { preferredStore: null },
    })
    expect(cleared.status()).toBe(200)
    const without = await find((row) => !row.preferredStore, 'The product without its usual shop')
    expect(without.fallbackStore, 'clearing one shop must not clear the other').toBe(greengrocer)
  } finally {
    await api.delete(`/api/products/${productId}`)
  }
})

/**
 * MAG-190, second refusal: through the admin form the shop did not stick. The
 * form sends the record back with `id` set to its IRI (and `originId`), the API
 * parsed that as a Ulid and answered 400, so the saved shop never reached the
 * database. The journey above talks to the API directly and cannot see it.
 */
test('the usual shop chosen in the product form is saved and still there when the product is reopened', async ({
  page,
  api,
}) => {
  const name = `Câpres MAG-190 form ${Date.now()}`
  const created = await api.post('/api/products', { headers: LD, data: { name, category: 'other' } })
  expect(created.status()).toBe(201)
  const iri = ((await created.json()) as { '@id': string })['@id']

  try {
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.products}/${encodeURIComponent(iri)}`)

    const shop = shell.content.getByLabel('Magasin habituel')
    await shop.fill('Primeur')
    await page.getByRole('option', { name: 'Primeur du marché' }).click()

    const patched = page.waitForResponse(
      (response) => response.url().includes(iri) && response.request().method() === 'PATCH',
    )
    await shell.content.getByRole('button', { name: 'Enregistrer' }).click()
    const response = await patched
    expect(response.status(), `the API refused the product: ${await response.text()}`).toBe(200)

    // Items are served from the index: reopening before the worker has
    // reindexed would show the previous document.
    const greengrocer = `/api/stores/${seedId('e2e_store_greengrocer')}`
    await waitForIndexed<ProductRow>(
      api,
      '/api/products?itemsPerPage=200',
      (row) => row.name === name && row.preferredStore === greengrocer,
      { what: 'The product with the shop chosen in the form' },
    )

    await shell.goto(`${ROUTES.products}/${encodeURIComponent(iri)}`)
    await expect(shell.content.getByLabel('Magasin habituel')).toHaveValue('Primeur du marché')
    const stored = await api.get(iri, { headers: { Accept: 'application/ld+json' } })
    expect(((await stored.json()) as ProductRow).preferredStore).toBe(greengrocer)
  } finally {
    await api.delete(iri)
  }
})
