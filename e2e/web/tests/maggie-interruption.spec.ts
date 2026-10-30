import { test, expect, e2eNow, seedDate } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { toolResults } from '../helpers/agui.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed, publishOnHub } from '../helpers/mercure.js'
import { NotificationBell } from '../pages/NotificationBell.js'
import { TasksPage } from '../pages/TasksPage.js'

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
 *
 * The last test is the real thing, end to end (MAG-327, extends MAG-103): a
 * proaction asked for in the chat, filed in the « Maggie » agenda by the API,
 * claimed and run by the agent's executor, then marked done. It lives here for
 * the same reason: once it runs, it speaks to this account's every window.
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
  const dashboard = new HomePage(page)
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

test('the answer of a silent run (replan, departure check) never interrupts (MAG-680)', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  const topic = `/proactions/${session.user.id}`
  await publishOnHub(page, topic, {
    ...proaction(session.user.id, 'e2e-interruption-silent'),
    summary: 'Replanifier les trajets',
    response: 'La replanification est terminée : rien à recalculer, monsieur.',
    silent: true,
  })
  // An ordinary one right behind: the feed is live, and it is the first and only thing said.
  await publishOnHub(page, topic, { ...proaction(session.user.id, 'e2e-interruption-ordinary'), silent: false })

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText(MESSAGE)
  await expect(interruption).not.toContainText('rien à recalculer')
  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()
})

