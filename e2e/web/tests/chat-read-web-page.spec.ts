import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
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
  expect(calledTools(events)).toEqual(['read_web_page'])
  expect(toolResults(events)).toEqual([{ toolName: 'read_web_page', status: 'success' }])

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
