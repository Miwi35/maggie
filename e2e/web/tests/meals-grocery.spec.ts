import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * From the week's menu to the shopping — the two halves of MAG-101 that have no
 * screen of their own.
 *
 * What is **not** here, deliberately: the arithmetic. Quantities merging onto
 * one line rather than piling up, a meal moved or cancelled taking back exactly
 * its own share, a perishable deferred by its shelf life, a recurring item
 * whose fortnight has not run out — all of that is
 * `api/modules/cookbook/tests/MessageHandler/MealGrocerySyncTest.php`, twenty-odd
 * cases of it, on every pull request. Re-asserting a quantity here, over a
 * grocery list the whole suite shares and writes to in parallel, would be a
 * slower test of the same thing and a flake waiting for a busy runner.
 *
 * What is here is the chain only a journey can see:
 *
 *   recurring items  reach the list through `generate_grocery_list` and nothing
 *                    else — no cron, no screen, no button. Asking Maggie is the
 *                    only path, and `Generate` is one of the commands `afc1a70`
 *                    left publishing nothing.
 *   the Repas agenda a meal planned without naming an agenda makes the API
 *                    create one (MAG-114 § 1). It is persisted now; it is still
 *                    invisible, which is MAG-176.
 *
 * `recipes.spec.ts` owns planning from the week grid and cancelling it; the
 * owner-visible half of `buyAfter` is `grocery-list.spec.ts` (MAG-174).
 */

interface Line {
  id: string
  label: string
  checked: boolean
  source: string
  quantity: number | null
}

interface StoredList {
  items: Line[]
}

interface StoredAgenda {
  '@id': string
  id: string
  name: string
}

interface StoredMeal {
  summary: string
  agenda: unknown
}

const MILK = 'Lait demi-écrémé'

/** A name the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

async function lines(api: APIRequestContext): Promise<Line[]> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  expect(list, 'the signed-in user has no grocery list — did the seed run?').toBeDefined()

  return list.items
}

/** The unchecked lines for a label. Unchecked, because the seed leaves a bought one behind. */
async function waiting(api: APIRequestContext, label: string): Promise<Line[]> {
  return (await lines(api)).filter((line) => line.label === label && !line.checked)
}

test('asking Maggie to prepare the week puts the recurring items on the list, once', async ({ page, api }) => {
  // A window six weeks out, with no meal in it: the only thing generation can
  // add there is what comes back on its own, which is what this test is about.
  // A window around the anchor would also sync the seeded dinner, and the basil
  // it would add is a line `chat.spec.ts` asserts is *absent* when it starts.
  const from = seedDate(40)
  const to = seedDate(41)

  const grocery = new GroceryListPage(page)
  await grocery.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(`Prépare les courses du ${from} au ${to}`)

  expect(calledTools(events)).toContain('generate_grocery_list')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'generate_grocery_list', status: 'success' })]),
  )

  // The weekly milk, which the seed never put on the list unbought: its own
  // line is checked, left there so ending an errand has both states to sort
  // out. So an *unchecked* milk line is the one generation just added.
  await expect
    .poll(async () => (await waiting(api, MILK)).length, {
      message: 'the weekly recurring item never reached the list',
      timeout: 30_000,
    })
    .toBe(1)

  const [added] = await waiting(api, MILK)
  expect(added.source, 'the line does not say where it came from').toBe('recurring')
  expect(added.quantity).toBe(2)

  // Asked twice, and the answer is the same list: generation is a top-up, not
  // an append. It used to double the shopping (MAG-116), and a shopper who asks
  // Maggie again because she was slow to answer is the normal case.
  const again = await chat.send(`Prépare les courses du ${from} au ${to}`)
  expect(calledTools(again)).toContain('generate_grocery_list')

  await expect
    .poll(async () => (await waiting(api, MILK)).length, {
      message: 'generating twice doubled the shopping',
      timeout: 30_000,
    })
    .toBe(1)

  // And it reaches the screen the owner looks at.
  await grocery.expectItemEventually(MILK)
})

/**
 * MAG-114 § 1, the half that is still broken — expected to fail, naming MAG-176.
 *
 * A meal planned without an agenda makes `CreateMealHandler` find or create the
 * user's "Repas" agenda. It is persisted now, and
 * `UserIsolationToolsTest::testFirstMealCreatesTheRepasAgendaForTheCurrentUser`
 * keeps it that way. But `/api/agendas` is served from Elasticsearch, and the
 * index is fed by an `IndexDocumentCommand` the middleware dispatches from the
 * handler's *return value* — which is the meal. The agenda is a side effect: it
 * is neither published nor indexed, so the owner reloads and it is not in their
 * sidebar, and `MealsWeekView` goes on planning into "Perso" for ever.
 *
 * Exactly the shape `GroceryListBroadcaster` exists to fix on the grocery side.
 *
 * The journey ends on a reload, because that is the owner's own words in the
 * ticket: "l'agenda Repas est créé, **existe toujours après un rechargement**".
 */
test.fail('a meal Maggie plans lands in a Repas agenda that survives a reload — MAG-176', async ({ page, api }) => {
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const day = seedDate(42)

  // A recipe of this journey's own, with an ingredient of its own: the line it
  // puts on the shared grocery list is then nobody else's.
  const ingredient = await api.post('/api/ingredients', {
    headers,
    data: { name: perAttempt('Semoule MAG-101'), category: 'grain' },
  })
  expect(ingredient.status()).toBe(201)
  const ingredientIri = ((await ingredient.json()) as { '@id': string })['@id']

  const recipe = await api.post('/api/recipes', {
    headers,
    data: {
      name: perAttempt('Couscous MAG-101'),
      servings: 2,
      ingredients: [{ ingredient: ingredientIri, quantity: 250, unit: 'g' }],
    },
  })
  expect(recipe.status()).toBe(201)
  const recipeId = ((await recipe.json()) as { id: string }).id

  const shell = new AdminShell(page)
  await shell.goto(ROUTES.calendar)
  await shell.expectLoaded()

  // The id is in what the owner says because a ULID differs on every seed and
  // no fixture can name one — `47-meal-plan.yaml` captures it and carries it
  // into the real `manage_meals` call.
  const chat = new ChatPanel(page)
  const events = await chat.send(`Planifie la recette ${recipeId} pour le dîner du ${day}`)

  expect(calledTools(events)).toContain('manage_meals')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'manage_meals', status: 'success' })]),
  )

  const findRepas = async (): Promise<StoredAgenda | undefined> =>
    (await getCollection<StoredAgenda>(api, '/api/agendas')).find((agenda) => agenda.name === 'Repas')

  await expect
    .poll(async () => undefined !== (await findRepas()), {
      message: 'the Repas agenda the API created is not in the agendas collection',
      timeout: 30_000,
    })
    .toBe(true)

  const repas = await findRepas()

  // The meal really is in it, and not in whichever agenda came first
  // alphabetically.
  const meal = (await getCollection<StoredMeal>(api, '/api/meals?itemsPerPage=100')).find((candidate) =>
    String(candidate.summary).includes('Couscous MAG-101'),
  )
  expect(meal, 'the meal Maggie planned is not in the collection').toBeDefined()
  expect(JSON.stringify(meal?.agenda)).toContain(String(repas?.id))

  // "Still there after a reload" — the owner's own acceptance criterion, read
  // on the screen that shows their agendas rather than through the API again.
  const calendar = new CalendarPage(page)
  await calendar.open()
  await expect(calendar.agendaRow('Repas')).toBeVisible()
})
