import { test, expect } from '../fixtures/index.js'
import { parseAgUiStream } from '../helpers/agui.js'
import { withChatLock } from '../helpers/chatLock.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * A message the agent could not be reached for (MAG-363, extends MAG-99).
 *
 * The failure is a state of the owner's message — a mention and « Réessayer » under it —
 * and never a bubble of Maggie's. Retrying sends the same text under the same
 * idempotency key, so an agent that had already received it answers once.
 */

// 82-greeting.yaml
const QUESTION = 'Bonjour Maggie'
const ANSWER = "Bonjour ! Je suis là, dis-moi ce qu'il te faut."
const MENTION = "Maggie n'a pas pu être jointe"

// The second account, like chat-client-leaves.spec.ts: chat.spec.ts counts the seeded
// user's messages and threads, and one sent here under that account would skew them.
test('an unreachable agent leaves a mention under the message, and « Réessayer » gets the answer once', async ({
  otherUser,
}) => {
  const { page } = otherUser
  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  await chat.open()

  const keys: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/agent/chat/stream') && request.method() === 'POST') {
      keys.push((JSON.parse(request.postData() ?? '{}') as { idempotency_key?: string }).idempotency_key ?? '')
    }
  })

  await page.route('**/agent/chat/stream', (route) => route.abort('connectionrefused'))
  await chat.input.fill(QUESTION)
  await chat.input.press('Enter')

  const mention = chat.panel.getByText(MENTION)
  await expect(mention).toBeVisible()
  await expect(chat.panel.getByRole('button', { name: 'Réessayer' })).toBeVisible()
  // « c'est de l'info, pas une réponse »: nothing from Maggie, and the question once.
  await expect(chat.panel.getByText(/impossible de contacter/i)).toHaveCount(0)
  await expect(chat.bubbles(QUESTION)).toHaveCount(1)
  await expect(chat.bubbles(ANSWER)).toHaveCount(0)

  // The network is back.
  await page.unroute('**/agent/chat/stream')
  await withChatLock(async () => {
    const stream = page.waitForResponse(
      (response) => response.url().includes('/agent/chat/stream') && response.request().method() === 'POST',
      { timeout: 60_000 },
    )
    await chat.panel.getByRole('button', { name: 'Réessayer' }).click()

    const response = await stream
    expect(response.status(), 'the agent refused the retried message').toBe(200)
    expect(parseAgUiStream(await response.text()).map((event) => event.type)).toContain('RUN_FINISHED')
  })

  await expect(chat.bubbles(ANSWER)).toHaveCount(1)
  await expect(chat.bubbles(QUESTION)).toHaveCount(1)
  await expect(mention).toHaveCount(0)

  // The same message, the same key.
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})
