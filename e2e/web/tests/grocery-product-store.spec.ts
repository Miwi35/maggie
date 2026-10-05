import { test, expect, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'

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
