import { test, expect } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

/**
 * A category written elsewhere lands in the open list, under its parent (MAG-503, extends MAG-102).
 *
 * Given « Voiture MAG-503 » on the open category list, When a sub-category is created from
 * another client (the API call stands for the mobile app or Maggie), Then it appears under
 * « Voiture MAG-503 » without reloading the page.
 *
 * As the neighbour, with categories of its own: the owner's categories are not touched.
 */

const PARENT = 'Voiture MAG-503'
const CHILD = 'Assurance auto MAG-503'
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

test('a sub-category created elsewhere appears under its parent in the open list', async ({ otherUser }) => {
  const { api, page } = otherUser

  const remove = async () => {
    const all = await getCollection<{ id?: string; name?: string }>(api, '/api/categories?itemsPerPage=200')
    for (const category of all.filter((candidate) => candidate.name === CHILD)) {
      await api.delete(`/api/categories/${category.id}`)
    }
    for (const category of all.filter((candidate) => candidate.name === PARENT)) {
      await api.delete(`/api/categories/${category.id}`)
    }
  }

  await remove()

  try {
    const parent = await api.post('/api/categories', { headers: LD, data: { name: PARENT, obligation: 'optional' } })
    expect(parent.status(), await parent.text()).toBe(201)
    const parentIri = ((await parent.json()) as { '@id': string })['@id']

    // Given the list open, « Voiture MAG-503 » on it.
    const categories = new FinanceCategoriesPage(page)
    await openSubscribed(page, () => categories.open())
    await expect(categories.row(PARENT)).toBeVisible()

    // When the sub-category is created from another client.
    await expectRealtimeSync(
      page,
      async () => {
        const child = await api.post('/api/categories', {
          headers: LD,
          data: { name: CHILD, obligation: 'optional', parent: parentIri },
        })
        expect(child.status(), await child.text()).toBe(201)
      },
      async () => {
        // Then it is listed under its parent, one level down.
        await expect
          .poll(async () =>
            (await categories.tree()).filter((row) => row.name === PARENT || row.name === CHILD),
          )
          .toEqual([
            { name: PARENT, depth: 1 },
            { name: CHILD, depth: 2 },
          ])
      },
    )
  } finally {
    await remove()
  }
})
