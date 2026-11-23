import { randomUUID } from 'node:crypto'
import { test, expect } from '../fixtures/index.js'
import { parseAgUiStream } from '../helpers/agui.js'
import { withChatLock } from '../helpers/chatLock.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * An overloaded model is retried by Maggie, and the chat says so (MAG-971, extends MAG-99).
 *
 * The scenario `97-model-overloaded-once` answers 529 with `retry-after: 2` once: the loop waits,
 * emits a `retrying` event, and the answer follows. « Maggie réessaie... » replaces
 * « Maggie réfléchit... » until then, and no failure is shown.
 */

test('an overloaded model is retried: « Maggie réessaie... », then the answer, no failure', async ({ otherUser }) => {
  const { page } = otherUser
  const ref = randomUUID().slice(0, 8)
  const QUESTION = `Test de surcharge du modèle (réf ${ref})`
  const ANSWER = `C'est reparti, monsieur. Je vous écoute. (réf ${ref})`

  await withChatLock(async () => {
    await new HomePage(page).open()
    const chat = new ChatPanel(page)
    await chat.open()

    const streamed = page.waitForResponse('**/agent/chat/stream')
    await chat.input.fill(QUESTION)
    await chat.input.press('Enter')

    await expect(chat.panel.getByText('Maggie réessaie...')).toBeVisible()
    await expect(chat.bubbles(ANSWER)).toHaveCount(1)
    await expect(page.getByText('Maggie réessaie...')).toHaveCount(0)
    await expect(page.getByText("Maggie n'a pas pu être jointe")).toHaveCount(0)

    const events = parseAgUiStream(await (await streamed).text())
    const retrying = events.findIndex((event) => event.type === 'CUSTOM' && event.name === 'retrying')
    const textStart = events.findIndex((event) => event.type === 'TEXT_MESSAGE_START')
    expect(retrying).toBeGreaterThanOrEqual(0)
    expect(retrying).toBeLessThan(textStart)
  })
})
