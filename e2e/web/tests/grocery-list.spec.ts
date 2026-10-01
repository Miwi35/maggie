import { test, expect } from '../fixtures/index.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * The list as the owner reads it: which aisle a line is in, and which lines
 * belong on today's list at all.
 *
 * Read-only on purpose, so it is safe beside the rest of the suite — the whole
 * harness shares one grocery list. The one line this file writes carries a
 * label of its own, and nothing is asserted on a count.
 *
 * `grocery-errand.spec.ts` owns everything that ticks or deletes, and reads
 * nothing this file writes.
 */

const STORES = {
  supermarket: 'Supermarché Leclerc',
  greengrocer: 'Primeur du marché',
  unassigned: 'Non assigné',
}

/** A label the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

test('the list is grouped by shop, in the order the shopper walks them', async ({ page }) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  const order = await grocery.storeOrder()

  expect(order, 'the shops the seed gave visit orders to are missing from the list').toEqual(
    expect.arrayContaining([STORES.supermarket, STORES.greengrocer]),
  )

  // Relative, not an exact list: another worker adding a line in a shop of its
  // own would otherwise fail a test that has nothing to do with it.
  expect(
    order.indexOf(STORES.supermarket),
    `visitOrder is ignored — ${STORES.supermarket} is 1 and ${STORES.greengrocer} is 2`,
  ).toBeLessThan(order.indexOf(STORES.greengrocer))

  // A line nobody gave a shop comes last, whatever the shops are called: the
  // view sorts that group on PHP_INT_MAX rather than on its name.
  expect(order.indexOf(STORES.unassigned), 'the unassigned group is not last').toBe(order.length - 1)
})

test('a line sits in the aisle its product says, and one without a shop sits nowhere', async ({ page }) => {
  // What makes the grouping above worth anything. Asserted on lines nothing
  // else in the suite moves: the seeded pasta (preferred shop, the
  // supermarket), the seeded battery (no shop at all), and one this test
  // writes into a shop by hand. Deliberately *not* on the tomato, the one line
  // the closed-shop journey moves to its fallback.
  const label = perAttempt('Moutarde MAG-101')

  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.line('Pâtes complètes')).toHaveAttribute('data-store', STORES.supermarket)
  // `data-store` is empty on a line with no shop: the group it is drawn under
  // is the view's own label for "nowhere", not a shop the API knows.
  await expect(grocery.line('Pile LR03')).toHaveAttribute('data-store', '')

  await grocery.addItem(label, { quantity: 1, store: STORES.greengrocer })

  await expect(grocery.line(label)).toBeVisible()
  await expect(
    grocery.line(label),
    'the shop chosen in the dialog did not reach the line — storeId is not being sent',
  ).toHaveAttribute('data-store', STORES.greengrocer)
})

/**
 * A line deferred by `buyAfter` is not today's shopping.
 *
 * The seed defers the washing-up liquid five days out, and the three clients
 * disagree about what that means:
 *
 *   `get_grocery_list`  hides it unless asked for `includeDeferred`, and says
 *                       so in its own description.
 *   mobile              filters it out in `GroceryViewModel`.
 *   the admin           declares `buyAfter` on its `GroceryItem` type and then
 *                       never reads it, so the line is on the list.
 *
 * Two clients out of three agree, the API tool agrees with them, and the
 * owner's own seed comment says the line "must stay out of today's list" — so
 * the admin is the one that is wrong. Expected to fail, naming its ticket
 * rather than quietly asserting the behaviour the owner does not want: the
 * journey is the fix's reproduction, already written.
 */
test.fail('a line deferred to next week is not on today list — MAG-174', async ({ page }) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  // The control first: the list is really loaded, so the absence below is an
  // absence and not an empty screen.
  await expect(grocery.line('Pâtes complètes')).toBeVisible()

  await expect(
    grocery.line('Liquide vaisselle'),
    'the admin shows a line it is told to buy in five days',
  ).toHaveCount(0)
})
