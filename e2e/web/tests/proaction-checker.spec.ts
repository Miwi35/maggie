import { test, expect, e2eNow } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed } from '../helpers/mercure.js'

/**
 * A proaction writes only what an instruction says (MAG-943, MAG-988, extends MAG-103).
 *
 * The proaction « Ajoute du lait à la liste de courses (checker e2e) » makes Maggie call
 * `add_grocery_item` (`07e-proaction-checker-milk-due.yaml`). The checker reads the action against the
 * owner's planning instructions: with none that covers it the write waits for « Autoriser / Refuser »,
 * with one that says it word for word (`07d-checker-grocery-milk.yaml`) it runs.
 *
 * Asked in the chat (MAG-977), the proaction keeps the owner's message as its origin and the checker reads it
 * too: « ajoute du lait » covers the write (`07f-origin-milk-asked.yaml`), « préviens-moi » covers none
 * although the model scheduled one (`07g-origin-warn-milk-asked.yaml`).
 *
 * Its own account (`checkerUser`), serially: the interruption covers every window of its user.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

interface ListedProaction {
  id: string
  status: string
  prompt: string
  origin: { kind: string; ref: string } | null
}

type Api = Parameters<typeof callMcpTool>[0]

const REMINDER_PROMPT = 'Rappel à délivrer : fermer les volets (rappel e2e).'
const PROMPT = 'Ajoute du lait à la liste de courses (checker e2e)'

test('a proaction write no instruction covers waits for « Autoriser / Refuser » and is not on the list', async ({ checkerUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = checkerUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()
  await clearAccount(api)

  const instruction = await api.post('/agent/instructions', {
    data: { content: "Préviens-moi si l'enveloppe Loisirs dépasse 80 %", kind: 'planning' },
  })
  expect(instruction.status(), await instruction.text()).toBe(201)

  await runDueProaction(api)

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText('Lait', { timeout: 20_000 })
  await expect(interruption).toContainText("Ajouter « Lait » à la liste de courses ? Je n'ai pas retrouvé votre demande.")
  expect(JSON.stringify(await callMcpTool(api, 'get_grocery_list', {}))).not.toContain('Lait')

  // The card covers every window of this account: answer it through the API so the next journey starts clear.
  const pending = (await (await api.get('/agent/approvals?status=pending')).json()) as { id: string }[]
  for (const action of pending) {
    const denied = await api.post(`/agent/approvals/${action.id}/deny?channel=notification`)
    expect(denied.ok(), await denied.text()).toBe(true)
  }
})

test('a proaction write an instruction covers word for word runs without a validation', async ({ checkerUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = checkerUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()
  await clearAccount(api)

  const instruction = await api.post('/agent/instructions', {
    data: { content: 'Chaque lundi, ajoute du lait à la liste de courses', kind: 'planning' },
  })
  expect(instruction.status(), await instruction.text()).toBe(201)

  await runDueProaction(api)

  await expect
    .poll(async () => JSON.stringify(await callMcpTool(api, 'get_grocery_list', {})), { timeout: 20_000 })
    .toContain('Lait')
  await expect(page.getByRole('alertdialog')).toContainText('Je m\'occupe du lait', { timeout: 20_000 })
  await expect(page.getByRole('button', { name: 'Autoriser' })).toHaveCount(0)
  expect(await (await api.get('/agent/approvals?status=pending')).json()).toEqual([])
})

// Reproduction of MAG-988: talking to the owner is never held for approval (owner, 23 Nov.).
test('a due reminder notifies the owner without asking to authorise it', async ({ checkerUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = checkerUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  await runDueProaction(api, REMINDER_PROMPT)

  // The reminder's own notification surfaces first (« Fermer les volets », « Compris »), then its run's message:
  // either way it says the reminder and never asks to authorise it.
  await expect(page.getByRole('alertdialog')).toContainText("C'est l'heure de fermer les volets", { timeout: 20_000 })
  await expect(page.getByRole('button', { name: 'Autoriser' })).toHaveCount(0)
  expect(await (await api.get('/agent/approvals?status=pending')).json()).toEqual([])
  expect(JSON.stringify(await callMcpTool(api, 'manage_notifications', { action: 'list' }))).toContain('Fermer les volets')
})

test('a proaction asked in the chat writes what the owner\'s message says, without a validation', async ({ checkerUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = checkerUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await clearAccount(api)

  const chat = new ChatPanel(page)
  await chat.send(`Dans 30 minutes, ajoute du lait à la liste de courses (origine e2e, pour ${inThirtyMinutes()})`)
  await chat.ensureClosed()

  const asked = await scheduledFromTheChat(api)
  expect(asked.origin?.kind).toBe('owner_message')
  await makeDue(api, asked.id)

  await expect
    .poll(async () => JSON.stringify(await callMcpTool(api, 'get_grocery_list', {})), { timeout: 20_000 })
    .toContain('Lait')
  await expect(page.getByRole('alertdialog')).toContainText('Je m\'occupe du lait', { timeout: 20_000 })
  await expect(page.getByRole('button', { name: 'Autoriser' })).toHaveCount(0)
  expect(await (await api.get('/agent/approvals?status=pending')).json()).toEqual([])
})

test('a proaction asked with « préviens-moi » writes nothing without a validation', async ({ checkerUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = checkerUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await clearAccount(api)

  const chat = new ChatPanel(page)
  await chat.send(`Dans 30 minutes, préviens-moi s'il manque du lait (origine e2e, pour ${inThirtyMinutes()})`)
  await chat.ensureClosed()

  // The model scheduled more than it was asked: adding the milk.
  const asked = await scheduledFromTheChat(api)
  expect(asked.origin?.kind).toBe('owner_message')
  await makeDue(api, asked.id)

  await expect(page.getByRole('alertdialog')).toContainText('Lait', { timeout: 20_000 })
  expect(JSON.stringify(await callMcpTool(api, 'get_grocery_list', {}))).not.toContain('Lait')

  const pending = (await (await api.get('/agent/approvals?status=pending')).json()) as { id: string }[]
  for (const action of pending) {
    const denied = await api.post(`/agent/approvals/${action.id}/deny?channel=notification`)
    expect(denied.ok(), await denied.text()).toBe(true)
  }
})

function inThirtyMinutes(): string {
  return new Date(e2eNow().getTime() + 30 * 60_000).toISOString()
}

/** No planning instruction and no « Lait » on the list: each journey starts from the owner's words alone. */
async function clearAccount(api: Api): Promise<void> {
  const instructions = (await (await api.get('/agent/instructions?kind=planning')).json()) as { id: string }[]
  for (const instruction of instructions) {
    const deleted = await api.delete(`/agent/instructions/${instruction.id}`)
    expect(deleted.ok(), await deleted.text()).toBe(true)
  }
  for (const id of itemIds(await callMcpTool(api, 'get_grocery_list', {}), 'Lait')) {
    await callMcpTool(api, 'remove_grocery_item', { groceryItemId: id })
  }
}

