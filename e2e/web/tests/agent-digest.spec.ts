import { test, expect, e2eNow } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'

/**
 * The morning digest end to end — MAG-709 (digest du matin, part 5, extends MAG-99).
 *
 * Given the digest instruction and the fake model (`06-planning-digest.yaml` schedules the digest two
 * minutes ahead once it has read that instruction, `98-proaction-digest-due.yaml` runs it), When the
 * planning runs and the digest's hour comes — moved to a minute ago, the stack's clock barely moves
 * (MAG-234) — and the executor claims it, Then the thread holds a written message citing the
 * weather — the simulated Open-Meteo says 70 % of rain — and a vocal, both on the `digest` screen; a
 * `message` notification opens the digest; the journal shows the instruction read and the weather call.
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

test('the digest instruction said once brings a written digest, a vocal and a notification that opens the digest', async ({
  api,
}) => {
  // The executor claims what is due once a minute.
  test.setTimeout(240_000)

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

  // Then the executor runs it: a vocal and a written message, both on the digest screen.
  const digest = async () => {
    const messages = (await (await api.get('/agent/messages?limit=20')).json()) as AgentMessage[]
    return messages.filter((m) => m.role === 'assistant' && m.screen === 'digest' && m.content.includes('digest MAG-709'))
  }
  await expect.poll(async () => (await digest()).length, { message: 'the digest was never run', timeout: 180_000, intervals: [2_000] }).toBe(2)
  const messages = await digest()
  const vocal = messages.find((m) => m.audio !== undefined)
  const written = messages.find((m) => m.audio === undefined)
  expect(vocal?.audio?.transcript).toContain('pleuvra')
  expect(written?.content).toContain('Météo')
  expect(written?.content).toContain('70 %')

  // The notification of the written message opens the digest, not the thread.
  const notification = await waitForIndexed<{ type: string; relatedEntityIri: string | null }>(
    api,
    '/api/notifications?itemsPerPage=100',
    (n) => n.type === 'message' && n.relatedEntityIri === `/digest?message=${written!.id}`,
    { what: 'The notification of the digest' },
  )
  expect(notification.relatedEntityIri).toBe(`/digest?message=${written!.id}`)

  // The journal keeps the run: the weather was read, the vocal sent.
  const journal = (await (await api.get('/agent/journal')).json()) as JournalRun[]
  const run = journal.find((entry) => entry.kind === 'proaction' && entry.summary === 'Digest du matin')
  expect(run?.toolCalls.map((c) => c.name)).toEqual(expect.arrayContaining(['get_weather', 'send_voice_message']))
})
