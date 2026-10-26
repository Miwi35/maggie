import { randomUUID } from 'node:crypto'
import { test, expect } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Maggie, folded into her floating portrait (MAG-624, extends MAG-99).
 *
 * The admin opens on the page alone: only the 64px face, bottom right, says she is there.
 * A click brings the conversation in and the portrait goes away; the ✕ of the chat folds it back
 * and the portrait returns.
 *
 * 82-greeting-reference.yaml repeats the reference, so the answer read is this run's alone.
 */

// The second account, like chat-feedback.spec.ts: chat.spec.ts counts the seeded user's messages.
test('Maggie is a portrait until clicked, then answers a greeting', async ({ otherUser }) => {
  const { page } = otherUser
  const ref = randomUUID().slice(0, 8)
  const ANSWER = `Bonjour ! Je suis là, dis-moi ce qu'il te faut. (réf ${ref})`

  // Given the admin just opened, then only the portrait is there — no conversation.
  await new HomePage(page).open()
  const chat = new ChatPanel(page)
  await expect(chat.portrait).toBeVisible()
  await expect(chat.panel).toBeHidden()

  // When the owner clicks the portrait and says hello…
  await chat.portrait.click()
  await expect(chat.input).toBeVisible()
  await chat.send(`Bonjour Maggie (réf ${ref})`)

  // …then the fake model answers in the conversation, once.
  await expect(chat.bubbles(ANSWER)).toHaveCount(1)

  // The portrait made way for the conversation…
  await expect(chat.portrait).toBeHidden()

  // …and the ✕ of the chat header folds it away and brings the portrait back.
  await chat.close()
  await expect(chat.portrait).toBeVisible()
})
