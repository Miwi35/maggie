import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DigestPage } from '../pages/DigestPage.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The morning digest end to end — MAG-709, rewritten by MAG-807 and MAG-843 (extends MAG-99).
 *
 * Given the digest instruction, an appointment today that the fake model's first answer does not name, and the fake model
 * (`06-planning-digest.yaml` schedules the digest two minutes ahead once it has read that instruction,
 * `98-proaction-digest-due.yaml` runs it), When the planning runs and the digest's hour comes — moved to a
 * minute ago, the stack's clock barely moves (MAG-234) — and the executor claims it, Then the thread holds
 * ONE message on the `digest` screen, with its audio, which announces itself (MAG-808) and carries a
 * `digest` block holding the text: it cites the weather (the simulated Open-Meteo) and the appointment with
 * its hour, written by the model after the code's one read-back (turn 4 of `98-proaction-digest-due.yaml`), and
 * the code adds no sentence of its own; a `message` notification opens the digest; the opening route
 * marks the block once and only once (the digest screen opens it, MAG-959); the journal shows the weather read and no `send_voice_message`.
 *
 * An account of its own: the planning reads its instructions, and no other journey's must match.
 */

test.use({ journeyAccount: 'standard' })

const INSTRUCTION = 'Tous les matins à 7 h, fais-moi le digest de ma journée, par écrit et en vocal (digest MAG-709).'

interface AgentMessage {
  id: string
  role: string
  content: string
  screen?: string
  audio?: { transcript: string }
  blocks?: { type: string; day: string; text: string; expiresAt: string; openedAt: string | null }[]
}

interface JournalRun {
  kind: string
  summary: string | null
  instructions: { content: string }[]
  scheduled: { id: string | null; summary: string }[]
  toolCalls: { name: string }[]
}

