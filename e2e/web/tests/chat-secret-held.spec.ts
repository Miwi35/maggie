import { test, expect } from '../fixtures/index.js'
import { assistantText } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * A code written to the chat never reaches the model nor the history (MAG-871, extends
 * [MAG-99](https://linear.app/meven/issue/MAG-99/parcours-e2e-chat-et-maggie-streaming-contextes-outils-voix)).
 *
 * The fake model only answers `08-secret-held` when the turn carries `[Message retenu]` and `[masqué]`.
 */

const ANSWER = 'Je ne prends pas les codes dans la conversation, monsieur. Gardez-le pour vous.'

// The second account, like chat-in-car.spec.ts; `send` takes the chat lock.
test('a code alone is held: Maggie answers, the thread keeps [masqué] and never the code', async ({ otherUser }) => {
  const { page, api } = otherUser

  await new HomePage(page).open()
  const chat = new ChatPanel(page)
  await chat.open()

  const events = await chat.send('4821')
  expect(assistantText(events)).toBe(ANSWER)

  await page.reload()
  await chat.open()
  await expect(chat.bubbles('[masqué]').first()).toBeVisible()
  await expect(chat.panel.getByText('4821')).toHaveCount(0)

  const stored = await (await api.get('/agent/messages?limit=20')).text()
  expect(stored).not.toContain('4821')
})
