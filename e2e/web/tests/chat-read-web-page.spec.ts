import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Maggie reads a web page, then proposes (MAG-597, extends MAG-99).
 *
 * The message cites the page's address; she calls `read_web_page`, which really runs in
 * the API against the pages the e2e stack serves from WireMock
 * (.docker/e2e/wiremock/mappings/web-pages.json), and proposes what the page holds. The
 * fake model does not read the tool's answer, so the sentence is a fixture: what the
 * journey proves is the chain — the tool reached the page through the SSRF guard and
 * answered — and that nothing is created before the « oui ».
 *
 * Then the recipe structured from that page, pasted or dictated (MAG-598): the proposal comes
 * first and writes nothing; only the « oui » makes Maggie call `create_recipe`, with
 * everything — ingredients, steps, utensils, source — and the journey reads the recipe back
 * from the API. A correction remakes the proposal and still creates nothing.
 *
 * An account of its own: nothing else writes its recipes or its chat.
 */

test.use({ journeyAccount: 'empty' })

const PAGES = 'http://wiremock:8080/pages'

async function ask(page: import('@playwright/test').Page, token: string, message: string) {
  const body = await page.evaluate(
    async ({ message, token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          message,
          idempotency_key: `e2e-read-web-page-${Math.random().toString(36).slice(2)}`,
        }),
      })
      return response.text()
    },
    { message, token },
  )

  return parseAgUiStream(body)
}

test('a recipe page is read and proposed with its ingredients, and nothing is created', async ({
  page,
  api,
  session,
}) => {
  await new HomePage(page).open()

  // When the owner cites a Marmiton page.
  const events = await ask(page, session.token, `Ajoute cette recette ${PAGES}/marmiton/boeuf-bourguignon`)

  // Then she read the page, successfully, and only read it.
  expect(calledTools(events)).toEqual(['read_web_page', 'search_ingredients'])
  expect(toolResults(events)).toEqual([
    { toolName: 'read_web_page', status: 'success' },
    { toolName: 'search_ingredients', status: 'success' },
  ])

  // And she proposes the recipe with its ingredients, without creating it.
  const answer = assistantText(events)
  expect(answer).toContain('Boeuf bourguignon')
  expect(answer).toContain('6 ingrédients')
  expect(answer).toContain('boeuf à braiser')
  expect(answer).toContain('Je l\'ajoute')
  expect(await getCollection(api, '/api/recipes')).toEqual([])
})

test('an event page is read and proposed with its date and place', async ({ page, session }) => {
  await new HomePage(page).open()

  const events = await ask(page, session.token, `Mets ça dans mon agenda ${PAGES}/events/concert-jazz`)

  expect(calledTools(events)).toEqual(['read_web_page'])
  expect(toolResults(events)).toEqual([{ toolName: 'read_web_page', status: 'success' }])
  const answer = assistantText(events)
  expect(answer).toContain('Concert de jazz')
  expect(answer).toContain('14 novembre 2026')
  expect(answer).toContain('Rennes')
})

test('a product page is read and proposed for the grocery list', async ({ page, session }) => {
  await new HomePage(page).open()

  const events = await ask(page, session.token, `Ajoute ça à mes courses ${PAGES}/products/cafe-arabica`)

  expect(calledTools(events)).toEqual(['read_web_page'])
  expect(toolResults(events)).toEqual([{ toolName: 'read_web_page', status: 'success' }])
  const answer = assistantText(events)
  expect(answer).toContain('Café en grains Arabica')
  expect(answer).toContain('14,90 €')
})

interface RecipeRecord {
  name: string
  servings: number
  notes: string | null
  ingredients: Array<{ ingredientName: string; quantity: number; unit: string }>
  utensils: Array<{ name: string }>
  steps: Array<{ position: number; durationMinutes: number | null; background: boolean; waitsFor: number[] }>
}

async function createdRecipe(api: import('@playwright/test').APIRequestContext, name: string): Promise<RecipeRecord> {
  const listed = await waitForIndexed<{ name: string; '@id': string }>(api, '/api/recipes?pagination=false', (r) => r.name === name, {
    what: `The recipe ${name}`,
  })
  const read = await api.get(listed['@id'], { headers: { Accept: 'application/ld+json' } })
  expect(read.ok()).toBeTruthy()

  return (await read.json()) as RecipeRecord
}

