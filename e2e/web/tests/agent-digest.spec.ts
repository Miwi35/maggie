import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'

/**
 * The morning digest end to end — MAG-709, rewritten by MAG-807 (extends MAG-99).
 *
 * Given the digest instruction, an appointment today that the fake model never names, and the fake model
 * (`06-planning-digest.yaml` schedules the digest two minutes ahead once it has read that instruction,
 * `98-proaction-digest-due.yaml` runs it), When the planning runs and the digest's hour comes — moved to a
 * minute ago, the stack's clock barely moves (MAG-234) — and the executor claims it, Then the thread holds
 * ONE message on the `digest` screen, with its audio, which cites the weather (the simulated Open-Meteo)
 * and ends with the code's sentence naming the appointment and its hour; a `message` notification opens
 * the digest; the journal shows the weather read and no `send_voice_message`.
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
    return messages.filter((m) => m.role === 'assistant' && m.screen === 'digest' && m.content.includes('digest MAG-709'))
  }
  await expect.poll(async () => (await digest()).length, { message: 'the digest was never run', timeout: 180_000, intervals: [2_000] }).toBe(1)
  const [message] = await digest()
  expect(message.audio?.transcript).toContain('degrés')
  expect(message.content).toContain('degrés')
  // The model never named the appointment, even after the rewrite: the code appends it, with its hour,
  // after the weather (the seeded agenda's other appointments are appended too, in the day's order).
  expect(message.content).toContain('Vous avez aussi Kiné à 14 h 30.')
  expect(message.content.indexOf('degrés')).toBeLessThan(message.content.indexOf('Vous avez aussi Kiné'))

  // The notification of the message opens the digest, not the thread.
  const notification = await waitForIndexed<{ type: string; relatedEntityIri: string | null }>(
    api,
    '/api/notifications?itemsPerPage=100',
    (n) => n.type === 'message' && n.relatedEntityIri === `/digest?message=${message.id}`,
    { what: 'The notification of the digest' },
  )
  expect(notification.relatedEntityIri).toBe(`/digest?message=${message.id}`)

  // The journal keeps the run: the weather was read, and the model did not call the voice tool.
  const journal = (await (await api.get('/agent/journal')).json()) as JournalRun[]
  const run = journal.find((entry) => entry.kind === 'proaction' && entry.summary === 'Digest du matin')
  const called = run?.toolCalls.map((c) => c.name) ?? []
  expect(called).toContain('get_weather')
  expect(called).not.toContain('send_voice_message')
})
