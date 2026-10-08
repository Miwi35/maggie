import { test, expect } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { openSubscribed, publishOnHub } from '../helpers/mercure.js'

/**
 * Maggie speaking first (MAG-311, extends MAG-97 / MAG-99).
 *
 * Whatever she raises on her own reaches the open admin as an interruption: a
 * finished proaction, any notification (a reminder, a proaction, a task due…)
 * and an action waiting for the user's answer — the message, the action it
 * proposes and « Plus tard ». The deliveries are the updates the agent and the
 * API publish, sent here straight on the hub — the journey is about what the
 * admin does with them.
 *
 * It runs as the third account, serially. An interruption covers the whole
 * screen of every window the user has open, and these deliveries are published
 * to the user, not to a test: on an account another file drives they would pop
 * up over that file's clicks (and over each other's).
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const MESSAGE = 'Petit rappel : les poubelles sortent ce soir.'

const proaction = (userId: string, id: string) => ({
  id,
  userId,
  prompt: 'Rappelle-lui de sortir les poubelles',
  status: 'completed',
  response: MESSAGE,
  error: null,
  scheduledAt: '2026-10-07T18:00:00+00:00',
  createdAt: '2026-10-07T17:00:00+00:00',
  completedAt: '2026-10-07T18:00:01+00:00',
})

test('a proaction that completes interrupts the open admin, and « Plus tard » closes it', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  // An open conversation already shows what Maggie says: nothing to interrupt.
  await new ChatPanel(page).ensureClosed()

  await publishOnHub(page, `/proactions/${session.user.id}`, proaction(session.user.id, 'e2e-interruption-1'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await expect(interruption).toContainText(MESSAGE)
  await expect(interruption.getByRole('button', { name: 'Plus tard' })).toBeVisible()

  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()
})

test('Esc is « Plus tard »', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  // An open conversation already shows what Maggie says: nothing to interrupt.
  await new ChatPanel(page).ensureClosed()

  await publishOnHub(page, `/proactions/${session.user.id}`, proaction(session.user.id, 'e2e-interruption-2'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(interruption).toBeHidden()
})

const notification = (id: string, title: string, overrides: Record<string, unknown> = {}) => ({
  '@id': `/api/notifications/${id}`,
  id,
  type: 'reminder',
  title,
  body: '15',
  relatedEntityIri: null,
  readAt: null,
  createdAt: '2026-10-07T17:00:00+00:00',
  ...overrides,
})

test('an event reminder interrupts the open admin, and « Plus tard » closes it', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  const topic = `/users/${session.user.id}/api/notifications/e2e-reminder-1`
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)

  await publishOnHub(page, topic, notification('e2e-reminder-1', 'Rappel e2e : close 27'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await expect(interruption).toContainText('Rappel e2e : close 27')
  await expect(interruption).toContainText('Dans 15 min')
  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()
})

test('a notification created by a proaction interrupts, even with the chat open', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  // The open chat shows messages, not notifications: it does not spare this one.
  await new ChatPanel(page).open()

  await publishOnHub(
    page,
    `/users/${session.user.id}/api/notifications/e2e-proaction-1`,
    notification('e2e-proaction-1', 'Maggie a préparé ta liste', { type: 'proaction', body: 'Quatre articles ajoutés.' }),
  )

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText('Maggie a préparé ta liste')
  await expect(interruption).toContainText('Quatre articles ajoutés.')
  await interruption.getByRole('button', { name: 'Compris' }).click()
  await expect(interruption).toBeHidden()
})

test('three notifications arriving together pass one after the other, never twice', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  for (const [id, title] of [
    ['e2e-chain-1', 'Première'],
    ['e2e-chain-2', 'Deuxième'],
    ['e2e-chain-3', 'Troisième'],
  ]) {
    await publishOnHub(page, `/users/${session.user.id}/api/notifications/${id}`, notification(id, title, { body: null }))
  }
  // The same one again: it must not come back.
  await publishOnHub(page, `/users/${session.user.id}/api/notifications/e2e-chain-1`, notification('e2e-chain-1', 'Première', { body: null }))

  const interruption = page.getByRole('alertdialog')
  for (const title of ['Première', 'Deuxième', 'Troisième']) {
    await expect(interruption).toContainText(title)
    await interruption.getByRole('button', { name: 'Compris' }).click()
  }
  await expect(interruption).toBeHidden()
})

test('an action waiting for the user is asked with Autoriser and Refuser', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new DashboardPage(page)
  await page.route('**/agent/approvals/e2e-approval-1/approve', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  )
  await page.route('**/agent/approvals/e2e-approval-2/deny', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  )
  await openSubscribed(page, () => dashboard.open(), `/approvals/${session.user.id}`)

  const held = (id: string, summary: string) => ({
    id,
    userId: session.user.id,
    toolName: 'delete_event',
    arguments: {},
    summary,
    status: 'pending',
    expiresAt: '2099-01-01T00:00:00+00:00',
  })
  await publishOnHub(page, `/approvals/${session.user.id}`, held('e2e-approval-1', 'Supprimer la série « Psychomot »'))
  await publishOnHub(page, `/approvals/${session.user.id}`, held('e2e-approval-2', 'Supprimer l’événement « Dentiste »'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText('Supprimer la série « Psychomot »')
  const approved = page.waitForRequest((request) => request.url().endsWith('/agent/approvals/e2e-approval-1/approve'))
  await interruption.getByRole('button', { name: 'Autoriser' }).click()
  await approved

  await expect(interruption).toContainText('Supprimer l’événement « Dentiste »')
  const denied = page.waitForRequest((request) => request.url().endsWith('/agent/approvals/e2e-approval-2/deny'))
  await interruption.getByRole('button', { name: 'Refuser' }).click()
  await denied
  await expect(interruption).toBeHidden()
})
