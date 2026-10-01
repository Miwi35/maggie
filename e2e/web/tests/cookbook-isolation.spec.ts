import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'

/**
 * Nothing of the neighbour's cookbook or shopping reaches the owner — MAG-114 § 3.
 *
 * A missing `WHERE user` is invisible with one account: every row belongs to
 * you, so every list looks right. The seed therefore gives the neighbour a
 * recipe tagged like one of the owner's, an ingredient, and a meal in the same
 * week — three rows that would surface in the owner's own cookbook if a query
 * dropped its filter.
 *
 * Every test ends on a **pair** of assertions: the neighbour's row is absent
 * for the owner *and* present for the neighbour. The second half is what stops
 * the first from passing because the fixture was never loaded, which is the way
 * an isolation test usually lies.
 *
 * Two things isolation-shaped are covered elsewhere and not repeated here: the
 * MCP tools tool by tool, in `api/modules/cookbook/tests/Mcp/UserIsolationToolsTest.php`
 * and its grocery twin, and generation never touching the neighbour's list, in
 * `MealGrocerySyncTest::testTheNeighboursListIsNeverTouched`. Driving generation
 * from here would also race the one in `meals-grocery.spec.ts` over the same
 * recurring line.
 *
 * The last test is the one that fails: three grocery **writes** take an item id
 * and never ask whose it is.
 */

const MINE = { recipe: 'Pâtes à la tomate', ingredient: 'Tomate', meal: 'Pâtes à la tomate' }
const THEIRS = { recipe: 'Velouté du voisin', ingredient: 'Poireau du voisin', meal: 'Velouté du voisin' }

/** The tag both recipes carry, so a tag search that forgot its filter returns both. */
const SHARED_TAG = 'végétarien'

interface Named {
  name?: string
  summary?: string
  tags?: string[]
}

async function namesOf(api: APIRequestContext, path: string): Promise<string[]> {
  return (await getCollection<Named>(api, path)).map((row) => String(row.name ?? row.summary ?? ''))
}

test("the recipes collection holds the caller's recipes and no one else's", async ({ api, otherUser }) => {
  const mine = await namesOf(api, '/api/recipes?itemsPerPage=100')
  expect(mine).toContain(MINE.recipe)
  expect(mine, "the neighbour's recipe is in the owner's cookbook").not.toContain(THEIRS.recipe)

  // The control. Without it a filter matching nothing at all would pass the
  // assertion above, and so would a seed that never loaded the neighbour's row.
  const theirs = await namesOf(otherUser.api, '/api/recipes?itemsPerPage=100')
  expect(theirs).toContain(THEIRS.recipe)
  expect(theirs).not.toContain(MINE.recipe)
})

test('the recipes each user can read carry the tag they were seeded with', async ({ api, otherUser }) => {
  // The setup both tag searches rest on, asserted once: the two recipes carry
  // the *same* tag, so a `searchByTags` that dropped its user filter returns
  // the neighbour's soup rather than an empty list — which is the only way
  // that failure is visible at all.
  //
  // The search itself is not driven from here: `searchByTags` is MCP-only and
  // a browser journey cannot read a tool's results (see
  // `recipes-ciqual.spec.ts`). It is covered tool-side by
  // `UserIsolationToolsTest::testSearchRecipesByTagOnlyReturnsTheCallersRecipes`
  // and `RecipeToolsTest::testSearchRecipesByTag`. What this adds is that the
  // fixtures those tests' e2e twin depends on are really shaped that way.
  const mine = await getCollection<Named>(api, '/api/recipes?itemsPerPage=100')
  const theirs = await getCollection<Named>(otherUser.api, '/api/recipes?itemsPerPage=100')

  const taggedMine = mine.filter((recipe) => (recipe.tags ?? []).includes(SHARED_TAG)).map((r) => String(r.name))
  const taggedTheirs = theirs.filter((recipe) => (recipe.tags ?? []).includes(SHARED_TAG)).map((r) => String(r.name))

  expect(taggedMine, 'the seeded tag is gone — both tag searches would prove nothing').toContain(MINE.recipe)
  expect(taggedMine).not.toContain(THEIRS.recipe)
  expect(taggedTheirs).toContain(THEIRS.recipe)
  expect(taggedTheirs).not.toContain(MINE.recipe)
})

