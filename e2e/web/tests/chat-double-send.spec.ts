import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, countEvents, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The same message sent twice while Maggie still answers it (MAG-386, extends MAG-99).
 *
 * On 10 Oct. a slow answer made the owner send his request again; both turns ran and the
 * second undid the first. A message with no key in common but the same words, received while
 * the turn of the first runs, now follows that turn: one message stored, one tool call, one answer.
 */

const ANSWER =
  "J'ai regardé ton agenda une seule fois pour répondre à ce message envoyé deux fois, sans rien refaire."

// The second account, for the same reason as chat-client-leaves.spec.ts.
test('the same message sent twice while Maggie answers makes one turn and one answer', async ({ otherUser }) => {
  // Unique per run: a retry of this journey inside the minute would otherwise attach to the first run's answer.
  const QUESTION = `Vérifie encore une fois, double envoi ${Date.now()}`
  const { page, api, session } = otherUser
  await new DashboardPage(page).open()

  await withChatLock(async () => {
    const [first, second] = await page.evaluate(
      async ({ message, token }) => {
        const send = async (text: string) => {
          const response = await fetch('/agent/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            body: JSON.stringify({ message: text }),
          })
          return response.text()
        }
        const first = send(message)
        // The answer is late: the owner types it again, spaces and capitals not being the point.
        await new Promise((resolve) => setTimeout(resolve, 1500))
        const second = send(`  ${message.toUpperCase()} `)
        return Promise.all([first, second])
      },
      { message: QUESTION, token: session.token },
    )

    const firstEvents = parseAgUiStream(first)
    const secondEvents = parseAgUiStream(second)

    // The tool ran once, in the first turn only.
    expect(calledTools(firstEvents).filter((tool) => tool === 'get_upcoming_events')).toHaveLength(1)
    expect(calledTools(secondEvents)).toEqual([])
    // And the client sees one answer in each stream, the same one.
    expect(countEvents(firstEvents, 'TEXT_MESSAGE_START')).toBe(1)
    expect(assistantText(firstEvents)).toBe(ANSWER)
    expect(assistantText(secondEvents)).toBe(ANSWER)

    const messages = (await (await api.get('/agent/messages?limit=10')).json()) as { role: string; content: string }[]
    expect(messages.filter((m) => m.role === 'user' && m.content === QUESTION)).toHaveLength(1)
    expect(messages.filter((m) => m.role === 'assistant' && m.content === ANSWER)).toHaveLength(1)
  })
})
