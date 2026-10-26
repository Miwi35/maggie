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
 * The service works and is asserted here. The form used to write the Ciqual
 * code to the root of the payload rather than to the row (MAG-173, fixed), and
 * the API's lookup of an ingredient already made from that code bound the user
 * without its `ulid` type, so every Ciqual recipe answered 500 (MAG-191, fixed).
 *
 * Tag search is here too, because it is a recipe read and because MAG-114 § 2
 * is about it: a `LIKE` against a JSON column, which Postgres refuses outright,
 * so the call failed rather than returning nothing.
 */

/** Two real Ciqual foods. Codes are stable: they come from the published base. */
const COURGETTE = { code: '20020', name: 'Courgette, chair et peau, crue' }
const TOMATO = { code: '20276', name: 'Tomate ronde, crue' }
/** Used by no other journey and not seeded, so the recipe below is what creates it (MAG-182). */
const CARROT = { code: '20009', name: 'Carotte, crue' }

// Every test here makes an ingredient for the same account and the same Ciqual
// code. In two workers at once both find none and each creates one, and the
// lookup then answers 500 for good (two rows, one expected). One worker, in order.
test.describe.configure({ mode: 'serial' })

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

test('a recipe created with a Ciqual code is saved, and the same code twice is not a 500', async ({ api }) => {
  // The API half of the chain, driven through the API on purpose, so that it
  // cannot be red for the form's reason. The ingredient itself is read back by
  // the journey below and asserted against Postgres by `CreateRecipeHandlerTest`.
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

  // The second time the code is found already, which is the lookup MAG-191 broke.
  const again = await api.post('/api/recipes', {
    headers,
    data: {
      name: `${name} bis`,
      servings: 2,
      ingredients: [{ ciqualAlimCode: COURGETTE.code, quantity: 150, unit: 'g' }],
    },
  })
  expect(again.status(), `the second POST answered ${again.status()}: ${await again.text()}`).toBe(201)

  const stored = await waitForIndexed<StoredRecipe>(api, '/api/recipes?itemsPerPage=100', (recipe) => recipe.name === name, {
    what: 'The new recipe',
  })
  expect(stored.tags).toEqual(['four'])
})

test('the ingredient made from a Ciqual code is listed, macros and all, and made once', async ({ api }) => {
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = perAttempt('Gratin MAG-191')

  for (const [suffix, quantity] of [['', 300], [' bis', 150]] as const) {
    const created = await api.post('/api/recipes', {
      headers,
      data: { name: `${name}${suffix}`, servings: 2, ingredients: [{ ciqualAlimCode: COURGETTE.code, quantity, unit: 'g' }] },
    })
    expect(created.status(), `POST /api/recipes answered ${created.status()}: ${await created.text()}`).toBe(201)
  }

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

  const all = await getCollection<StoredIngredient>(api, '/api/ingredients?itemsPerPage=100')
  expect(
    all.filter((candidate) => candidate.ciqualAlimCode === COURGETTE.code),
    'asking for the same Ciqual food twice made a second ingredient',
  ).toHaveLength(1)
})

test('the ingredient a recipe creates from a Ciqual code is searchable at once, without a reindex', async ({ api }) => {
  // MAG-182: the resolver persisted the ingredient and the recipe handler
  // returned only the recipe, so the ingredient was in Postgres and in neither
  // Elasticsearch nor Mercure — `/api/products` is served from the index and
  // never listed it. `CreateRecipeHandlerTest` pins the dispatch; this is the
  // read the owner's ingredient search does.
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

  const created = await api.post('/api/recipes', {
    headers,
    data: {
      name: perAttempt('Carottes râpées MAG-182'),
      servings: 2,
      ingredients: [{ ciqualAlimCode: CARROT.code, quantity: 200, unit: 'g' }],
    },
  })
  expect(created.status(), `POST /api/recipes answered ${created.status()}: ${await created.text()}`).toBe(201)

  const found = await waitForIndexed<StoredIngredient>(
    api,
    '/api/products?itemsPerPage=100',
    (product) => product.name === CARROT.name,
    { what: 'The ingredient created with the recipe, among the products' },
  )
  expect(found.name).toBe(CARROT.name)
})

/**
 * The same thing from the browser, and the first bullet of MAG-101's own
 * description.
 *
 * The form half is fixed (MAG-173): the Ciqual autocomplete writes the code into
 * its own row, and the API now accepts it (MAG-191).
 *
 * Asserted on the POST rather than on the screen: the admin answers a failed
 * create with a notification, and waiting for a recipe that is never written
 * would cost the journey thirty seconds to say the same thing.
 */
