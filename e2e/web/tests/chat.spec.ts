import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, contextAction, deltaCount, isUnscripted } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Talking to Maggie from the browser.
 *
 * `LLM_PROVIDER=fake` (MAG-95) scripts the model's side and nothing else: the
 * AG-UI gateway, the tool loop and the MCP client are the production ones. So
 * what is asserted here is the chain, not the prose — which tool ran, that the
 * answer streamed rather than arrived whole, that the write reached the
 * database. Judgement is the eval suite's job, on the real model.
 *
 * Serial, because the conversation is stateful: the context router opens a
 * context on the first message and every later one joins it.
 */

test.describe.configure({ mode: 'serial' })

const AGENDA_QUESTION = "Qu'est-ce que j'ai de prévu aujourd'hui ?"

test('a scripted question runs its tool and streams the answer back', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(AGENDA_QUESTION)

  const answer = assistantText(events)
  expect(isUnscripted(answer), `no scenario matched — Maggie said: ${answer}`).toBe(false)

  // The tool really ran, against the real MCP server and the seeded database.
  expect(calledTools(events)).toContain('get_upcoming_events')

  // More than one delta: a single one would mean the gateway buffered the
  // whole answer, which is the bug streaming exists to avoid.
  expect(deltaCount(events), 'the answer did not stream').toBeGreaterThan(1)

  // And the context router opened a context for it.
  expect(contextAction(events)).toBe('created')

  await expect(chat.message(/déjeuner avec Alex/i)).toBeVisible()
})

test('an unscripted message says so rather than improvising', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send("une question que personne n'a scriptée depuis le navigateur")

  // The assertion that proves the switch is in effect at all: the real model
  // would never produce this sentence.
  expect(isUnscripted(assistantText(events))).toBe(true)
})

interface GroceryList {
  items?: Array<{ label?: string }>
}

test('asking for an item writes it to the grocery list', async ({ page, api }) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  // Basil is a seeded ingredient the seed deliberately leaves *off* the list,
  // so its appearance there proves the write happened.
  await expect(grocery.item('Basilic')).toBeHidden()

  const chat = new ChatPanel(page)
  const events = await chat.send('Ajoute du basilic à ma liste de courses')

  expect(calledTools(events)).toContain('add_grocery_item')

  // The data, not her wording: the fake does not read tool results, so a
  // scripted sentence cannot prove anything landed. Polled, because the
  // collection is served from Elasticsearch and the write is indexed through
  // RabbitMQ — the row exists before it is findable.
  await waitForIndexed<GroceryList>(
    api,
    '/api/grocery_lists',
    (list) => (list.items ?? []).some((item) => item.label?.toLowerCase() === 'basilic'),
    { what: 'The basil Maggie added' },
  )

  // And it reaches the screen the owner actually looks at.
  await grocery.expectItemEventually('Basilic')
})
