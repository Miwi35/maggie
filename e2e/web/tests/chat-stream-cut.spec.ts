import { randomUUID } from 'node:crypto'
import { test, expect } from '../fixtures/index.js'
import { withChatLock } from '../helpers/chatLock.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * A stream cut during a silence is not a failed turn (MAG-638, extends MAG-99).
 *
 * A slow tool leaves the stream silent; a proxy that cuts it must not make the chat say
 * « Maggie n'a pas pu être jointe »: the admin follows the turn again, under the same key,
 * and the answer lands without a reload.
 */

const MENTION = "Maggie n'a pas pu être jointe"

test('a stream cut before the run finished is followed again: no mention, the answer shows', async ({
  otherUser,
}) => {
  const { page } = otherUser
  const ref = randomUUID().slice(0, 8)
  const QUESTION = `Bonjour Maggie (réf ${ref})`
  const ANSWER = `Bonjour ! Je suis là, dis-moi ce qu'il te faut. (réf ${ref})`

  await withChatLock(async () => {
    await new HomePage(page).open()
    const chat = new ChatPanel(page)
    await chat.open()

    const keys: string[] = []
    let cuts = 0
    await page.route('**/agent/chat/stream', async (route) => {
      keys.push((JSON.parse(route.request().postData() ?? '{}') as { idempotency_key?: string }).idempotency_key ?? '')
      if (cuts++ > 0) return route.continue()
      // The first response ends after RUN_STARTED, as a proxy would cut it during a slow tool.
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: `data: ${JSON.stringify({ type: 'RUN_STARTED', runId: 'cut' })}\n\n`,
      })
    })

    await chat.input.fill(QUESTION)
    await chat.input.press('Enter')

    await expect(chat.bubbles(ANSWER)).toHaveCount(1)
    await expect(chat.bubbles(QUESTION)).toHaveCount(1)
    await expect(chat.panel.getByText(MENTION)).toHaveCount(0)
    await expect(chat.panel.getByRole('button', { name: 'Réessayer' })).toHaveCount(0)

    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
  })
})
