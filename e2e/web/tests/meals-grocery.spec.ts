import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext, Page } from '@playwright/test'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * A meal planned without naming an agenda — MAG-114 § 1.
 *
 * No client names an agenda for a meal any more (MAG-324): the API files it in
 * the user's « Repas » module agenda, found by attribute and created on first
 * need. Asking Maggie is the path this file drives.
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
 * A meal puts nothing on the list by itself (MAG-300): the last test below owns
 * that claim, on an account of its own — asking Maggie to prepare the week adds
 * every recurring item due, and the neighbour's weekly rice belongs to
 * `grocery-errand.spec.ts`. The choice itself, and the lines it writes, are
 * `meals-grocery-choice.spec.ts`.
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
  return getCollection<StoredAgenda>(api, '/api/agendas?module=cookbook')
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
 * The "Repas" agenda the first meal creates is indexed and published, so the
 * owner sees it after a reload — MAG-176.
 *
 * `/api/agendas` is served from Elasticsearch, and the index is fed from the
 * handler's *return value* — which is the meal. The agenda used to be a side
 * effect nobody indexed: the owner reloaded and it was not in their sidebar, and
 * `MealsWeekView` went on planning into the first agenda it found for ever.
 *
 * The journey ends on a reload, because that is the owner's own wording in
 * MAG-114: "l'agenda Repas est créé, **existe toujours après un rechargement**".
 */
test('the Repas agenda is in the sidebar after a reload — MAG-176', async ({ otherUser }) => {
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
  // Signed in as the second account, whose own agenda is not called "Perso".
  // The module's agenda is not among the owner's: the filters carry one « Repas » line (MAG-354).
  const calendar = new CalendarPage(otherUser.page, 'Agenda du voisin')
  await calendar.open()
  await expect(calendar.content.getByText('Repas', { exact: true })).toHaveCount(1)
  await expect(calendar.agendaRow('Repas')).toHaveCount(0)
})

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Line {
  label: string
  quantity: number | null
  unit: string | null
  source: string | null
}

