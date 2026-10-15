import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { assistantText, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock, withLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Maggie's memory folder and its bucket (MAG-195, extends MAG-99).
 *
 * The files are the truth: a file the owner changes in the bucket is taken back by the next
 * synchronisation and the index reflects it; when the bucket is cut Maggie still answers, from her
 * local copy and her index, and says so; what she wrote meanwhile is pushed when it comes back.
 * The bucket is the fake one (MEMORY_PROVIDER=fake), cut and restored through the agent's e2e routes.
 */

// Both memory journeys use the second account and the one bucket outage switch: one at a time.
const MEMORY_LOCK = 'memory-folder'
const TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'
const headers = { 'X-E2E-Token': TOKEN, 'Content-Type': 'application/json' }

const FICHE = 'Famille/Françoise et les fleurs.md'
const GIFT = 'Famille/Un cadeau pour Françoise.md'

const fiche = (text: string, resume: string): string => `---\nresume: ${resume}\nevoque: [fleurs, cadeau]\n---\n\n${text}\n`

interface Sync {
  ok: boolean
  pulled?: number
  pushed?: number
}

interface State {
  degraded: boolean
  local: string[]
  indexed: string[]
  outbox: string[]
}

async function call<T>(api: APIRequestContext, method: 'get' | 'put' | 'post' | 'delete', path: string, data?: unknown): Promise<T> {
  const response = await api[method](`/agent/e2e/memory/${path}`, { headers, ...(data === undefined ? {} : { data }) })
  expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(200)
  return (await response.json()) as T
}

test('a file changed in the bucket is taken back, Maggie answers during an outage and the write is pushed after', async ({
  otherUser,
}) => {
  await withLock(MEMORY_LOCK, async () => {
    const { page, api, session } = otherUser
    const user = session.user.id
    const search = async (q: string): Promise<string[]> =>
      (await call<{ paths: string[] }>(api, 'get', `${user}/search?q=${encodeURIComponent(q)}`)).paths

    await call(api, 'delete', user)
    await call(api, 'put', 'outage', { down: false })

    // Given a folder synchronised from the bucket.
    await call(api, 'put', `bucket/${user}/${FICHE}`, { text: fiche('Elle aime les pivoines.', 'Françoise aime les pivoines') })
    const first = await call<Sync>(api, 'post', `${user}/sync`)
    expect(first.ok).toBe(true)
    expect(await search('pivoines')).toContain(FICHE)

    // When the owner corrects the file in the bucket, Then the next synchronisation takes it back and the index follows.
    await call(api, 'put', `bucket/${user}/${FICHE}`, { text: fiche('Elle préfère les tulipes.', 'Françoise préfère les tulipes') })
    const second = await call<Sync>(api, 'post', `${user}/sync`)
    expect(second.pulled).toBeGreaterThanOrEqual(1)
    expect(await search('tulipes')).toContain(FICHE)
    expect(await search('pivoines')).not.toContain(FICHE)

    // When the bucket is cut and Maggie writes a fiche, Then the write waits and the memory is flagged late.
    await call(api, 'put', 'outage', { down: true })
    await call(api, 'post', `${user}/write`, { path: GIFT, text: fiche('Des fleurs pour son anniversaire.', 'Un cadeau pour Françoise') })
    expect((await call<Sync>(api, 'post', `${user}/sync`)).ok).toBe(false)
    await expect.poll(async () => (await call<State>(api, 'get', `${user}/state`)).degraded).toBe(true)
    expect((await call<State>(api, 'get', `${user}/state`)).outbox).toContain(GIFT)
    // The local copy and the index still answer.
    expect(await search('tulipes')).toContain(FICHE)
    expect(await search('anniversaire')).toContain(GIFT)

    // And she still answers the chat, honestly.
    await new DashboardPage(page).open()
    const ask = async (): Promise<string> =>
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
          { message: `Dis-moi, ta mémoire est-elle à jour ? (${Date.now()})`, token: session.token },
        )
        return assistantText(parseAgUiStream(body))
      })
    expect(await ask()).toContain('mon stockage ne répond plus')

    // When the bucket comes back, Then the waiting write is pushed, nothing lost.
    await call(api, 'put', 'outage', { down: false })
    const back = await call<Sync>(api, 'post', `${user}/sync`)
    expect(back.ok).toBe(true)
    expect(back.pushed).toBeGreaterThanOrEqual(1)
    const stored = await call<{ text: string }>(api, 'get', `bucket/${user}/${GIFT}`)
    expect(stored.text).toContain('Des fleurs pour son anniversaire.')
    const state = await call<State>(api, 'get', `${user}/state`)
    expect(state.degraded).toBe(false)
    expect(state.outbox).toEqual([])
    expect(await ask()).toContain('ma mémoire est à jour')

    // The index is disposable: emptied and rebuilt, the search finds everything again.
    await call(api, 'post', `${user}/rebuild`)
    expect(await search('tulipes')).toContain(FICHE)
    expect(await search('anniversaire')).toContain(GIFT)

    await call(api, 'delete', user)
  })
})

test('a fiche that evokes mothers day comes back on its own in the context sent to the model', async ({ otherUser }) => {
  await withLock(MEMORY_LOCK, async () => {
    const { page, api, session } = otherUser
    const user = session.user.id
    const roses = 'Famille/Françoise et les roses.md'

    await call(api, 'delete', user)
    await call(api, 'put', 'outage', { down: false })

    // Given a fiche that evokes « fête des mères », synchronised from the bucket.
    await call(api, 'put', `bucket/${user}/${roses}`, {
      text: `---\nresume: Françoise n'aime pas les roses\nevoque: [fête des mères, offrir des fleurs]\n---\n\nFrançoise n'aime pas les roses.\n`,
    })
    expect((await call<Sync>(api, 'post', `${user}/sync`)).ok).toBe(true)

    // When the owner speaks of a bouquet for mothers day, Then the fiche is in the context sent to the model:
    // the fake only answers when the recalled carte is in its system prompt.
    await new DashboardPage(page).open()
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
        { message: 'Un bouquet pour la fête des mères', token: session.token },
      )
      return assistantText(parseAgUiStream(body))
    })
    expect(answer).toContain('Évite les roses')
    expect(answer).not.toContain('aucun scénario')

    await call(api, 'delete', user)
  })
})