test('Esc is « Plus tard »', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new HomePage(page)
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
  const dashboard = new HomePage(page)
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

test('« C\'est noté » on an event reminder marks it read and closes it, and the bell stops counting it', async ({
  interruptedUser,
}) => {
  const { page, api } = interruptedUser
  const title = `Rappel e2e, c'est noté ${test.info().retry}`
  const bell = new NotificationBell(page)
  const unread = async () =>
    (await getCollection<{ readAt: string | null }>(api, '/api/notifications?itemsPerPage=100')).filter((n) => !n.readAt).length

  const before = await unread()
  await openSubscribed(page, () => bell.goto('/'))
  await bell.expectUnreadCount(before, 5_000)

  expect(
    (
      await callMcpTool(api, 'manage_notifications', {
        action: 'create',
        type: 'reminder',
        title,
        body: '15',
        relatedEntityIri: '/api/events/e2e-reminder-ack',
      })
    ).error,
  ).toBeUndefined()

  const interruption = page.getByRole('alertdialog').filter({ hasText: title })
  await expect(interruption).toBeVisible({ timeout: 5_000 })
  await expect(interruption.getByRole('button', { name: "C'est noté" })).toBeVisible()
  await expect(interruption.getByRole('button', { name: "Voir l'événement" })).toBeVisible()
  await expect(interruption.getByRole('button', { name: 'Plus tard' })).toBeVisible()
  await bell.expectUnreadCount(before + 1, 5_000)

  const marked = page.waitForRequest((request) => request.method() === 'PATCH' && request.url().includes('/api/notifications/'))
  await interruption.getByRole('button', { name: "C'est noté" }).click()
  expect(JSON.parse((await marked).postData() ?? '{}')).toEqual({ readAt: expect.any(String) })

  await expect(interruption).toBeHidden()
  await bell.expectUnreadCount(before, 5_000)
  // Still on the dashboard: acknowledging does not open the event.
  await expect(page).not.toHaveURL(/calendar/)
  // Read for good — not only hidden until the « Plus tard » delay is up.
  await waitForIndexed(api, '/api/notifications?itemsPerPage=100', (n) => {
    const row = n as { title?: string; readAt?: string | null }
    return row.title === title && Boolean(row.readAt)
  }, { what: `The notification "${title}" marked read` })
})

test('a task alert « Dans 3 j » interrupts, and « Voir la tâche » opens the task', async ({ interruptedUser }) => {
  const { page, api, session } = interruptedUser
  const title = `Dans 3 j : Déclarer les impôts ${test.info().retry}`
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  // What the morning cron files (MAG-410): the cron itself is run by the smoke journey, which
  // no browser can; the notification it creates goes through the same command as this one.
  expect(
    (
      await callMcpTool(api, 'manage_notifications', {
        action: 'create',
        type: 'task_due',
        title,
        relatedEntityIri: '/api/tasks/e2e-task-due-1',
      })
    ).error,
  ).toBeUndefined()

  const interruption = page.getByRole('alertdialog').filter({ hasText: title })
  await expect(interruption).toBeVisible({ timeout: 5_000 })
  await interruption.getByRole('button', { name: 'Voir la tâche' }).click()
  await expect(page).toHaveURL(/#\/tasks\?task=e2e-task-due-1$/)
})

test('touching a task alert in the bell opens the task board on that task, highlighted (MAG-410 recette, MAG-640)', async ({
  interruptedUser,
}) => {
  const { page, api } = interruptedUser
  const attempt = test.info().retry
  const taskTitle = `Appeler le garage ${attempt}`
  const title = `Dans 3 j : ${taskTitle}`
  const created = await api.post('/api/tasks', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: { title: taskTitle, dueDate: seedDate(3) },
  })
  expect(created.ok(), `POST /api/tasks answered ${created.status()}`).toBe(true)
  const taskId = String(((await created.json()) as { id: string }).id)
  const bell = new NotificationBell(page)
  await openSubscribed(page, () => bell.goto('/'))

  expect(
    (
      await callMcpTool(api, 'manage_notifications', {
        action: 'create',
        type: 'task_due',
        title,
        relatedEntityIri: `/api/tasks/${taskId}`,
      })
    ).error,
  ).toBeUndefined()

  const interruption = page.getByRole('alertdialog').filter({ hasText: title })
  await expect(interruption).toBeVisible({ timeout: 5_000 })
  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()

  await bell.open()
  await bell.item(title).click()

  // Due in three days: « Aujourd'hui » does not list it, the page follows it to « À venir » (MAG-644).
  await expect(page).toHaveURL(new RegExp(`#/tasks/upcoming\\?task=${taskId}$`))
  const board = new TasksPage(page)
  await expect(board.heading).toBeVisible()
  await expect(board.row(taskTitle)).toBeVisible()
  await expect(board.row(taskTitle)).toHaveAttribute('aria-current', 'true')
})

test('a notification created by a proaction interrupts, even with the chat open', async ({ interruptedUser }) => {
  const { page, session } = interruptedUser
  const dashboard = new HomePage(page)
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
  const dashboard = new HomePage(page)
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
  const dashboard = new HomePage(page)
  await page.route('**/agent/approvals/e2e-approval-1/approve*', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  )
  await page.route('**/agent/approvals/e2e-approval-2/deny*', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  )
  await openSubscribed(page, () => dashboard.open(), `/approvals/${session.user.id}`)
  // An open conversation shows the action as a card (MAG-6): nothing to interrupt.
  await new ChatPanel(page).ensureClosed()

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
  const approved = page.waitForRequest((request) => request.url().endsWith('/agent/approvals/e2e-approval-1/approve?channel=notification'))
  await interruption.getByRole('button', { name: 'Autoriser' }).click()
  await approved

  await expect(interruption).toContainText('Supprimer l’événement « Dentiste »')
  const denied = page.waitForRequest((request) => request.url().endsWith('/agent/approvals/e2e-approval-2/deny?channel=notification'))
  await interruption.getByRole('button', { name: 'Refuser' }).click()
  await denied
  await expect(interruption).toBeHidden()
})

interface ListedProaction {
  id: string
  prompt: string
  status: string
  response: string | null
}

interface StoredEvent {
  id: string
  summary: string
  agenda: string
}

test('a proaction asked for in the chat sits in the « Maggie » agenda, runs once due, and is marked done', async ({ interruptedUser }) => {
  // The executor claims due proactions once a minute: up to a minute of waiting, then the run.
  test.setTimeout(240_000)
  const { page, api, session } = interruptedUser
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/chat/${session.user.id}`)

  // The fake cannot work out « dans 2 minutes »: the sentence names the instant, on the stack's clock.
  const inTwoMinutes = new Date(e2eNow().getTime() + 120_000).toISOString()
  const events = await new ChatPanel(page).send(`Rappelle-moi dans 2 minutes de boire un verre d'eau (pour ${inTwoMinutes})`)
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'schedule_proaction', status: 'success' })]),
  )

  // Given the proaction, pending, in the « Maggie » agenda.
  const listed = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]
  const scheduled = listed.find((p) => p.prompt === "Rappelle à l'utilisateur de boire un verre d'eau." && p.status === 'pending')
  expect(scheduled, JSON.stringify(listed)).toBeDefined()
  const id = scheduled!.id

  const read = await api.get(`/api/events/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(read.status()).toBe(200)
  const event = (await read.json()) as StoredEvent
  expect(event.summary).toBe("Verre d'eau")
  const agenda = await waitForIndexed<{ '@id': string; name: string }>(api, '/api/agendas?module=agent', (a) => a['@id'] === event.agenda, {
    what: 'The « Maggie » agenda',
  })
  expect(agenda.name).toBe('Maggie')

  // When its two minutes have passed. The stack's clock barely moves (MAG-234), so
  // the minutes are skipped the way the agenda would move it: to a minute ago.
  const moved = await api.patch(`/api/events/${id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { startAt: new Date(e2eNow().getTime() - 60_000).toISOString() },
  })
  expect(moved.ok(), `PATCH /api/events/${id} answered ${moved.status()}: ${await moved.text()}`).toBe(true)

  // Then the executor runs it, and the API marks it done with what Maggie said…
  await expect
    .poll(
      async () => {
        const all = (await callMcpTool(api, 'list_proactions', {})).proactions as ListedProaction[]
        const run = all.find((p) => p.id === id)
        return run ? { status: run.status, response: run.response } : null
      },
      { message: 'the proaction was never run', timeout: 180_000, intervals: [2_000] },
    )
    .toEqual({ status: 'completed', response: "C'est l'heure de boire un verre d'eau !" })

  // …which is in the chat, once.
  const messages = (await (await api.get('/agent/messages?limit=10')).json()) as { role: string; content: string }[]
  expect(messages.filter((m) => m.role === 'assistant' && m.content === "C'est l'heure de boire un verre d'eau !")).toHaveLength(1)
})