test('a recipe built on its sheet carries the Ciqual food picked', async ({ page, api }) => {
  const name = perAttempt('Sauce tomate MAG-101')
  const shell = new AdminShell(page)
  await shell.goto(`${ROUTES.recipes}/create`)

  // MAG-601: the recipe is created from its name, then its sheet takes the
  // ingredient in a popin.
  const created = page.waitForResponse(
    (response) => response.url().includes('/api/recipes') && response.request().method() === 'POST',
  )
  const naming = page.getByRole('dialog', { name: 'Nouvelle recette' })
  await naming.getByLabel('Nom').fill(name)
  await naming.getByRole('button', { name: 'Créer' }).click()
  expect((await created).status(), 'the API refused the new recipe').toBe(201)
  await expect(page).toHaveURL(/#\/recipes\/.+\/show/)

  const patched = page.waitForResponse(
    (response) => response.url().includes('/api/recipes/') && response.request().method() === 'PATCH',
  )

  // The sheet opens to read (MAG-618): « Modifier » brings the edit tools.
  await shell.content.getByRole('button', { name: 'Modifier', exact: true }).click()
  await shell.content.getByRole('button', { name: 'Ajouter un ingrédient' }).click()
  const dialog = page.getByRole('dialog', { name: 'Ajouter un ingrédient' })

  // Two characters is the autocomplete's threshold and 300 ms its debounce;
  // the option is chosen by its exact name rather than by position, because
  // the service ranks results and the ranking is not this journey's business.
  await dialog.getByLabel('Ingrédient').fill('tomate ronde')
  await page.getByRole('option', { name: new RegExp(TOMATO.name) }).click()

  await dialog.getByLabel('Quantité').fill('4')
  await dialog.getByLabel('Unité').click()
  await page.getByRole('option', { name: 'pièce' }).click()

  await dialog.getByRole('button', { name: 'Enregistrer' }).click()

  const response = await patched
  expect(
    response.status(),
    `the API refused the recipe: ${await response.text()}`,
  ).toBe(200)
  expect(JSON.parse(response.request().postData() ?? '{}')).toMatchObject({
    ingredients: [{ ciqualAlimCode: TOMATO.code, quantity: 4, unit: 'piece' }],
  })
  await expect(shell.content.getByRole('button', { name: `Modifier la quantité de ${TOMATO.name}` })).toBeVisible()

  const stored = await waitForIndexed<StoredRecipe>(api, '/api/recipes?itemsPerPage=100', (recipe) => recipe.name === name, {
    what: 'The new recipe',
  })
  expect(stored.name).toBe(name)
})

/**
 * MAG-255: the quantity of a Ciqual ingredient already in a recipe could not be
 * changed. The form sent the line back as the API had served it — the
 * ingredient embedded as an object — and the API only understood an IRI, so the
 * save was refused and the old quantity stayed.
 */
test('the quantity of an ingredient already in a recipe can be changed', async ({ page, api }) => {
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const name = perAttempt('Pâtes MAG-255')

  const created = await api.post('/api/recipes', {
    headers,
    data: { name, servings: 2, ingredients: [{ ciqualAlimCode: COURGETTE.code, quantity: 200, unit: 'g' }] },
  })
  expect(created.status(), `POST /api/recipes answered ${created.status()}: ${await created.text()}`).toBe(201)
  const iri = ((await created.json()) as { '@id': string })['@id']

  const shell = new AdminShell(page)
  await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)

  // One click on the number turns it into a field (MAG-601); Enter saves.
  await shell.content.getByRole('button', { name: 'Modifier', exact: true }).click()
  const open = shell.content.getByRole('button', { name: `Modifier la quantité de ${COURGETTE.name}` })
  await expect(open).toHaveText(/200/)
  await open.click()

  const patched = page.waitForResponse(
    (response) => response.url().includes(iri) && response.request().method() === 'PATCH',
  )
  const quantity = shell.content.getByRole('spinbutton', { name: `Quantité de ${COURGETTE.name}` })
  await quantity.fill('300')
  await quantity.press('Enter')
  const response = await patched
  expect(response.status(), `the API refused the recipe: ${await response.text()}`).toBe(200)

  // Kept on the API side, and still there when the sheet is opened again.
  await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
  await expect(shell.content.getByRole('region', { name: 'Ingrédients' }).getByRole('listitem')).toHaveText(/300/)
  const stored = (await (await api.get(iri, { headers: { Accept: 'application/ld+json' } })).json()) as {
    ingredients: { quantity: number; ciqualAlimCode: string }[]
  }
  expect(stored.ingredients).toHaveLength(1)
  expect(stored.ingredients[0]).toMatchObject({ quantity: 300, ciqualAlimCode: COURGETTE.code })
})

/**
 * MAG-647: the nutrition of a sheet counts what is weighed, not only what is in grams. Pieces
 * go through the weight of one piece the ingredient carries, kg and cl through their unit,
 * and a line with neither weight nor nutrition is said apart instead of weighing 0.
 */