function itemIds(value: unknown, label: string): string[] {
  if (Array.isArray(value)) return [...new Set(value.flatMap((v) => itemIds(v, label)))]
  if (value === null || typeof value !== 'object') return []
  const record = value as Record<string, unknown>
  const own = record.label === label && typeof record.id === 'string' ? [record.id] : []
  return [...new Set([...own, ...Object.values(record).flatMap((v) => itemIds(v, label))])]
}

async function scheduledFromTheChat(api: Api): Promise<ListedProaction> {
  const listed = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]
  const asked = listed.find((p) => p.prompt === PROMPT && p.status === 'pending')
  expect(asked, JSON.stringify(listed)).toBeDefined()
  return asked!
}

async function runDueProaction(api: Api, prompt = PROMPT): Promise<void> {
  const scheduled = await callMcpTool(api, 'schedule_proaction', {
    prompt,
    kind: 'reminder',
    scheduled_at: inThirtyMinutes(),
  })
  await makeDue(api, (scheduled.proaction as ListedProaction).id)
}

async function makeDue(api: Api, id: string): Promise<void> {
  // Due now: moved to a minute ago, the way the agenda would.
  const moved = await api.patch(`/api/events/${id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { startAt: new Date(e2eNow().getTime() - 60_000).toISOString() },
  })
  expect(moved.ok(), `PATCH /api/events/${id} answered ${moved.status()}: ${await moved.text()}`).toBe(true)

  await expect
    .poll(
      async () => {
        const all = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]
        return all.find((p) => p.id === id)?.status
      },
      { message: 'the proaction was never run', timeout: 150_000, intervals: [2_000] },
    )
    .toBe('completed')
}