/**
 * The envelope alert, end to end (MAG-49, extends MAG-103 and MAG-102): a bank
 * sync — Enable Banking simulated by WireMock — brings a 40 € cinema ticket to
 * a « Loisirs » already at 70 % of its 300 €. Maggie interrupts with where the
 * envelope stands, offers to open the envelopes, and a second sync of the same
 * month brings nothing new and says nothing. Its own account: the alert goes
 * out once per period, and it covers every window of its user.
 */
test('a bank sync that takes an envelope past 80 % interrupts, once, and « Voir les enveloppes » opens them', async ({
  envelopeAlertUser,
}) => {
  const { page, api, session } = envelopeAlertUser
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  const sync = async () => {
    const response = await api.post('/api/finance/bank-connections/sync', { headers: { 'Content-Type': 'application/json' } })
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()) as { imported: number }
  }

  expect((await sync()).imported).toBe(1)

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible({ timeout: 10_000 })
  await expect(interruption).toContainText('Loisirs : 83 % du budget (250 / 300 €)')
  await expect(interruption.getByRole('button', { name: "C'est noté" })).toBeVisible()
  await expect(interruption.getByRole('button', { name: 'Voir les enveloppes' })).toBeVisible()

  await interruption.getByRole('button', { name: 'Voir les enveloppes' }).click()
  await expect(interruption).toBeHidden()
  await expect(page).toHaveURL(/\/envelopes/)

  // The same month, nothing new crossed: nothing to say.
  expect((await sync()).imported).toBe(0)
  await expect(interruption).toBeHidden({ timeout: 5_000 })
  const alerts = (await getCollection<{ type: string; title: string }>(api, '/api/notifications?itemsPerPage=100')).filter(
    (n) => n.type === 'finance' && n.title.startsWith('Loisirs'),
  )
  expect(alerts).toHaveLength(1)
})