test('a sheet counts pieces, kg and cl in its values per person, and says what it could not count', async ({ page, api }) => {
  const headers = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const stamp = `${Date.now()}`
  const ingredient = async (name: string, extra: Record<string, unknown>): Promise<string> => {
    const made = await api.post('/api/ingredients', { headers, data: { name: `${name} MAG-647 ${stamp}`, category: 'other', ...extra } })
    expect(made.status(), `POST /api/ingredients answered ${made.status()}: ${await made.text()}`).toBe(201)

    return ((await made.json()) as { '@id': string })['@id']
  }
  const beef = await ingredient('Boeuf', { kcalPer100g: 250, proteinPer100g: 20, carbsPer100g: 0, fatPer100g: 17 })
  const onion = await ingredient('Oignon', { kcalPer100g: 40, proteinPer100g: 1, carbsPer100g: 9, fatPer100g: 0.1, pieceWeightGrams: 100 })
  const wine = await ingredient('Vin rouge', { kcalPer100g: 70, proteinPer100g: 0.1, carbsPer100g: 2.6, fatPer100g: 0 })
  const bunch = await ingredient('Bouquet garni', {})

  const name = `Bourguignon MAG-647 ${stamp}`
  const created = await api.post('/api/recipes', {
    headers,
    data: {
      name,
      servings: 4,
      ingredients: [
        { ingredient: beef, quantity: 1, unit: 'kg' },
        { ingredient: onion, quantity: 2, unit: 'piece' },
        { ingredient: wine, quantity: 75, unit: 'cl' },
        { ingredient: bunch, quantity: 1, unit: 'piece' },
      ],
    },
  })
  expect(created.status(), `POST /api/recipes answered ${created.status()}: ${await created.text()}`).toBe(201)
  const iri = ((await created.json()) as { '@id': string })['@id']

  const shell = new AdminShell(page)
  await shell.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)

  // 1 kg of beef + 2 × 100 g of onion + 750 g of wine = (2500 + 80 + 525) / 4 kcal and (200 + 2 + 0.75) / 4 g of protein
  await expect(shell.content.getByTestId('nutrition-energy')).toContainText('≈ 776 kcal')
  await expect(shell.content.getByText('Protéines 51 g')).toBeVisible()
  await expect(shell.content.getByText('1 ingrédient non compté')).toBeVisible()
})

test('Maggie links an ingredient to the Ciqual food and sets what one piece weighs', async ({ otherUser }) => {
  const { api } = otherUser
  const shell = new AdminShell(otherUser.page)
  await shell.goto(ROUTES.recipes)
  await shell.expectLoaded()

  const events = await new ChatPanel(otherUser.page).send('Rapproche mon oignon de Ciqual, il pèse 100 g la pièce')

  expect(calledTools(events)).toEqual(['search_ciqual_foods', 'manage_ingredients'])
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'manage_ingredients', status: 'success' })]),
  )
  const all = await getCollection<StoredIngredient & { pieceWeightGrams: number | null }>(api, '/api/ingredients?itemsPerPage=100')
  const linked = all.find((candidate) => candidate.name === 'Oignon MAG-647')
  expect(linked?.ciqualAlimCode).toBe('20034')
  expect(linked?.pieceWeightGrams).toBe(100)
  expect(linked?.kcalPer100g, 'the macros were not copied from the Ciqual food').not.toBeNull()
})

test('asking Maggie for a tag searches the recipes instead of crashing', async ({ otherUser }) => {
  // MAG-114 § 2: `searchByTags` used to run a `LIKE` against a JSON column, and
  // Postgres refuses that outright — so the call *failed*, it did not merely
  // return nothing. The tool does not catch it either, so the tool loop reports
  // the round as an error, which is what the status below reads.
  //
  // What this can assert and what it cannot, stated plainly: the fake does not
  // read tool results and AG-UI only carries each round's *outcome*, so
  // "the right recipes came back" is not observable from a browser journey.
  // That half is `RecipeToolsTest::testSearchRecipesByTag` (the tag really
  // matches) and `UserIsolationToolsTest::testSearchRecipesByTagOnlyReturnsTheCallersRecipes`
  // (nobody else's), both on every pull request. What is left here, and worth
  // having, is that the whole chain — browser, agent, tool loop, MCP, Postgres
  // — survives the question.
  //
  // Asked as the second account, like every other journey that talks to Maggie
  // (see `grocery-errand.spec.ts` for why), and that account's own recipe
  // carries the tag.
  const { api } = otherUser
  const tagged = await getCollection<StoredRecipe>(api, '/api/recipes?itemsPerPage=100')
  expect(
    tagged.find((recipe) => recipe.name === 'Velouté du voisin')?.tags,
    'the seeded recipe lost its tag — the search below would prove nothing',
  ).toContain('végétarien')

  const shell = new AdminShell(otherUser.page)
  await shell.goto(ROUTES.recipes)
  await shell.expectLoaded()

  const chat = new ChatPanel(otherUser.page)
  const events = await chat.send('Montre-moi mes recettes taguées végétarien')

  expect(calledTools(events)).toContain('search_recipes')
  expect(
    toolResults(events),
    'the tag search came back as an error — a LIKE on a JSON column again?',
  ).toEqual(expect.arrayContaining([expect.objectContaining({ toolName: 'search_recipes', status: 'success' })]))
})