test("the ingredients and products collections hold only the caller's", async ({ api, otherUser }) => {
  // `Ingredient` extends `Product` in one table, so the two collections read the
  // same rows through different resources — and a filter can be right on one and
  // missing on the other.
  for (const path of ['/api/ingredients?itemsPerPage=100', '/api/products?itemsPerPage=100']) {
    const mine = await namesOf(api, path)
    expect(mine, `${path} is empty for the owner`).toContain(MINE.ingredient)
    expect(mine, `the neighbour's leek is in ${path}`).not.toContain(THEIRS.ingredient)

    const theirs = await namesOf(otherUser.api, path)
    expect(theirs).toContain(THEIRS.ingredient)
    expect(theirs).not.toContain(MINE.ingredient)
  }
})

test("the meals collection holds the caller's meals and no one else's", async ({ api, otherUser }) => {
  // The neighbour's meal sits in the same week as the owner's, which is what
  // makes this worth asserting: a date-range read that forgot its user would
  // return both and put the neighbour's leek on the owner's shopping.
  const mine = await namesOf(api, '/api/meals?itemsPerPage=100')
  expect(mine.some((summary) => summary.includes(MINE.meal))).toBe(true)
  expect(mine.some((summary) => summary.includes(THEIRS.meal))).toBe(false)

  const theirs = await namesOf(otherUser.api, '/api/meals?itemsPerPage=100')
  expect(theirs.some((summary) => summary.includes(THEIRS.meal))).toBe(true)
  expect(theirs.some((summary) => summary.includes(MINE.meal))).toBe(false)
})

test("the global search answers out of the caller's own index", async ({ api, otherUser }) => {
  // Its own code path: the collections filter in SQL or on an Elasticsearch
  // `userId` term, and global search is a third query over several indices at
  // once. A filter can be right in both of the others and missing here.
  const hits = async (client: APIRequestContext): Promise<string> => {
    // `limit=100`, not the endpoint's default of 10: the neighbour owns nine
    // rows with "voisin" in them now, and a flat cross-index top ten would
    // start dropping the recipe this looks for the moment one more is seeded —
    // reading as isolation when it is truncation.
    const response = await client.get('/api/search?q=voisin&limit=100', {
      headers: { Accept: 'application/json' },
    })

    expect(response.status(), `GET /api/search answered ${response.status()}`).toBe(200)

    return response.text()
  }

  expect(await hits(api), "the neighbour's soup is in the owner's search results").not.toContain(THEIRS.recipe)
  // The control: the same search, run by its owner, does find it — so the
  // absence above is a filter and not a stale index.
  expect(await hits(otherUser.api), 'the neighbour cannot find their own recipe — is the index built?').toContain(
    THEIRS.recipe,
  )
})

/**
 * Three grocery writes act on an id without asking whose it is — MAG-175.
 *
 * `CheckGroceryItemHandler` and `RemoveGroceryItemHandler` both do
 * `em->find(GroceryItem, $id)` and then write, with no owner check at all;
 * `ReorderGroceryItemsHandler` loads the caller's own list and then moves
 * whatever id it is handed. `EditGroceryItemHandler`, in the same directory,
 * refuses with "Access denied." — so this is an omission, not a decision.
 *
 * The ids are not secret either: a grocery list's Mercure payload carries the
 * `id` of every line.
 *
 * So an authenticated user can tick, reorder, or **permanently delete** a line
 * from somebody else's shopping. Expected to fail, and written as the fix's
 * reproduction, with the control beside it.
 *
 * One thing the fix will trip over, and it is worth knowing before starting:
 * `CheckGroceryItemController` catches `\DomainException` only, while its
 * sibling `RemoveGroceryItemController` also catches `HandlerFailedException`.
 * On the synchronous bus a handler-thrown `DomainException` arrives wrapped, so
 * an owner check added in `CheckGroceryItemHandler` answers **500** rather than
 * the 404 asserted here. MAG-175 says so.
 */
