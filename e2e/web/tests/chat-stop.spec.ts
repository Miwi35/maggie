import { test, expect } from '../fixtures/index.js'
import { withChatLock } from '../helpers/chatLock.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Stopping Maggie and correcting the message, as on Claude (MAG-320, extends MAG-99).
 *
 * 97-stop-slow-answer.yaml answers slowly and freezes after its first words, so the stop
 * is never a race. « Arrêter » closes the stream, tells the agent, and puts the sentence
 * back in the field; the corrected one replaces the first message and its answer — in the
 * thread and in the agent's history (97-corrected-message.yaml answers it).
 */

const QUESTION = 'Crée un événement test à elle demain'
const CORRECTED = 'Crée un événement test à Ael demain'
const FIRST_WORDS = /Je regarde ton agenda/
const FROZEN_END = 'FIN DE LA RÉPONSE'
const CORRECTED_ANSWER = "Compris : un événement test à Ael demain. Quelle heure te convient ?"

// The second account, like chat-send-failed.spec.ts, under the chat lock for the same reason:
// an answer to another run of the account reaching this window would muddy the counts.
test('Arrêter stops the answer and gives the message back; the corrected one replaces both', async ({ otherUser }) => {
  const { page, api } = otherUser

  await withChatLock(async () => {
    await new DashboardPage(page).open()
    const chat = new ChatPanel(page)
    await chat.open()
    // The thread is shared with earlier runs, which leave their corrected exchange behind.
    const answersBefore = await chat.bubbles(CORRECTED_ANSWER).count()
    const correctedBefore = await chat.bubbles(CORRECTED).count()

    const sent: { replaces?: string }[] = []
    page.on('request', (request) => {
      if (request.url().includes('/agent/chat/stream') && request.method() === 'POST') {
        sent.push(request.postDataJSON() as { replaces?: string })
      }
    })

    // The first words show, then the stream freezes.
    await chat.input.fill(QUESTION)
    await chat.input.press('Enter')
    await expect(chat.message(FIRST_WORDS)).toBeVisible()
    await expect(chat.stopButton).toHaveAccessibleName('Arrêter')

    const interrupted = page.waitForResponse(
      (response) => response.url().includes('/agent/chat/interrupt') && response.request().method() === 'POST',
    )
    await chat.stopButton.click()
    expect((await interrupted).status(), 'the agent refused the interrupt').toBe(200)

    // Nothing more is written: not the end of the answer, not the first words either.
    await expect(chat.input).toHaveValue(QUESTION)
    await expect(chat.panel.getByRole('button', { name: 'Envoyer' })).toBeVisible()
    await expect(chat.panel.getByText(FIRST_WORDS)).toHaveCount(0)
    await expect(chat.panel.getByText(FROZEN_END)).toHaveCount(0)
    await expect(chat.bubbles(QUESTION)).toHaveCount(0)

    // « à elle » becomes « à Ael ».
    // Not `chat.submit()`: it takes the chat lock, which this journey already holds.
    await chat.input.fill(CORRECTED)
    const correctedRun = page.waitForResponse(
      (response) => response.url().includes('/agent/chat/stream') && response.request().method() === 'POST',
    )
    await chat.input.press('Enter')
    const correctedResponse = await correctedRun
    expect(correctedResponse.status(), 'the agent refused the corrected message').toBe(200)
    expect(await correctedResponse.text()).toContain('RUN_FINISHED')

    expect(sent).toHaveLength(2)
    expect(sent[0].replaces).toBeUndefined()
    expect(sent[1].replaces, 'the correction does not say which message it replaces').toBeTruthy()

    await expect(chat.bubbles(CORRECTED)).toHaveCount(correctedBefore + 1)
    await expect(chat.bubbles(CORRECTED_ANSWER)).toHaveCount(answersBefore + 1)
    await expect(chat.panel.getByText(/à elle/)).toHaveCount(0)
    await expect(chat.panel.getByText(FIRST_WORDS)).toHaveCount(0)

    // And the agent's history agrees with the screen.
    const stored = (await (await api.get('/agent/messages?limit=10')).json()) as { content: string }[]
    expect(stored.some((m) => m.content.includes('à elle'))).toBe(false)
    expect(stored.some((m) => m.content.includes(FROZEN_END))).toBe(false)
    expect(stored.filter((m) => m.content === CORRECTED)).toHaveLength(correctedBefore + 1)
  })
})