test('the digest instruction said once brings one message, read and heard, and a notification that opens the digest', async ({
  api,
  page,
}) => {
  // The executor claims what is due once a minute.
  test.setTimeout(240_000)

  // Given an appointment today, which the fake model's digest does not name.
  const today = parisDay()
  const agendas = await getCollection<{ id: string; name: string }>(api, '/api/agendas?itemsPerPage=100')
  const personal = agendas.find((agenda) => agenda.name === 'Perso')
  expect(personal, 'the account has no "Perso" agenda').toBeTruthy()
  const appointment = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      summary: 'Kiné',
      startAt: parisTime(today, '14:30:00'),
      endAt: parisTime(today, '15:30:00'),
      timeZone: 'Europe/Paris',
      agenda: `/api/agendas/${personal!.id}`,
    },
  })
  expect(appointment.status(), await appointment.text()).toBe(201)

  const created = await api.post('/agent/instructions', { data: { content: INSTRUCTION, kind: 'planning' } })
  expect(created.status(), await created.text()).toBe(201)

  // When the planning runs (« Relancer la planification maintenant »), it reads the instruction and schedules the digest.
  const planning = await api.post('/agent/journal/planning')
  expect(planning.status(), await planning.text()).toBe(200)
  const planned = (await planning.json()) as JournalRun
  expect(planned.instructions.map((i) => i.content)).toContain(INSTRUCTION)
  expect(planned.scheduled.map((s) => s.summary)).toEqual(['Digest du matin'])
  const id = planned.scheduled[0].id
  expect(id).toBeTruthy()

  // When its hour comes: moved to a minute ago, the way the agenda would move it.
  const moved = await api.patch(`/api/events/${id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { startAt: new Date(e2eNow().getTime() - 60_000).toISOString() },
  })
  expect(moved.ok(), `PATCH /api/events/${id} answered ${moved.status()}: ${await moved.text()}`).toBe(true)

  // Then the executor runs it: one message on the digest screen, with its audio.
  const digest = async () => {
    const messages = (await (await api.get('/agent/messages?limit=20')).json()) as AgentMessage[]
    return messages.filter(
      (m) => m.role === 'assistant' && m.screen === 'digest' && m.blocks?.some((b) => b.type === 'digest' && b.text.includes('digest MAG-709')),
    )
  }
  await expect.poll(async () => (await digest()).length, { message: 'the digest was never run', timeout: 180_000, intervals: [2_000] }).toBe(1)
  const [message] = await digest()
  const block = message.blocks!.find((b) => b.type === 'digest')!
  expect(message.content).toBe('Votre digest du matin est disponible.')
  expect(message.audio?.transcript).toContain('degrés')
  expect(block.text).toContain('degrés')
  expect(block.day).toBe(today)
  expect(block.expiresAt).toContain('T14:00:00')
  expect(block.openedAt).toBeNull()
  // The first answer missed the appointment: the model was told once, and its second answer names it.
  expect(block.text).toContain('Kiné à 14 h 30')
  expect(block.text).not.toContain('Vous avez aussi')
  // The first answer also said a meal was empty (MAG-861): the second does not.
  expect(block.text).not.toContain('Aucun')
  expect(block.text.indexOf('degrés')).toBeLessThan(block.text.indexOf('Kiné à 14 h 30'))

  // The chat shows the announcement and the voice bubble in the same message (MAG-925).
  const home = new HomePage(page)
  await home.open()
  // The unopened digest heads the Accueil.
  await expect(home.digestTile).toBeVisible()
  const chat = new ChatPanel(page)
  // Folded away, the portrait signals the unread digest (a dot: no server count yet) in the accent (MAG-966).
  await expect(chat.portraitBadge).toHaveCSS('background-color', 'rgb(166, 139, 255)', { timeout: 15_000 })
  await chat.open()
  const bubbleMessage = chat.panel.locator('#msg-' + message.id)
  await expect(bubbleMessage).toContainText('Votre digest du matin est disponible.')
  await expect(bubbleMessage.getByTestId('voice-bubble').getByTestId('voice-duration')).toBeVisible()

  // The chat gives its card, whose « Ouvrir » leads to the digest.
  await expect(bubbleMessage.getByTestId('moment-card')).toHaveCount(0)
  await expect(chat.momentCard(message.id)).toContainText('Digest du matin')
  const cardBox = await chat.momentCard(message.id).boundingBox()
  const panelBox = await chat.panel.boundingBox()
  expect(cardBox!.width / panelBox!.width).toBeGreaterThanOrEqual(0.8)
  expect((await chat.momentTitle(message.id).boundingBox())!.height).toBeLessThanOrEqual(22)
  expect(await chat.momentText(message.id).evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
  await chat.momentCard(message.id).getByRole('link', { name: 'Ouvrir le digest du matin' }).click()
  await expect(page).toHaveURL(new RegExp(`/digest\\?message=${message.id}$`))

  // The notification of the message opens the digest, not the thread.
  const notification = await waitForIndexed<{ type: string; relatedEntityIri: string | null }>(
    api,
    '/api/notifications?itemsPerPage=100',
    (n) => n.type === 'message' && n.relatedEntityIri === `/digest?message=${message.id}`,
    { what: 'The notification of the digest' },
  )
  expect(notification.relatedEntityIri).toBe(`/digest?message=${message.id}`)

  // The opening route marks the block once: the second call changes nothing.
  const opened = async () => {
    const messages = (await (await api.get('/agent/messages?screen=digest&limit=1')).json()) as AgentMessage[]
    return messages[0].blocks!.find((b) => b.type === 'digest')!.openedAt
  }
  const digestPage = new DigestPage(page)
  await digestPage.open(message.id)
  await expect(digestPage.trail).toContainText('Accueil')
  await expect(digestPage.word).toContainText('Kiné à 14 h 30')
  await expect(digestPage.card('weather')).toBeVisible()
  await expect(digestPage.card('agenda')).toContainText('Kiné')
  await expect.poll(opened, { message: 'the digest screen never marked the block opened' }).not.toBeNull()
  // Back on the Accueil, the opened digest has no tile any more.
  await home.open()
  await expect(home.digestTile).toHaveCount(0)
  const openedAt = await opened()
  const second = await api.post(`/agent/messages/${message.id}/digest/opened`)
  expect(second.status(), await second.text()).toBe(200)
  expect(await opened()).toBe(openedAt)
  const unknown = await api.post('/agent/messages/00000000-0000-0000-0000-000000000000/digest/opened')
  expect(unknown.status()).toBe(404)

  // The journal keeps the run: the weather was read, and the model did not call the voice tool.
  const journal = (await (await api.get('/agent/journal')).json()) as JournalRun[]
  const run = journal.find((entry) => entry.kind === 'proaction' && entry.summary === 'Digest du matin')
  const called = run?.toolCalls.map((c) => c.name) ?? []
  expect(called).toContain('get_weather')
  expect(called).not.toContain('send_voice_message')
})
