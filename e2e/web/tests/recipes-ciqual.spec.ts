import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * Recipes and the Ciqual food base — the first bullet of MAG-101, and the
 * corner of the repository with the worst regressions-per-test-file ratio: six
 * fixes for four API test files.
 *
 * Three things have to hold for "a recipe with Ciqual ingredients" to work, and
 * they are three different pieces of the stack:
 *
 *   the service   `/ciqual/foods` answers from a SQLite base built out of the
 *                 committed XML. The `dev` image bakes none and the stack mounts
 *                 the worktree over `/app`, so until `task e2e:ciqual:db` ran,
 *                 every call here answered 500 and nothing below could work.
 *   the API       `IngredientFromCiqualResolver` turns a food code into an
 *                 `Ingredient`, with its macros and a category mapped from the
 *                 food's group.
 *   the form      the admin has to send that code *in the ingredient row*.
 *
 * The first two work and are asserted here. The third does not: the Ciqual
 * autocomplete writes to the root of the form rather than to the row, so the
 * API is handed an ingredient with no code and refuses the whole recipe. That
 * is MAG-173, and the journey for it is written expected-to-fail rather than
 * left out — it is the fix's reproduction.
 *
 * Tag search is here too, because it is a recipe read and because MAG-114 § 2
 * is about it: a `LIKE` against a JSON column, which Postgres refuses outright,
 * so the call failed rather than returning nothing.
 */

/** Two real Ciqual foods. Codes are stable: they come from the published base. */
const COURGETTE = { code: '20020', name: 'Courgette, chair et peau, crue' }
const TOMATO = { code: '20276', name: 'Tomate ronde, crue' }

interface StoredIngredient {
  '@id': string
  name: string
  ciqualAlimCode: string | null
  kcalPer100g: number | null
  category: string
}

interface StoredRecipe {
  '@id': string
  name: string
  tags: string[]
}

interface CiqualFood {
  alim_code: string
  alim_name_fr: string
  kcal_per100g: number | null
}

/** A name the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

test('the Ciqual service answers through the stack', async ({ api }) => {
  // The assertion the rest of this file rests on, and the one that fails loudly
  // when the food base was never built: `app/database.py` opens the SQLite file
  // read-only, so a missing one is a 500 on every search and the autocomplete
  // is simply always empty.
  const response = await api.get(`/ciqual/foods?q=${encodeURIComponent('courgette crue')}&limit=20`)

  expect(
    response.status(),
    'GET /ciqual/foods failed — has `task e2e:ciqual:db` built ciqual/db/ciqual.db?',
  ).toBe(200)

  const foods = (await response.json()) as CiqualFood[]
  const found = foods.find((food) => food.alim_code === COURGETTE.code)

  expect(found, `${COURGETTE.name} is not in the food base`).toBeDefined()
  expect(found?.alim_name_fr).toBe(COURGETTE.name)
  // The macros the resolver copies onto the ingredient. A base built without
  // compo.xml would hold every food and no nutrient at all.
  expect(found?.kcal_per100g, 'the food has no energy value — were compositions imported?').toBeGreaterThan(0)
})

test('a recipe created with a Ciqual code gets a real ingredient, macros and all', async ({ api }) => {
  // The API half of the chain, driven through the API on purpose: the form is
  // broken (MAG-173) and this must not be red for the form's reason. When
  // MAG-173 lands, the journey below covers the same chain from the browser.
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = perAttempt('Gratin MAG-101')

  const created = await api.post('/api/recipes', {
    headers,
    data: {
      name,
      servings: 2,
      tags: ['four'],
      ingredients: [{ ciqualAlimCode: COURGETTE.code, quantity: 300, unit: 'g' }],
    },
  })

  expect(created.status(), `POST /api/recipes answered ${created.status()}: ${await created.text()}`).toBe(201)

  const ingredient = await waitForIndexed<StoredIngredient>(
    api,
    '/api/ingredients?itemsPerPage=100',
    (candidate) => candidate.ciqualAlimCode === COURGETTE.code,
    { what: 'The ingredient resolved from the Ciqual code' },
  )

  // Named by the food base rather than by whatever the caller typed: the owner
  // picked a food, not a word.
  expect(ingredient.name).toBe(COURGETTE.name)
  expect(ingredient.kcalPer100g, 'the macros were not copied from Ciqual').not.toBeNull()
  // `fruits, légumes, …` contains "légume", which `mapCategory` reads as produce.
  expect(ingredient.category).toBe('produce')

  // And asking for the same food twice reuses that ingredient instead of
  // making a second one — `findOneByUserAndCiqualAlimCode` is what stops the
  // cookbook filling up with duplicate courgettes.
  const again = await api.post('/api/recipes', {
    headers,
    data: {
      name: `${name} bis`,
      servings: 2,
      ingredients: [{ ciqualAlimCode: COURGETTE.code, quantity: 150, unit: 'g' }],
    },
  })
  expect(again.status()).toBe(201)

  const all = await getCollection<StoredIngredient>(api, '/api/ingredients?itemsPerPage=100')
  expect(
    all.filter((candidate) => candidate.ciqualAlimCode === COURGETTE.code),
    'asking for the same Ciqual food twice made a second ingredient',
  ).toHaveLength(1)
})

/**
 * The same thing from the browser, and the first bullet of MAG-101's own
 * description — expected to fail, naming MAG-173.
 *
 * `CiqualFoodAutocomplete` calls `useFormContext().setValue('ciqualAlimCode')`
 * with the bare source. Inside react-admin's `SimpleFormIterator` a source is
 * scoped through a `SourceContext` (`ingredients.0.ciqualAlimCode`) that its own
 * inputs consult and a hand-written `setValue` does not — so the code lands at
 * the root of the payload, the row carries a null, and
 * `CreateRecipeHandler::resolveIngredient` refuses the recipe outright.
 *
 * Asserted on the POST rather than on the screen: the admin answers a failed
 * create with a notification, and waiting for a recipe that is never written
 * would cost the journey thirty seconds to say the same thing.
 */