interface GroceryListRow {
  items: Line[]
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<{ '@id': string; id: string }> {
  const response = await api.post(path, { headers: JSON_LD, data })
  expect(response.status(), `POST ${path} answered ${response.status()}: ${await response.text()}`).toBe(201)

  return (await response.json()) as { '@id': string; id: string }
}

function linesOf(list: GroceryListRow, label: string): Line[] {
  return list.items.filter((item) => item.label === label)
}

/**
 * Planning a meal adds nothing to the list; the owner chooses — MAG-300, extending MAG-101.
 *
 * Until MAG-300 a meal derived its ingredients onto the list by itself, and
 * asking Maggie to prepare the week added the ones of every meal in it. Both
 * stopped: the list holds what the owner chose (`meals-grocery-choice.spec.ts`),
 * and the week's generation is left with the recurring items — and with naming
 * the meals whose ingredients are still to be chosen.
 *
 * The fake model cannot read the tool's answer, so the names of the meals are
 * `GenerateGroceryListToolTest`'s; what Maggie says here is the scenario's
 * wording, and that she proposes the choice instead of announcing an addition.
 *
 * The weekly milk is this run's own, created due; the dedicated account keeps
 * the generation from adding anyone else's recurring item.
 */
test('a planned meal adds nothing, Maggie prepares the week with the recurring items only, and the meal takes its chosen lines away when it goes — MAG-300', async ({
  mealsUser,
}) => {
  const { api, page } = mealsUser
  const suffix = `${Date.now()}`
  const riceName = `Riz MAG-300 ${suffix}`
  const milkName = `Lait MAG-300 ${suffix}`
  const recipeName = `Riz au curry MAG-300 ${suffix}`
  const cleanup: string[] = []

  try {
    // Given « Riz au curry » with rice out of stock, bought by the 500 g pack…
    const rice = await created(api, '/api/ingredients', {
      name: riceName,
      category: 'grain',
      packagingUnit: 'pack',
      packagingSize: 500,
      packagingSizeUnit: 'g',
      stockState: 'out',
    })
    cleanup.push(rice['@id'])
    const recipe = await created(api, '/api/recipes', {
      name: recipeName,
      servings: 2,
      ingredients: [{ ingredient: rice['@id'], quantity: 300, unit: 'g' }],
    })
    cleanup.push(recipe['@id'])

    // …and a recurring item that is due: milk, weekly, never added yet.
    const milk = await created(api, '/api/products', { name: milkName, category: 'dairy' })
    cleanup.push(milk['@id'])
    const recurring = await created(api, '/api/recurring_grocery_items', {
      product: milk['@id'],
      quantity: 1,
      unit: 'l',
      frequency: 'weekly',
    })
    cleanup.push(recurring['@id'])
    await waitForIndexed<{ name: string }>(api, '/api/recipes?itemsPerPage=200', (row) => row.name === recipeName, {
      what: 'The recipe to plan',
    })

    // When I plan it on Saturday lunch and answer « Plus tard »…
    const shell = new AdminShell(page)
    await shell.goto(ROUTES.meals)
    await shell.content.getByTestId('meal-cell-lunch-5').click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Menu').click()
    await page.getByRole('option', { name: recipeName }).click()
    await dialog.getByRole('button', { name: 'Créer' }).click()
    await expect(dialog.getByRole('checkbox', { name: riceName })).toBeVisible()
    await dialog.getByRole('button', { name: 'Plus tard' }).click()
    await expect(dialog).toBeHidden()

    const meal = await waitForIndexed<{ '@id': string; id: string; summary: string }>(
      api,
      '/api/meals?itemsPerPage=200',
      (m) => String(m.summary).includes(recipeName),
      { what: 'The planned meal' },
    )
    cleanup.unshift(meal['@id'])

    // …then the list holds nothing of it.
    const afterPlanning = (await getCollection<GroceryListRow>(api, '/api/grocery_lists'))[0]
    expect(linesOf(afterPlanning ?? { items: [] }, riceName), 'planning the meal put its rice on the list').toHaveLength(0)

    // When I ask Maggie to prepare the week…
    const chat = new ChatPanel(page)
    const events = await chat.send(`Prépare les courses du ${seedDate(0)} au ${seedDate(13)}`)
    expect(calledTools(events)).toContain('generate_grocery_list')
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'generate_grocery_list', status: 'success' })]),
    )

    // …then the recurring milk is on the list and the meal's rice is not.
    const afterGenerating = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => linesOf(list, milkName).length > 0, {
      what: 'The recurring milk on the list',
    })
    expect(linesOf(afterGenerating, milkName)).toMatchObject([{ source: 'recurring' }])
    expect(linesOf(afterGenerating, riceName), 'preparing the week derived the meal’s ingredients').toHaveLength(0)

    // And Maggie offers the choice instead of announcing an addition.
    expect(assistantText(events)).toMatch(/choisir les ingrédients/)

    // When I review the meal's ingredients and add them…
    await chat.close()
    await page.getByRole('button', { name: `Revoir les ingrédients à acheter pour ${meal.summary}` }).click()
    await expect(dialog.getByRole('checkbox', { name: riceName })).toBeChecked()
    await dialog.getByRole('button', { name: 'Ajouter aux courses' }).click()
    await expect(dialog).toBeHidden()

    // …then the list holds « Riz — 1 paquet ».
    await waitForIndexed<GroceryListRow>(
      api,
      '/api/grocery_lists',
      (list) => 1 === linesOf(list, riceName).length && 1 === linesOf(list, riceName)[0].quantity && 'pack' === linesOf(list, riceName)[0].unit,
      { what: 'A single « Riz — 1 paquet » line' },
    )

    // When I delete the meal…
    await page.getByRole('button', { name: `Supprimer le repas ${meal.summary}` }).click()

    // …then its lines leave, and the recurring milk stays.
    const afterDeleting = await waitForIndexed<GroceryListRow>(api, '/api/grocery_lists', (list) => 0 === linesOf(list, riceName).length, {
      what: 'The list without the rice',
    })
    expect(linesOf(afterDeleting, milkName)).toHaveLength(1)
  } finally {
    for (const iri of cleanup) await api.delete(iri)
  }
})