interface Line {
  '@id': string
  id: string
  label: string
  checked: boolean
}

interface StoredList {
  items: Line[]
}

/** A label this attempt alone writes, on the owner's list and nowhere else. */
function attackedLabel(): string {
  const { retry } = test.info()

  return 0 === retry ? 'Pâtisson MAG-175' : `Pâtisson MAG-175 essai ${retry}`
}

async function lineOn(client: APIRequestContext, label: string): Promise<Line | undefined> {
  const [list] = await getCollection<StoredList>(client, '/api/grocery_lists')

  return list?.items.find((item) => item.label === label)
}

/**
 * Writes the line under attack, on the **owner's** list.
 *
 * The owner's and not the neighbour's, deliberately: `grocery-errand.spec.ts`
 * owns the neighbour's list outright — it is the only way that file can prove a
 * write published — and a second writer there would put a hole in it. Nothing
 * makes a publication claim on the owner's list, so a line here costs nothing.
 *
 * The direction of the attack is therefore reversed from the obvious one: the
 * neighbour is the attacker and the owner the victim. It proves the same thing.
 */
async function writeLineToAttack(api: APIRequestContext, label: string): Promise<Line> {
  const added = await api.post('/api/grocery/add-item', {
    headers: { 'Content-Type': 'application/json' },
    data: { label, quantity: 1 },
  })
  expect(added.status(), `the owner could not add a line: ${await added.text()}`).toBe(200)

  await expect
    .poll(async () => undefined !== (await lineOn(api, label)), {
      message: 'the line under attack never became findable',
      timeout: 30_000,
    })
    .toBe(true)

  const line = await lineOn(api, label)
  expect(line).toBeDefined()

  return line as Line
}

test.fail("the neighbour cannot touch a line on the owner's list — MAG-175", async ({ api, otherUser }) => {
  const label = attackedLabel()
  const line = await writeLineToAttack(api, label)

  // The neighbour's token, the owner's id. 404 rather than 403: the API must
  // not confirm that the id exists.
  const ticked = await otherUser.api.patch(`/api/grocery_items/${line.id}`, {
    headers: { 'Content-Type': 'application/json' },
    data: { checked: true },
  })
  expect(ticked.status(), "ticking another user's grocery line was accepted").toBe(404)

  const deleted = await otherUser.api.delete(`/api/grocery_items/${line.id}`)
  expect(deleted.status(), "deleting another user's grocery line was accepted").toBe(404)

  // And the line is exactly as its owner left it — the assertion that matters,
  // because a refusal that deleted the row anyway would still answer 404.
  const after = await lineOn(api, label)
  expect(after, "the owner's line was deleted by somebody else").toBeDefined()
  expect(after?.checked, "the owner's line was ticked by somebody else").toBe(false)
})

test('the owner can still tick and drop a line of their own', async ({ api }) => {
  // The control, and the pair the expectation above needs twice over. It drives
  // the same setup unmarked, so a `POST /api/grocery/add-item` that stopped
  // working could not hide behind the marker — and it is what stops the fix
  // from being "answer 404 to everybody", which would satisfy MAG-175's
  // assertions perfectly while breaking the feature.
  const label = `${attackedLabel()} (le mien)`
  const line = await writeLineToAttack(api, label)

  const ticked = await api.patch(`/api/grocery_items/${line.id}`, {
    headers: { 'Content-Type': 'application/json' },
    data: { checked: true },
  })
  expect(ticked.ok(), `the owner could not tick their own line: ${await ticked.text()}`).toBe(true)

  await expect
    .poll(async () => (await lineOn(api, label))?.checked, { message: 'the ticked line', timeout: 30_000 })
    .toBe(true)

  const deleted = await api.delete(`/api/grocery_items/${line.id}`)
  expect(deleted.ok(), `the owner could not delete their own line: ${await deleted.text()}`).toBe(true)

  await expect
    .poll(async () => undefined === (await lineOn(api, label)), { message: 'the deleted line', timeout: 30_000 })
    .toBe(true)
})
