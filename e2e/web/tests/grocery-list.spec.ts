import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Which lines belong on today's list at all.
 *
 * The one grocery assertion that has to be made on the **owner's** list rather
 * than the shopper's, because it is the owner's seed that defers a line five
 * days out. Only « Souvent pris » writes, one line it takes back off at the
 * end, so it costs the rest of the suite nothing — which is also why the aisles
 * are asserted in `grocery-errand.spec.ts` instead, on a list that file owns
 * and can therefore count exactly.
 */

/**
 * A line deferred by `buyAfter` is not today's shopping (MAG-120).
 *
 * `buyAfter` is not something the owner sets by hand: `MealGrocerySync` puts it
 * there from an ingredient's shelf life, so a meal planned ten days out defers
 * its perishables on its own. The admin, the mobile app and `get_grocery_list`
 * all keep such a line off today's list and say so: it waits in « Plus tard »,
 * with its date, and does not count in the `coché/total` figure.
 */
test('a line deferred to next week is not on today list but waits in « Plus tard » — MAG-120', async ({
  page,
}) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.line('Liquide vaisselle')).toHaveCount(0)

  const later = grocery.laterSection
  await expect(later).toContainText('Plus tard (1)')
  await later.getByText('Plus tard').click()
  await expect(later.getByTestId('grocery-later-item').filter({ hasText: 'Liquide vaisselle' })).toBeVisible()
})

test("the owner's list loads, with the lines the seed put on it", async ({ page }) => {
  // The setup of the journey above, on its own, so "the deferred line is
  // absent" can never be satisfied by an empty screen.
  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.line('Pâtes complètes')).toBeVisible()
  await expect(grocery.line('Pile LR03')).toBeVisible()
})

interface Suggestion {
  product: { id: string; name: string }
  everyDays: number
  dueOn: string
}
interface GroceryList {
  items?: Array<{ label?: string }>
}

/**
 * « Tu en prends souvent » (MAG-495): the seed has the owner buy « Café » in
 * three past errands, 14 days apart, the last one 14 days before the anchor —
 * so it is due on the seed's day, and nothing else is. Owner's list, because
 * the past errands are hers; the line it adds is deleted at the end, which
 * brings the suggestion back for a repeat.
 */
test('a product bought every two weeks is suggested in « Souvent pris », and one tap puts it on the list — MAG-495', async ({
  page,
  api,
}) => {
  // The rhythm, from the API: what the banner below has to say, on the seed's day and not the wall clock's.
  const response = await api.get('/api/grocery-suggestions')
  expect(response.status(), await response.text()).toBe(200)
  const due = (await response.json()) as Suggestion[]
  expect(due.map((s) => [s.product.name, s.everyDays, s.dueOn])).toContainEqual(['Café', 14, seedAnchorDate()])

  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.line('Café')).toHaveCount(0)
  await expect(grocery.suggestions).toBeVisible()
  await expect(grocery.suggestion('Café')).toContainText('Café · toutes les 2 sem.')

  try {
    await grocery.addSuggestion('Café')

    await expect(grocery.line('Café')).toBeVisible()
    await expect(grocery.suggestion('Café')).toHaveCount(0)

    // The data, not the screen alone: the line is in the list the API serves.
    await waitForIndexed<GroceryList>(
      api,
      '/api/grocery_lists',
      (list) => (list.items ?? []).some((item) => item.label === 'Café'),
      { what: 'The coffee added from « Souvent pris »' },
    )
    const after = (await (await api.get('/api/grocery-suggestions')).json()) as Suggestion[]
    expect(after.map((s) => s.product.name), 'a product on the list is still suggested').not.toContain('Café')
  } finally {
    await grocery.deleteLineIfPresent('Café')
  }
})