test('after the yes, the recipe of the page is created with its ingredients, steps and utensil — MAG-598', async ({
  page,
  api,
  session,
}) => {
  await new HomePage(page).open()
  const address = `${PAGES}/marmiton/boeuf-bourguignon`

  // Given Maggie proposed the recipe of a Marmiton page, and created nothing
  await ask(page, session.token, `Ajoute cette recette ${address}`)
  expect(await getCollection(api, '/api/recipes')).toEqual([])

  // When I answer « oui »
  const events = await ask(page, session.token, 'Oui')

  // Then she creates it, once, with everything
  expect(calledTools(events)).toEqual(['create_recipe'])
  expect(toolResults(events)).toEqual([{ toolName: 'create_recipe', status: 'success' }])

  // And the recipe appears in Recettes with its portions, ingredients, steps, utensil and source
  const recipe = await createdRecipe(api, 'Boeuf bourguignon : la vraie recette')
  expect(recipe.servings).toBe(6)
  expect(recipe.notes).toBe(`Source : ${address}`)
  expect(recipe.ingredients).toHaveLength(6)
  expect(recipe.ingredients.map((line) => line.ingredientName)).toContain('Boeuf, paleron cru')
  expect(recipe.utensils.map((utensil) => utensil.name)).toEqual(['Cocotte'])
  expect(recipe.steps.map(({ position, durationMinutes, background, waitsFor }) => ({ position, durationMinutes, background, waitsFor }))).toEqual([
    { position: 1, durationMinutes: null, background: false, waitsFor: [] },
    { position: 2, durationMinutes: null, background: false, waitsFor: [1] },
    { position: 3, durationMinutes: 180, background: true, waitsFor: [2] },
  ])
})

test('a correction remakes the proposal and creates nothing — MAG-598', async ({ page, api, session }) => {
  await new HomePage(page).open()

  await ask(page, session.token, `Ajoute cette recette ${PAGES}/marmiton/boeuf-bourguignon`)

  // When I ask for 12 people instead
  const events = await ask(page, session.token, 'Plutôt pour 12 personnes')

  // Then she proposes again, with the quantities in proportion, and writes nothing
  expect(calledTools(events)).toEqual([])
  expect(assistantText(events)).toContain('12 personnes')
  expect(await getCollection(api, '/api/recipes')).toEqual([])
})

const PASTA = [
  {
    source: 'pasted',
    message: `Pâtes aux légumes pour 4 personnes
Ingrédients : 200 g de pâtes, 1 courgette, 1 poivron.
1. Mettre l'eau à bouillir, 10 minutes.
2. Couper les légumes.
3. Cuire les pâtes 12 minutes une fois l'eau bouillante.
4. Faire revenir les légumes 8 minutes.
5. Égoutter les pâtes et mélanger.`,
  },
  {
    source: 'dictated',
    message:
      "alors c'est mes pâtes aux légumes pour quatre personnes, deux cents grammes de pâtes, une courgette, un poivron, je mets l'eau à bouillir dix minutes, pendant ce temps je coupe les légumes, ensuite je cuis les pâtes douze minutes, je fais revenir les légumes huit minutes et j'égoutte le tout",
  },
]

for (const { source, message } of PASTA) {
  test(`a ${source} recipe is proposed, then created with its five steps and two utensils after the yes — MAG-598`, async ({
    page,
    api,
    session,
  }) => {
    await new HomePage(page).open()

    // Given Maggie proposed the recipe I gave her and created nothing
    const proposal = await ask(page, session.token, message)
    expect(assistantText(proposal)).toContain('Je la crée ?')
    expect(calledTools(proposal)).not.toContain('create_recipe')
    expect(await getCollection(api, '/api/recipes')).toEqual([])

    // When I answer « oui »
    const events = await ask(page, session.token, 'oui')

    // Then the recipe exists with the reference steps: two timers in the background and a last step waiting for two others
    expect(calledTools(events)).toEqual(['create_recipe'])
    const recipe = await createdRecipe(api, 'Pâtes aux légumes')
    expect(recipe.servings).toBe(4)
    expect(recipe.ingredients).toHaveLength(3)
    expect(recipe.utensils.map((utensil) => utensil.name).sort()).toEqual(['Casserole', 'Poêle'])
    expect(recipe.steps.map(({ position, durationMinutes, background, waitsFor }) => ({ position, durationMinutes, background, waitsFor }))).toEqual([
      { position: 1, durationMinutes: 10, background: true, waitsFor: [] },
      { position: 2, durationMinutes: null, background: false, waitsFor: [] },
      { position: 3, durationMinutes: 12, background: true, waitsFor: [1] },
      { position: 4, durationMinutes: 8, background: false, waitsFor: [] },
      { position: 5, durationMinutes: null, background: false, waitsFor: [3, 4] },
    ])
  })
}
