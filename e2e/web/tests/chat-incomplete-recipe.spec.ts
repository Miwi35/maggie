import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Maggie does not create an empty record in silence (MAG-596, extends MAG-99).
 *
 * « Ajoute cette recette » over a screenshot that shows a title and a photo, with a screen
 * context that holds the page's domain and nothing else: on 24 Oct. she created a recipe
 * without ingredients or steps and said nothing. 02-screen-recipe-incomplete.yaml has the
 * model ask for `create_recipe` with the title alone — the mistake — so that the journey
 * proves what the API does with it: it refuses, nothing is stored, and the answer says what
 * is missing and offers to read the page. Whether the real model holds back before the call
 * is `task e2e:eval`'s; the sentence is a fixture and proves nothing.
 *
 * An account of its own: nothing else writes its recipes or its chat.
 */

test.use({ journeyAccount: 'empty' })

const TITLE = 'Boeuf bourguignon : la vraie recette'
const SCREEN_CONTEXT = "[Contexte de l'écran]\nApplication : Chrome (com.android.chrome)\nPage : marmiton.org"
const PIXEL =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

test('a recipe asked over a screenshot with no recipe in it is refused, and Maggie says what is missing', async ({
  page,
  api,
  session,
}) => {
  await new HomePage(page).open()

  // When the owner asks for the recipe over a bare screenshot.
  const body = await page.evaluate(
    async ({ message, screenContext, data, token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          message,
          screen_context: screenContext,
          image: { media_type: 'image/png', data },
          idempotency_key: `e2e-incomplete-recipe-${Math.random().toString(36).slice(2)}`,
        }),
      })
      return response.text()
    },
    { message: 'Ajoute cette recette', screenContext: SCREEN_CONTEXT, data: PIXEL, token: session.token },
  )
  const events = parseAgUiStream(body)

  // Then the tool was asked for and refused: nothing is created.
  expect(calledTools(events)).toEqual(['create_recipe'])
  expect(toolResults(events)).toEqual([{ toolName: 'create_recipe', status: 'error' }])
  const recipes = await getCollection<{ name: string }>(api, '/api/recipes')
  expect(recipes.filter((recipe) => recipe.name === TITLE)).toEqual([])

  // And the answer says she saw no recipe, created nothing, and offers to read the page.
  const answer = assistantText(events)
  expect(answer).toContain('Je ne vois pas la recette')
  expect(answer).toContain("Je n'ai rien créé")
  expect(answer).toContain('lise la page')
})
