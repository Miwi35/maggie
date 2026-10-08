import { test, expect } from '../fixtures/index.js'
import { withChatLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * A client that leaves before the answer (MAG-344, extends MAG-99).
 *
 * The turn used to live in the streamed response: the app closing cancelled it, and the
 * message stayed without an answer. Now it runs on the server whoever listens, so the
 * answer is stored (and published) all the same and the chat shows it on return.
 */

const QUESTION = "Regarde ma semaine, je ferme l'app pendant que tu cherches"
const ANSWER = "J'ai regardé ton agenda pendant que tu étais parti : rien d'urgent."

test('the answer is there when the chat is opened again, after the client dropped the stream', async ({
  page,
  api,
  session,
}) => {
  await new DashboardPage(page).open()

  await withChatLock(async () => {
    // Send the message, read the first event, then cut the connection like a closed app.
    const firstEvent = await page.evaluate(
      async ({ message, token }) => {
        const controller = new AbortController()
        const response = await fetch('/agent/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({ message, idempotency_key: `e2e-${Date.now()}` }),
          signal: controller.signal,
        })
        const reader = response.body!.getReader()
        const { value } = await reader.read()
        controller.abort()
        return new TextDecoder().decode(value)
      },
      { message: QUESTION, token: session.token },
    )
    expect(firstEvent).toContain('data: ')

    await expect
      .poll(
        async () => {
          const messages = (await (await api.get('/agent/messages?limit=10')).json()) as {
            role: string
            content: string
          }[]
          return messages.filter((m) => m.role === 'assistant' && m.content === ANSWER).length
        },
        { timeout: 30_000, message: 'the answer to a message whose client left was never stored' },
      )
      .toBe(1)
  })
})