test.fail('a recipe created from the form carries the Ciqual food picked — MAG-173', async ({ page, api }) => {
  const name = perAttempt('Sauce tomate MAG-101')
  const shell = new AdminShell(page)
  await shell.goto(`${ROUTES.recipes}/create`)

  await shell.content.getByLabel('Nom').fill(name)

  const posted = page.waitForResponse(
    (response) => response.url().includes('/api/recipes') && response.request().method() === 'POST',
  )

  // An icon button, labelled by `ra.action.add` — "Ajouter" in French. Pinned
  // by `RecipeCreate.handles.test.tsx`, so a renamed label fails there in
  // seconds rather than here in minutes.
  await shell.content.getByRole('button', { name: 'Ajouter' }).click()

  // Two characters is the autocomplete's threshold and 300 ms its debounce;
  // the option is chosen by its exact name rather than by position, because
  // the service ranks results and the ranking is not this journey's business.
  await shell.content.getByLabel('Aliment Ciqual').fill('tomate ronde')
  await page.getByRole('option', { name: TOMATO.name }).click()

  await shell.content.getByLabel('Quantité').fill('4')
  await shell.content.getByLabel('Unité').click()
  await page.getByRole('option', { name: 'pièce' }).click()

  await shell.content.getByRole('button', { name: 'Enregistrer' }).click()

  const response = await posted
  expect(
    response.status(),
    `the API refused the recipe: ${await response.text()}`,
  ).toBe(201)

  const stored = await waitForIndexed<StoredRecipe>(api, '/api/recipes?itemsPerPage=100', (recipe) => recipe.name === name, {
    what: 'The new recipe',
  })
  expect(stored.name).toBe(name)

  await waitForIndexed<StoredIngredient>(
    api,
    '/api/ingredients?itemsPerPage=100',
    (candidate) => candidate.ciqualAlimCode === TOMATO.code,
    { what: 'The ingredient the form asked Ciqual for' },
  )
})

test('asking Maggie for a tag searches the recipes instead of crashing', async ({ page, api }) => {
  // MAG-114 § 2: `searchByTags` used to run a `LIKE` against a JSON column, and
  // Postgres refuses that outright — so the call *failed*, it did not merely
  // return nothing. The tool does not catch it either, so the tool loop reports
  // the round as an error, which is what the status below reads.
  //
  // The seed tags "Pâtes à la tomate" with `végétarien`, so there is something
  // to find; the control that nothing of the neighbour's comes back lives in
  // `cookbook-isolation.spec.ts`.
  const tagged = await getCollection<StoredRecipe>(api, '/api/recipes?itemsPerPage=100')
  expect(
    tagged.find((recipe) => recipe.name === 'Pâtes à la tomate')?.tags,
    'the seeded recipe lost its tag — the search below would prove nothing',
  ).toContain('végétarien')

  const shell = new AdminShell(page)
  await shell.goto(ROUTES.recipes)
  await shell.expectLoaded()

  const chat = new ChatPanel(page)
  const events = await chat.send('Montre-moi mes recettes taguées végétarien')

  expect(calledTools(events)).toContain('search_recipes')
  expect(
    toolResults(events),
    'the tag search came back as an error — a LIKE on a JSON column again?',
  ).toEqual(expect.arrayContaining([expect.objectContaining({ toolName: 'search_recipes', status: 'success' })]))
})
