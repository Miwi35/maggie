import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useCallback, useEffect, useState } from 'react'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../../../theme'
import type { AgendaHomeEvent } from '../../../home/cards/agendaTimeline'
import type { Trip } from '../../../calendar/trips'
import type { BilanSession } from '../bilanSession'
import { useDemainSlot } from './DemainStep'

const TODAY = '2026-11-23'
const TOMORROW = '2026-11-24'

const { agenda, health } = vi.hoisted(() => ({ agenda: { value: null as unknown }, health: { value: null as unknown } }))
vi.mock('../../../home/useAgendaCardState', () => ({ useAgendaCardState: () => ({ state: agenda.value, loading: false, error: false, retry: () => undefined }) }))
vi.mock('../../../home/useHealthCardState', () => ({ useHealthCardState: () => ({ state: health.value, loading: false, error: false, retry: () => undefined }) }))

const event = (id: string, summary: string, start: string, location: string | null = null): AgendaHomeEvent =>
  ({ id, summary, location, locationShort: location, allDay: false, startAt: `${TOMORROW}T${start}:00+01:00`, endAt: `${TOMORROW}T${start}:00+01:00`, timeZone: 'Europe/Paris', status: 'confirmed' }) as AgendaHomeEvent

const task = (id: string, title: string, patch: Record<string, unknown> = {}) => ({ id, title, priority: 'normal', criticality: 'normal', urgency: null, dueDate: null, completedAt: null, ...patch })

let tasks: Record<string, ReturnType<typeof task>>
let calls: { url: string; method: string; body: unknown }[]
let refuse: string | null

const json = (body: unknown, code = 200) => Promise.resolve(new Response(JSON.stringify(body), { status: code }))

beforeEach(() => {
  tasks = {
    A: task('A', 'Trier les papiers', { postponedSince: `${TODAY}` }),
    B: task('B', 'Appeler le garage', { dueDate: '2026-11-30' }),
    C: task('C', 'Ranger les photos'),
    J: task('J', 'Payer la cantine', { scheduledMoment: 'time', scheduledTime: '09:30' }),
  }
  calls = []
  refuse = null
  agenda.value = { date: TOMORROW, now: `${TODAY}T20:00:00+01:00`, trips: [], events: [event('E1', 'Dentiste', '09:00')] }
  health.value = { day: 'tomorrow', steps: null, balance: null, fast: null, workout: { id: 'W', title: 'Course', time: '18:30', durationMinutes: 45 } }
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : null })
      if (method === 'POST' && url.includes('/agent/chat')) return json({}, refuse === 'chat' ? 500 : 200)
      if (method === 'PATCH') return json({}, refuse === 'patch' ? 500 : 200)
      const found = url.match(/\/tasks\/([^/?]+)$/)

      return found && tasks[found[1]] ? json(tasks[found[1]]) : json({}, 404)
    }),
  )
})

afterEach(() => vi.unstubAllGlobals())

let latest: BilanSession
let plan: () => Promise<boolean>

const Harness = ({ state }: { state: Record<string, unknown> }) => {
  const [session, setSession] = useState<BilanSession>({ day: TODAY, step: 'demain', state })
  const amend = useCallback((change: (current: BilanSession) => Record<string, unknown>) => setSession((current) => ({ ...current, state: { ...current.state, ...change(current) } })), [])
  const slot = useDemainSlot(TODAY, session, amend, () => undefined)
  useEffect(() => {
    latest = session
    plan = slot.plan
  })

  return (
    <div>
      <p data-testid="say">{slot.say}</p>
      {slot.asks?.map((ask) => (
        <button key={ask.label} type="button" onClick={ask.onClick}>
          {ask.label}
        </button>
      ))}
      {slot.content}
    </div>
  )
}

const renderStep = (state: Record<string, unknown>, theme = veilleuseDarkTheme) =>
  render(
    <ThemeProvider theme={theme}>
      <Harness state={state} />
    </ThemeProvider>,
  )

const prepared = {
  tomorrowTasks: [{ taskId: 'A', title: 'Trier les papiers', reason: 'Vous l’aviez reportée hier' }, { taskId: 'B', title: 'Appeler le garage' }],
  tomorrowSpares: [{ taskId: 'C', title: 'Ranger les photos' }],
}

