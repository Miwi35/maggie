import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock, withLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Maggie's two memory layers (MAG-17, extends MAG-99 and MAG-195).
 *
 * The conversation is kept whole by the code, whatever the model does: an anecdote told in passing is
 * on file, to the minute, even though Maggie calls no tool. A fiche is knowledge she writes; it cites
 * the conversation and the minute it comes from, is listed in her prompt on the next turn, and she opens
 * it before answering. The model is the fake (LLM_PROVIDER=fake) and the bucket the simulated one.
 */

// Both memory journeys use the second account and the one bucket outage switch: one at a time.
const MEMORY_LOCK = 'memory-folder'
const TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'
const headers = { 'X-E2E-Token': TOKEN, 'Content-Type': 'application/json' }

const ANECDOTE = "Les concerts commencent à 19 heures et la salle s'appelle l'UBU."
const ANSWER = 'Je garde ça en tête : les concerts commencent à 19 heures.'
const FICHE = "Sorties/Les concerts commencent à 19 heures à l'UBU.md"

interface Sync {
  ok: boolean
}

interface State {
  local: string[]
}

const encode = (path: string): string => path.split('/').map(encodeURIComponent).join('/')

async function call<T>(api: APIRequestContext, method: 'get' | 'post' | 'delete', path: string): Promise<T> {
  const response = await api[method](`/agent/e2e/memory/${path}`, { headers })
  expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(200)
  return (await response.json()) as T
}

test('an anecdote is kept whole without any tool, and a fiche cites it and comes back on the next turn', async ({
  otherUser,
}) => {
  await withLock(MEMORY_LOCK, async () => {
    const { page, api, session } = otherUser
    const user = session.user.id
    const state = async (): Promise<State> => call<State>(api, 'get', `${user}/state`)
    const inBucket = async (path: string): Promise<string> => (await call<{ text: string }>(api, 'get', `bucket/${user}/${encode(path)}`)).text
    const push = async (): Promise<void> => {
      expect((await call<Sync>(api, 'post', `${user}/sync`)).ok).toBe(true)
    }

    await call(api, 'delete', user)
    await new DashboardPage(page).open()

    const ask = async (message: string): Promise<{ text: string; tools: string[] }> =>
      withChatLock(async () => {
        const body = await page.evaluate(
          async ({ message, token }) => {
            const response = await fetch('/agent/chat/stream', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
              body: JSON.stringify({ message }),
            })
            return response.text()
          },
          { message, token: session.token },
        )
        const events = parseAgUiStream(body)
        return { text: assistantText(events), tools: calledTools(events) }
      })

    // Given an anecdote told in passing, When Maggie answers without calling any tool,
    const first = await ask(ANECDOTE)
    expect(first.text).toContain('19 heures')
    expect(first.tools).toEqual([])

    // Then the exchange is on file, whole, with the minute of each message.
    const wholeExchange = async (): Promise<{ file: string; text: string } | undefined> => {
      await push()
      const files = (await state()).local.filter((f) => f.startsWith('Conversations/'))
      const texts = await Promise.all(files.map(async (file) => ({ file, text: await inBucket(file) })))
      return texts.find(({ text }) => text.includes(ANECDOTE) && text.includes(ANSWER))
    }
    await expect.poll(wholeExchange).toBeDefined()
    const { file: conversation, text } = (await wholeExchange())!
    expect(conversation, 'a conversation file holds the anecdote').toMatch(/^Conversations\/\d{4}\/\d{2}\/\d{4}-\d{2}-\d{2} \d{2}h\d{2} — .+\.md$/)
    const headings = [...text.slice(0, text.indexOf(ANECDOTE)).matchAll(/^## (\d{4}-\d{2}-\d{2} \d{2}h\d{2}) — /gm)]
    const minute = headings.at(-1)?.[1]
    expect(minute, 'the anecdote is under a heading with its minute').toBeDefined()

    // When the owner asks her to keep it, Then the fiche is written at once, in the same turn.
    const second = await ask(`Retiens ce que je t'ai dit sur la salle : conversation « ${conversation} » à ${minute}`)
    expect(second.tools).toEqual(['memory_write_fiche'])
    expect(second.text).toContain("à l'UBU")
    expect((await state()).local).toContain(FICHE)
    expect((await call<{ paths: string[] }>(api, 'get', `${user}/search?q=UBU`)).paths).toContain(FICHE)

    // And the fiche cites the conversation and the minute, with its provenance.
    await push()
    const fiche = await inBucket(FICHE)
    expect(fiche).toContain("- Les concerts commencent à 19 heures et la salle s'appelle l'UBU — dit · " + conversation + ' · ' + minute)

    // The conversation is never rewritten: what was on file is still there, followed by the new exchange.
    const after = await inBucket(conversation)
    expect(after.startsWith(text)).toBe(true)
    expect(after).toContain(`conversation « ${conversation} »`)

    // On the next turn her prompt lists the fiche and she opens it before answering.
    const third = await ask('Que sais-tu de la salle de concert ?')
    expect(third.tools).toEqual(['memory_open'])
    expect(third.text).toContain("la salle s'appelle l'UBU")

    await call(api, 'delete', user)
  })
})
