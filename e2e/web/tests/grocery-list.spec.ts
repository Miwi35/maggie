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
 * A line deferred by `buyAfter` is not today's shopping.
 *
 * `buyAfter` is not something the owner sets by hand: `MealGrocerySync` puts it
 * there from an ingredient's shelf life, so a meal planned ten days out defers
 * its perishables on its own. The three clients then disagree about what that
 * means:
 *
 *   `get_grocery_list`  hides it unless asked for `includeDeferred`, says so in
 *                       its own description, and now has tests for both
 *                       (`GroceryToolsTest`).
 *   mobile              filters it out in `GroceryViewModel`.
 *   the admin           declares `buyAfter` on its `GroceryItem` type and then
 *                       never reads it, so the line is on the list.
 *
 * Two clients out of three agree, the API tool agrees with them, and the
 * owner's own seed comment says the line "must stay out of today's list" — so
 * the admin is the one that is wrong. Expected to fail, naming its ticket
 * rather than quietly asserting the behaviour the owner does not want: the
 * journey is the fix's reproduction, already written.
 *
 * Paired, as `e2e/web/README.md` requires: the test below it drives the same
 * setup — opening the list and finding it loaded — without a marker, so a
 * screen that failed to load at all cannot hide behind this one.
 */
test.fail('a line deferred to next week is not on today list — MAG-174', async ({ page }) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(
    grocery.line('Liquide vaisselle'),
    'the admin shows a line it is told to buy in five days',
  ).toHaveCount(0)
})

test("the owner's list loads, with the lines the seed put on it", async ({ page }) => {
  // The pair for the expectation above: it is the setup, unmarked, so "the
  // deferred line is absent" can never be satisfied by an empty screen.
  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.line('Pâtes complètes')).toBeVisible()
  await expect(grocery.line('Pile LR03')).toBeVisible()
})
