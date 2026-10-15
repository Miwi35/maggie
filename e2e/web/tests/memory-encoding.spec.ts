import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { assistantText, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock, withLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Encoding along the way (MAG-15, extends MAG-99 and MAG-17).
 *
 * After a few minutes of silence on a subject, a background pass writes the carte of the passage on top of
 * its conversation file and the obvious fiche, without waiting for the night, and says in `journal.md` that
 * it was this pass. Nothing is asked of Maggie during the turn: the answer below calls no tool. The model is
 * the fake (13-memory-encoding.yaml) and the bucket the simulated one.
 *
 * The stack's clock barely ticks in CI (MAG-234), so the silence cannot be waited for: the journey asks the
 * agent to run the pass at an instant past the delay (`POST /e2e/memory/{user}/encode`), for this user only.
 */

const MEMORY_LOCK = 'memory-folder'
const TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'
const headers = { 'X-E2E-Token': TOKEN, 'Content-Type': 'application/json' }

const SENTENCE = 'Ma nièce Zoé est végétarienne, pense-y pour les repas de famille.'
const ANSWER = "D'accord, j'y penserai pour les repas de famille."
const FICHE = 'Famille/Zoé, la nièce du propriétaire, est végétarienne.md'
const CLAIM = 'Zoé, la nièce du propriétaire, est végétarienne'

interface State {
  local: string[]
}

const encode = (path: string): string => path.split('/').map(encodeURIComponent).join('/')

async function call<T>(api: APIRequestContext, method: 'get' | 'post' | 'delete', path: string): Promise<T> {
  const response = await api[method](`/agent/e2e/memory/${path}`, { headers })
  expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(200)
  return (await response.json()) as T
}

test('a subject that went quiet gets its carte and its obvious fiche, and the journal says which pass wrote them', async ({
  otherUser,
}) => {
  await withLock(MEMORY_LOCK, async () => {
    const { page, api, session } = otherUser
    const user = session.user.id
    const state = async (): Promise<State> => call<State>(api, 'get', `${user}/state`)
    const inBucket = async (path: string): Promise<string> =>
      (await call<{ text: string }>(api, 'get', `bucket/${user}/${encode(path)}`)).text
    const encodeNow = async (): Promise<number> => (await call<{ encoded: number }>(api, 'post', `${user}/encode`)).encoded
    const push = async (): Promise<void> => {
      expect((await call<{ ok: boolean }>(api, 'post', `${user}/sync`)).ok).toBe(true)
    }

    await call(api, 'delete', user)
    await new DashboardPage(page).open()

    // Given an exchange where the owner says something obvious in passing, answered without any tool,
    const answer = await withChatLock(async () => {
      const body = await page.evaluate(
        async ({ message, token }) => {
          const response = await fetch('/agent/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
            body: JSON.stringify({ message }),
          })
          return response.text()
        },
        { message: SENTENCE, token: session.token },
      )
      return assistantText(parseAgUiStream(body))
    })
    expect(answer).toContain('repas de famille')

    // When the silence goes past the delay, Then the obvious fiche exists, with no other action from anyone.
    const written = async (): Promise<boolean> => {
      await encodeNow()
      await push()
      return (await state()).local.includes(FICHE)
    }
    await expect.poll(written, { timeout: 60_000 }).toBe(true)

    // And it cites the conversation and the minute where the owner said it.
    const files = (await state()).local.filter((f) => f.startsWith('Conversations/'))
    expect(files).toHaveLength(1)
    const conversation = files[0]
    const text = await inBucket(conversation)
    const said = new RegExp(`^## (\\d{4}-\\d{2}-\\d{2} \\d{2}h\\d{2}) — Propriétaire\\n[^\\n]*\\n\\n${SENTENCE}`, 'm')
    const minute = text.match(said)?.[1]
    expect(minute, 'the owner message has its minute').toBeDefined()
    const fiche = await inBucket(FICHE)
    expect(fiche).toContain(`- ${CLAIM} — dit · ${conversation} · ${minute}`)

    // And the carte of the passage is on top of the conversation, the messages below it untouched.
    expect(text.startsWith('---\n')).toBe(true)
    expect(text.slice(0, text.indexOf('# '))).toContain('repas de famille')
    expect(text.slice(0, text.indexOf('# '))).toContain('resume:')
    expect(text).toContain(SENTENCE)
    expect(text).toContain(ANSWER)
    expect(text.split(SENTENCE)).toHaveLength(2)

    // And the journal says it was this pass, for the carte and for the fiche (its line follows the file by a beat).
    const journalLine = new RegExp(`créé \`${FICHE}\` — encodage au fil de l'eau`)
    const journalSaysIt = async (): Promise<boolean> => {
      await push()
      return journalLine.test(await inBucket('journal.md'))
    }
    await expect.poll(journalSaysIt, { timeout: 15_000 }).toBe(true)
    const journal = await inBucket('journal.md')
    expect(journal).toMatch(new RegExp(`modifié \`${conversation}\` — encodage au fil de l'eau`))

    // A pass that runs again finds nothing new: no second carte, no second fiche.
    expect(await encodeNow()).toBe(0)
    await push()
    const later = await inBucket('journal.md')
    expect(later.match(/encodage au fil de l'eau/g)).toHaveLength(journal.match(/encodage au fil de l'eau/g)!.length)
    expect((await state()).local.filter((f) => f.startsWith('Famille/'))).toEqual([FICHE])

    await call(api, 'delete', user)
  })
})
