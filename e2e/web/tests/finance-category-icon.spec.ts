import { test, expect } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

/**
 * A category's icon is picked from a grid, not typed (MAG-474, extends MAG-102).
 *
 * Given a new category with no icon, When I open the picker and type « courses », Then only
 * the Courses icon is left; When I choose it and save, Then the list shows the pastille with
 * the cart and the API holds the icon's name.
 *
 * As the neighbour, with a category of its own: the owner's categories are not touched.
 */

const NAME = 'Icône MAG-474'

test('typing « courses » in the icon picker leaves the cart, and the list shows it once saved', async ({
  otherUser,
}) => {
  const { api, page } = otherUser

  const stale = await getCollection<{ id?: string; name?: string }>(api, '/api/categories?itemsPerPage=200')
  for (const category of stale.filter((candidate) => candidate.name === NAME)) {
    await api.delete(`/api/categories/${category.id}`)
  }

  try {
    const categories = new FinanceCategoriesPage(page)
    await categories.open()
    await categories.createCategory.click()
    await categories.content.getByLabel('Nom').fill(NAME)

    // When I open the picker: no icon yet, and nothing to type by heart.
    await categories.content.getByRole('button', { name: 'Icône : Aucune' }).click()
    const dialog = page.getByRole('dialog', { name: 'Choisir une icône' })
    await expect(dialog.getByRole('button', { name: 'Voiture' })).toBeVisible()

    // And type « courses ».
    await dialog.getByLabel('Rechercher une icône').fill('courses')

    // Then only the Courses icon is left.
    await expect(dialog.getByRole('button', { name: 'Courses' })).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Voiture' })).toHaveCount(0)

    // When I choose it and save.
    await dialog.getByRole('button', { name: 'Courses' }).click()
    await expect(dialog).toBeHidden()
    await expect(categories.content.getByRole('button', { name: 'Icône : Courses' })).toBeVisible()
    await categories.content.getByRole('button', { name: 'Enregistrer' }).click()
    await page.waitForURL((url) => !url.hash.includes('/create'))

    // Then the API holds the name of the icon…
    const stored = await waitForIndexed<{ id?: string; name?: string; icon?: string }>(
      api,
      '/api/categories',
      (c) => c.name === NAME,
      { what: `The category ${NAME}` },
    )
    expect(stored.icon).toBe('shopping-cart')

    // …and the list shows the pastille with the cart.
    await categories.open()
    await expect(categories.row(NAME).getByTestId('category-glyph')).toHaveAttribute('data-icon', 'shopping-cart')
  } finally {
    const created = await getCollection<{ id?: string; name?: string }>(api, '/api/categories?itemsPerPage=200')
    for (const category of created.filter((candidate) => candidate.name === NAME)) {
      await api.delete(`/api/categories/${category.id}`)
    }
  }
})
