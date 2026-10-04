import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { openMercureProbe } from '../helpers/mercure.js'
import { PreferencesPage } from '../pages/PreferencesPage.js'

/**
 * The notes' bucket is the source of truth, the index follows (MAG-195).
 *
 * The bucket is the agent's in-memory fake here (`MEMORY_BUCKET=fake`): `/agent/e2e/memory/*`
 * edits it the way the owner's editor would, takes it down and brings it back. Maggie's side
 * is the production one — the reconciler, the outbox, the volatile prompt block — and the
 * model is scripted by 13/14/15-memory-note-*.yaml, whose `system_contains` makes each answer
 * reachable only if the note really reached the prompt.
 *
 * As the other seeded account, like the journeys that talk to Maggie in chat.spec.ts: this one
 * owns no state anyone else asserts on. Serial, because the bucket and its outage switch are
 * shared by the whole agent.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const QUESTION = 'Quel est le code de la porte ?'
const E2E_TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'

function note(body: string, id?: string): string {
  return `---\n${id ? `id: ${id}\n` : ''}title: Code de la porte\ntags: [maison]\n---\n${body}\n`
}

async function agent(api: APIRequestContext, method: 'get' | 'put' | 'post', path: string, data?: unknown) {
  const response = await api[method](`/agent/e2e/memory${path}`, {
    headers: { 'X-E2E-Token': E2E_TOKEN },
    ...(data === undefined ? {} : { data }),
  })
  expect(response.status(), `${method} ${path}`).toBe(200)

  return response.json()
}

async function ask(api: APIRequestContext): Promise<string> {
  const response = await api.post('/agent/chat', { data: { message: QUESTION } })
  expect(response.status()).toBe(200)

  return String((await response.json()).response)
}

test.afterEach(async ({ otherUser }) => {
  await agent(otherUser.api, 'put', '/bucket/outage', { down: false })
})

test('a note edited in the bucket is reindexed and Maggie answers with the new version', async ({ otherUser }) => {
  const { api, page, session } = otherUser
  const key = `${session.user.id}/code-porte.md`

  await new PreferencesPage(page).open()
  const probe = await openMercureProbe(page, [`/memory/${session.user.id}`])

  await agent(api, 'put', '/bucket/object', { key, content: note('Le code de la porte est 4321') })
  expect(await ask(api)).toContain('4321')
  await probe.waitFor((message) => message.parsed?.type === 'created')

  // The note had no id: the reconciler stamped one and wrote it back into the file.
  const stamped = await agent(api, 'get', `/bucket/object?key=${encodeURIComponent(key)}`)
  const id = /^id: ([0-9A-HJKMNP-TV-Z]{26})$/m.exec(String(stamped.content))?.[1]
  expect(id, 'the file should carry the id the reconciler gave it').toBeTruthy()

  await agent(api, 'put', '/bucket/object', { key, content: note('Le code de la porte est 8765', id) })
  expect(await ask(api)).toContain('8765')
  await probe.waitFor((message) => message.parsed?.type === 'updated')

  await probe.close()
})

test('with the bucket down Maggie still answers, the write waits, and it is pushed when the bucket is back', async ({
  otherUser,
}) => {
  const { api, session } = otherUser
  const written = { title: 'Wifi invités', body: 'Le mot de passe du wifi invités est bleu-ciel' }

  await agent(api, 'put', '/bucket/outage', { down: true })

  const answer = await ask(api)
  expect(answer, 'the index must keep answering, and say where it reads from').toContain('injoignable')
  expect(answer).toContain('8765')

  const pending = await agent(api, 'post', '/notes', { user_id: session.user.id, ...written })
  expect(pending.status).toBe('pending')
  expect((await agent(api, 'get', '/outbox')).pending).toBeGreaterThanOrEqual(1)
  expect((await agent(api, 'get', '/bucket/keys')).keys).not.toContain(pending.path)

  await agent(api, 'put', '/bucket/outage', { down: false })
  expect((await agent(api, 'post', '/sync')).ok).toBe(true)

  expect((await agent(api, 'get', '/outbox')).pending).toBe(0)
  const pushed = await agent(api, 'get', `/bucket/object?key=${encodeURIComponent(pending.path)}`)
  expect(pushed.content).toContain(written.body)
})
