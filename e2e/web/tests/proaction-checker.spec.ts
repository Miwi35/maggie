import { test, expect, e2eNow } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed } from '../helpers/mercure.js'

/**
 * A proaction writes only what an instruction says (MAG-943, extends MAG-103).
 *
 * The proaction « Ajoute du lait à la liste de courses (checker e2e) » makes Maggie call
 * `add_grocery_item` (`07e-proaction-checker-milk-due.yaml`). The checker reads the action against the
 * owner's planning instructions: with none that covers it the write waits for « Autoriser / Refuser »,
 * with one that says it word for word (`07d-checker-grocery-milk.yaml`) it runs.
 *
 * Its own account, serially: the interruption covers every window of its user.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

interface ListedProaction {
  id: string
  status: string
}

const PROMPT = 'Ajoute du lait à la liste de courses (checker e2e)'

test('a proaction write no instruction covers waits for « Autoriser / Refuser » and is not on the list', async ({ interruptedUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = interruptedUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  const instruction = await api.post('/agent/instructions', {
    data: { content: "Préviens-moi si l'enveloppe Loisirs dépasse 80 %", kind: 'planning' },
  })
  expect(instruction.status(), await instruction.text()).toBe(201)

  await runDueProaction(interruptedUser.api)

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText('Lait', { timeout: 20_000 })
  expect(JSON.stringify(await callMcpTool(api, 'get_grocery_list', {}))).not.toContain('Lait')

  // The card covers every window of this account: answer it through the API so the next journey starts clear.
  const pending = (await (await api.get('/agent/approvals?status=pending')).json()) as { id: string }[]
  for (const action of pending) {
    const denied = await api.post(`/agent/approvals/${action.id}/deny?channel=notification`)
    expect(denied.ok(), await denied.text()).toBe(true)
  }
})

test('a proaction write an instruction covers word for word runs without a validation', async ({ interruptedUser }) => {
  test.setTimeout(240_000)
  const { page, api, session } = interruptedUser
  await openSubscribed(page, () => new HomePage(page).open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

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

async function runDueProaction(api: Parameters<typeof callMcpTool>[0]): Promise<void> {
  const scheduled = await callMcpTool(api, 'schedule_proaction', {
    prompt: PROMPT,
    scheduled_at: new Date(e2eNow().getTime() + 30 * 60_000).toISOString(),
  })
  const created = scheduled.proaction as ListedProaction

  // Due now: moved to a minute ago, the way the agenda would.
  const moved = await api.patch(`/api/events/${created.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { startAt: new Date(e2eNow().getTime() - 60_000).toISOString() },
  })
  expect(moved.ok(), `PATCH /api/events/${created.id} answered ${moved.status()}: ${await moved.text()}`).toBe(true)

  await expect
    .poll(
      async () => {
        const all = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]
        return all.find((p) => p.id === created.id)?.status
      },
      { message: 'the proaction was never run', timeout: 150_000, intervals: [2_000] },
    )
    .toBe('completed')
}
