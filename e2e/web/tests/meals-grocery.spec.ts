import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext, Page } from '@playwright/test'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * A meal planned without naming an agenda — MAG-114 § 1.
 *
 * The admin never takes this path: its week view always sends an agenda IRI it
 * picked itself. Asking Maggie is the only way there, and what the API does
 * then is find or create the user's "Repas" agenda.
 *
 * What is **not** here, deliberately: the arithmetic. Quantities merging onto
 * one line rather than piling up, a meal moved or cancelled taking back exactly
 * its own share, a perishable deferred by its shelf life, a recurring item
 * whose fortnight has not run out — all of that is
 * `api/modules/cookbook/tests/MessageHandler/MealGrocerySyncTest.php`, twenty-odd
 * cases of it, on every pull request. Re-asserting a quantity here would be a
 * slower test of the same thing. Recurring items reaching the list at all is a
 * different claim, and it is covered in `grocery-errand.spec.ts`, which owns a
 * grocery list; the owner-visible half of `buyAfter` is `grocery-list.spec.ts`.
 *
 * Run as the second account, like every other journey that talks to Maggie:
 * `GET /agent/messages` is scoped to the user and returns the last twenty, and
 * `chat.spec.ts` depends on that window. The recipes it plans carry **no
 * ingredient**, so `MealGrocerySync` returns null and nothing is written to any
 * grocery list — which keeps this file out of the way of the one that owns one.
 *
 * Serial, because both tests ask the API to find-or-create the *same* "Repas"
 * agenda: run side by side, both could find none and make one, and two agendas
 * of the same name is a state neither test is about. Retries stay on — each
 * attempt plans a recipe of its own, and an agenda already there is exactly
 * what the second test wants.
 */

test.describe.configure({ mode: 'serial' })

interface StoredAgenda {
  '@id': string
  id: string
  name: string
}

interface StoredMeal {
  summary: string
  agenda: unknown
}

interface Planned {
  id: string
  name: string
  day: string
}

/**
 * A recipe with no ingredients, and the day to plan it on.
 *
 * Six weeks out, so it sits in nobody else's week view, and ingredient-free so
 * it touches no grocery list. `what` keeps the two tests in this file apart:
 * they both plan one, and `plannedMeal` finds a meal by a substring of its
 * summary — a name one of them is a prefix of would make either assert on the
 * other's meal, serial or not.
 */
async function aRecipeToPlan(api: APIRequestContext, what: string): Promise<Planned> {
  const { retry } = test.info()
  const name = `Couscous ${what}${0 === retry ? '' : ` essai ${retry}`}`

  const response = await api.post('/api/recipes', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: { name, servings: 2 },
  })

  expect(response.status(), `POST /api/recipes answered ${response.status()}: ${await response.text()}`).toBe(201)

  return { id: ((await response.json()) as { id: string }).id, name, day: seedDate(42) }
}

async function agendas(api: APIRequestContext): Promise<StoredAgenda[]> {
  return getCollection<StoredAgenda>(api, '/api/agendas')
}

async function plannedMeal(api: APIRequestContext, name: string): Promise<StoredMeal | undefined> {
  return (await getCollection<StoredMeal>(api, '/api/meals?itemsPerPage=100')).find((candidate) =>
    String(candidate.summary).includes(name),
  )
}

/**
 * Asks Maggie to plan it. The id is in what the shopper says because a ULID
 * differs on every seed and no fixture can name one — `47-meal-plan.yaml`
 * captures it and carries it into the real `manage_meals` call.
 */
async function askMaggieToPlan(page: Page, recipe: Planned): Promise<void> {
  const shell = new AdminShell(page)
  await shell.goto(ROUTES.calendar)
  await shell.expectLoaded()

  const chat = new ChatPanel(page)
  const events = await chat.send(`Planifie la recette ${recipe.id} pour le dîner du ${recipe.day}`)

  expect(calledTools(events), 'manage_meals never ran — did the scenario stop matching?').toContain('manage_meals')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'manage_meals', status: 'success' })]),
  )
}

test('Maggie plans a meal, and the API gives it an agenda nobody named', async ({ otherUser }) => {
  // The unmarked half, and the pair the expectation below needs: it drives the
  // same setup — the scenario matching, `manage_meals` running, the meal being
  // written — so a scenario that stopped matching cannot hide behind a marker
  // (`e2e/web/README.md`).
  const { api } = otherUser
  const recipe = await aRecipeToPlan(api, 'MAG-101')

  await askMaggieToPlan(otherUser.page, recipe)

  await expect
    .poll(async () => undefined !== (await plannedMeal(api, recipe.name)), {
      message: 'the meal Maggie planned never became findable',
      timeout: 30_000,
    })
    .toBe(true)

  const meal = await plannedMeal(api, recipe.name)
  // An agenda, whichever it is. Which one it is, and whether the owner can see
  // it, is the expectation below.
  expect(JSON.stringify(meal?.agenda), 'the meal was written without an agenda').toContain('/api/agendas/')
})

/**
 * The half that is still broken — expected to fail, naming MAG-176.
 *
 * The "Repas" agenda is persisted now, and
 * `UserIsolationToolsTest::testFirstMealCreatesTheRepasAgendaForTheCurrentUser`
 * keeps it that way. But `/api/agendas` is served from Elasticsearch, and the
 * index is fed by an `IndexDocumentCommand` the middleware dispatches from the
 * handler's *return value* — which is the meal. The agenda is a side effect: it
 * is neither published nor indexed, so the owner reloads and it is not in their
 * sidebar, and `MealsWeekView` goes on planning into the first agenda it finds
 * for ever.
 *
 * Exactly the shape `GroceryListBroadcaster` exists to fix on the grocery side.
 *
 * The journey ends on a reload, because that is the owner's own wording in
 * MAG-114: "l'agenda Repas est créé, **existe toujours après un rechargement**".
 */
test.fail('the Repas agenda is in the sidebar after a reload — MAG-176', async ({ otherUser }) => {
  const { api } = otherUser
  const recipe = await aRecipeToPlan(api, 'MAG-176')

  await askMaggieToPlan(otherUser.page, recipe)

  await expect
    .poll(async () => (await agendas(api)).some((agenda) => agenda.name === 'Repas'), {
      message: 'the Repas agenda the API created is not in the agendas collection',
      timeout: 30_000,
    })
    .toBe(true)

  // On the screen that shows them, after a fresh load — not through the API
  // again.
  const calendar = new CalendarPage(otherUser.page)
  await calendar.open()
  await expect(calendar.agendaRow('Repas')).toBeVisible()
})
