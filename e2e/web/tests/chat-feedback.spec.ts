import { randomUUID } from 'node:crypto'
import { test, expect } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { adminUrl, ROUTES } from '../pages/routes.js'

/**
 * A thumb on Maggie's answer, and the style directive she proposes from it (MAG-23, extends MAG-99).
 *
 * The thumb lands on the answer it was given to, with its comment. What the thumbs say comes
 * back as a *proposal* in the settings: nothing reaches the behaviour directives before the
 * owner accepts it (spec « Personnalité chaleureuse » — rien n'est appliqué d'office).
 *
 * 82-greeting-reference.yaml repeats the reference, so the answer rated is this run's alone;
 * 00-style-synthesis.yaml is what the fast model proposes whatever the thumbs.
 */

const DIRECTIVE = 'Réponds en deux phrases au plus'

interface AgentMessage {
  id: string
  role: string
  content: string
  feedback: string | null
  feedbackComment: string | null
}

interface AgentInstruction {
  content: string
  kind: string
}

// The second account, like chat-send-failed.spec.ts: chat.spec.ts counts the seeded user's
// messages and threads, and its directives reach every answer it scripts.
test('a thumb down with a comment becomes a proposed directive, applied only once accepted', async ({ otherUser }) => {
  const { page, api } = otherUser
  const ref = randomUUID().slice(0, 8)
  const QUESTION = `Bonjour Maggie (réf ${ref})`
  const ANSWER = `Bonjour ! Je suis là, dis-moi ce qu'il te faut. (réf ${ref})`

  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  await chat.send(QUESTION)
  await expect(chat.bubbles(ANSWER)).toHaveCount(1)

  // Given her answer, when the owner gives it a thumb down and says why…
  const thumbs = page
    .locator('[data-role="assistant"]', { hasText: ANSWER })
    .locator('xpath=..')
    .locator('xpath=following-sibling::*[@data-testid="message-feedback"][1]')
  const rated = page.waitForResponse(
    (response) => response.url().includes('/feedback') && response.request().method() === 'PUT',
  )
  await thumbs.getByRole('button', { name: 'Réponse à revoir' }).click()
  expect((await rated).status()).toBe(200)
  await expect(thumbs.getByRole('button', { name: 'Réponse à revoir' })).toHaveAttribute('aria-pressed', 'true')

  const commented = page.waitForResponse(
    (response) => response.url().includes('/feedback') && response.request().method() === 'PUT',
  )
  await thumbs.getByRole('textbox', { name: 'Commentaire sur la réponse' }).fill('Trop long')
  await thumbs.getByRole('button', { name: 'Envoyer' }).click()
  expect((await commented).status()).toBe(200)

  // …then the thumb and its word are stored on that answer, and on no other.
  const messages = (await (await api.get('/agent/messages?limit=20')).json()) as AgentMessage[]
  const answer = messages.find((m) => m.role === 'assistant' && m.content === ANSWER)
  expect(answer, 'the rated answer is not in the history').toBeTruthy()
  expect([answer?.feedback, answer?.feedbackComment]).toEqual(['down', 'Trop long'])
  expect(messages.find((m) => m.role === 'user' && m.content === QUESTION)?.feedback).toBeNull()

  // When the owner asks for the thumbs to be read, the directive is proposed — and not applied.
  await page.goto(adminUrl(ROUTES.agentSettings))
  await page.getByRole('tab', { name: 'Instructions' }).click()
  const proposals = page.getByTestId('style-proposals')
  await proposals.getByRole('button', { name: 'Analyser mes retours' }).click()
  await expect(proposals.getByRole('textbox', { name: 'Directive proposée' }).first()).toHaveValue(DIRECTIVE)

  const before = (await (await api.get('/agent/instructions?kind=behavior')).json()) as AgentInstruction[]
  const countBefore = before.filter((i) => i.content === DIRECTIVE).length

  // When they accept it, it becomes a behaviour directive, read on every answer from then on.
  const accepted = page.waitForResponse(
    (response) => response.url().includes('/style-proposals/') && response.url().endsWith('/accept'),
  )
  await proposals.getByTestId('style-proposal').first().getByRole('button', { name: 'Accepter' }).click()
  expect((await accepted).status()).toBe(200)

  const after = (await (await api.get('/agent/instructions?kind=behavior')).json()) as AgentInstruction[]
  expect(after.filter((i) => i.content === DIRECTIVE)).toHaveLength(countBefore + 1)
  expect(after.every((i) => i.kind === 'behavior')).toBe(true)
  await expect(page.getByRole('cell', { name: DIRECTIVE }).first()).toBeVisible()
})
