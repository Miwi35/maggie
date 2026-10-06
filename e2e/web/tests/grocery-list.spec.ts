import { test, expect } from '../fixtures/index.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Which lines belong on today's list at all.
 *
 * The one grocery assertion that has to be made on the **owner's** list rather
 * than the shopper's, because it is the owner's seed that defers a line five
 * days out. Nothing here writes, so it costs the rest of the suite nothing —
 * which is also why the aisles are asserted in `grocery-errand.spec.ts`
 * instead, on a list that file owns and can therefore count exactly.
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