const titles = () => screen.getAllByTestId('bilan-planned-task').map((line) => line.textContent ?? '')

describe('DemainStep', () => {
  test('lists the programme: events and the planned session', async () => {
    renderStep(prepared)

    const programme = await screen.findByTestId('bilan-programme')
    expect(programme).toHaveTextContent('Mardi 24 nov.')
    expect(programme).toHaveTextContent('Dentiste')
    expect(programme).toHaveTextContent('Course · 45 min')
  })

  test('lists Maggie’s tasks with those left for tomorrow first, the reason before the meta', async () => {
    renderStep({ ...prepared, answers: { tasks: [{ taskId: 'J', choice: 'tomorrow' }] } })

    const card = await screen.findByTestId('bilan-planned')
    await waitFor(() => expect(within(card).getAllByTestId('bilan-planned-task')).toHaveLength(3))
    expect(card).toHaveTextContent('3 tâches')
    const lines = titles()
    expect(lines[0]).toContain('Payer la cantine')
    expect(lines[0]).toContain('09:30')
    expect(lines[1]).toContain('Vous l’aviez reportée hier')
    expect(lines[2]).toContain('limite lun. 30')
  })

  test('the card is not drawn without a task', async () => {
    renderStep({})

    await screen.findByTestId('bilan-programme')
    expect(screen.queryByTestId('bilan-planned')).not.toBeInTheDocument()
  })

  test('⇄ takes the first spare and sends the task out last in the reserve', async () => {
    renderStep(prepared)
    await screen.findByTestId('bilan-planned')
    await waitFor(() => expect(titles()).toHaveLength(2))

    await userEvent.click(screen.getByRole('button', { name: 'Échanger « Trier les papiers » contre une autre tâche' }))

    await waitFor(() => expect(latest.state.tomorrowSpares).toEqual([{ taskId: 'A', title: 'Trier les papiers' }]))
    expect(latest.state.tomorrowTasks).toEqual([{ taskId: 'C', title: 'Ranger les photos' }, { taskId: 'B', title: 'Appeler le garage' }])
    await waitFor(() => expect(titles()[0]).toContain('Ranger les photos'))
  })

  test('swapping a task left at Journée puts it off', async () => {
    renderStep({ ...prepared, tomorrowTasks: [], answers: { tasks: [{ taskId: 'J', choice: 'tomorrow' }] } })
    await waitFor(() => expect(titles()).toHaveLength(1))

    await userEvent.click(screen.getByRole('button', { name: 'Échanger « Payer la cantine » contre une autre tâche' }))

    await waitFor(() => expect(latest.state.answers).toMatchObject({ tasks: [{ taskId: 'J', choice: 'later' }] }))
    expect(calls.find((call) => call.method === 'PATCH')).toMatchObject({ url: expect.stringContaining('/tasks/J'), body: { dueDate: null } })
  })

  test('a refused swap leaves the list and says so', async () => {
    refuse = 'patch'
    renderStep({ ...prepared, tomorrowTasks: [], answers: { tasks: [{ taskId: 'J', choice: 'tomorrow' }] } })
    await waitFor(() => expect(titles()).toHaveLength(1))

    await userEvent.click(screen.getByRole('button', { name: 'Échanger « Payer la cantine » contre une autre tâche' }))

    expect(await screen.findByTestId('bilan-demain-message')).toHaveTextContent('Impossible d’échanger « Payer la cantine » : réessayez.')
    expect(titles()).toHaveLength(1)
    expect(latest.state.tomorrowSpares).toEqual(prepared.tomorrowSpares)
  })

  test('no spare, no ⇄ button', async () => {
    renderStep({ ...prepared, tomorrowSpares: [] })
    await waitFor(() => expect(titles()).toHaveLength(2))

    expect(screen.queryByRole('button', { name: /Échanger/ })).not.toBeInTheDocument()
  })

  test('a trip answer sends the exact message and is recorded; the question is not asked again', async () => {
    const trip = { id: 'T1', event: '/api/events/E2', mode: 'drive', durationMinutes: 20, departAt: `${TOMORROW}T08:00:00+01:00` } as Trip
    agenda.value = { date: TOMORROW, now: `${TODAY}T20:00:00+01:00`, trips: [trip], events: [event('E2', 'Dentiste', '09:00', 'Rennes')] }
    renderStep({ answers: { trips: ['E2:origin'] } })

    expect(screen.getByTestId('say')).toHaveTextContent('Comment allez-vous à Rennes ?')
    await userEvent.click(screen.getByRole('button', { name: 'Voiture' }))

    await waitFor(() => expect(calls.find((call) => call.url.includes('/agent/chat'))?.body).toEqual({ message: 'Bilan du soir, trajet de demain · événement E2 « Dentiste » : j’y vais en voiture.' }))
    await waitFor(() => expect((latest.state.answers as { trips: string[] }).trips).toEqual(['E2:mode', 'E2:origin']))
    expect(screen.getByTestId('say')).toHaveTextContent('Où vous garez-vous à Rennes ?')
  })

  test('a refused trip answer is taken back', async () => {
    refuse = 'chat'
    const trip = { id: 'T1', event: '/api/events/E2', mode: 'drive', durationMinutes: 20, departAt: `${TOMORROW}T08:00:00+01:00` } as Trip
    agenda.value = { date: TOMORROW, now: `${TODAY}T20:00:00+01:00`, trips: [trip], events: [event('E2', 'Dentiste', '09:00', 'Rennes')] }
    renderStep({ answers: { trips: ['E2:origin'] } })

    await userEvent.click(screen.getByRole('button', { name: 'Voiture' }))

    expect(await screen.findByTestId('bilan-demain-message')).toHaveTextContent('Impossible d’envoyer votre réponse')
    expect((latest.state.answers as { trips: string[] }).trips).toEqual(['E2:origin'])
  })

  test('good night plans the shown tasks by scheduledDate, never dueDate, and releases a reserve task planned tomorrow', async () => {
    tasks.C = task('C', 'Ranger les photos', { scheduledDate: `${TOMORROW}T00:00:00+01:00` })
    renderStep(prepared)
    await waitFor(() => expect(titles()).toHaveLength(2))

    await expect(plan()).resolves.toBe(true)

    const patches = calls.filter((call) => call.method === 'PATCH')
    expect(patches.map((call) => call.body)).toEqual([
      { scheduledDate: TOMORROW },
      { scheduledDate: TOMORROW },
      { scheduledDate: null, scheduledMoment: null, scheduledTime: null },
    ])
    expect(patches.map((call) => call.url.split('/').pop())).toEqual(['A', 'B', 'C'])
  })

  test('a done or deleted task is neither shown nor planned', async () => {
    tasks.A = task('A', 'Trier les papiers', { completedAt: `${TODAY}T10:00:00+01:00` })
    delete tasks.B
    renderStep(prepared)

    await waitFor(() => expect(calls.filter((call) => call.method === 'GET' && /\/tasks\/[AB]$/.test(call.url))).toHaveLength(2))
    await screen.findByTestId('bilan-programme')
    await waitFor(() => expect(screen.queryByTestId('bilan-planned')).not.toBeInTheDocument())
    await expect(plan()).resolves.toBe(true)
    expect(calls.filter((call) => call.method === 'PATCH')).toEqual([])
  })

  test('a refused plan says so and stays open', async () => {
    refuse = 'patch'
    renderStep(prepared)
    await waitFor(() => expect(titles()).toHaveLength(2))

    await expect(plan()).resolves.toBe(false)

    expect(await screen.findByTestId('bilan-demain-message')).toHaveTextContent('Impossible de prévoir vos tâches de demain : réessayez.')
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('draws in the %s theme without a hex colour', async (_name, theme) => {
    renderStep(prepared, theme)

    const card = await screen.findByTestId('bilan-planned')
    await waitFor(() => expect(within(card).getAllByTestId('bilan-planned-task')).toHaveLength(2))
    expect(card.innerHTML).not.toMatch(/#[0-9a-f]{3,8}\b/i)
  })
})
