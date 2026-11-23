import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { addDays, localDay } from '../../../dates'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../../theme'
import type { AgendaHomeEvent, AgendaHomeState } from '../../home/cards/agendaTimeline'
import { BilanActivityPage } from './BilanActivityPage'
import { fakeBilanStatusApi } from './fakeBilanStatusApi'
import type { FakeBilanStatusApi } from './fakeBilanStatusApi'

let message: () => void = () => {}
vi.mock('../../../hooks/useMercure', () => ({ useMercure: (_topics: string[], onMessage: () => void) => void (message = onMessage) }))
vi.mock('../../../components/chat/ChatWidget', () => ({ ChatWidget: ({ open }: { open: boolean }) => (open ? <div data-testid="chat-panel">Conversation</div> : null) }))

const { agendaState, healthCard } = vi.hoisted(() => ({ agendaState: { value: null as unknown }, healthCard: { value: null as unknown } }))
vi.mock('../../home/useAgendaCardState', () => ({
  useAgendaCardState: () => ({ state: agendaState.value, loading: false, error: false, retry: () => undefined }),
}))
vi.mock('../../home/useHealthCardState', () => ({
  useHealthCardState: () => ({ state: healthCard.value, loading: false, error: false, retry: () => undefined }),
}))

const TODAY = localDay(new Date())
const TOMORROW = addDays(TODAY, 1)

const event = (id: string, summary: string, start: string, end: string): AgendaHomeEvent => ({
  id, summary, description: null, location: null, locationShort: null, allDay: false, startAt: `${TODAY}T${start}:00+01:00`, endAt: `${TODAY}T${end}:00+01:00`, timeZone: 'Europe/Paris', status: 'confirmed', reminders: null, activity: null, agendaId: 'A', agendaName: 'Perso', agendaColor: null,
})

const task = (id: string, title: string, completedAt: string | null = null) => ({ id, title, priority: 'normal', criticality: 'normal', dueDate: TODAY, completedAt })

const dayEntry = (patch: Record<string, unknown> = {}) => ({
  id: 'D1', date: TODAY, weightKg: 72.4, bodyFatPercent: null, weighedAt: `${TODAY}T07:12:00+01:00`, steps: 6200, eatenKcal: 1806, expendedKcal: 2118, balanceKcal: -312, sportMinutes: 45, mood: null, fatigue: null, note: null, notedBy: null, notedAt: null, ...patch,
})

let status: FakeBilanStatusApi
let tasks: ReturnType<typeof task>[]
let done: ReturnType<typeof task>[]
let health: ReturnType<typeof dayEntry>
let patches: { url: string; body: Record<string, unknown> }[]
let refusePatch: boolean
let eventStartAt: string | null

const json = (body: unknown, code = 200) => Promise.resolve(new Response(JSON.stringify(body), { status: code }))

const fakeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input)
  const saved = status.handle(url, init)
  if (saved) return saved
  if (init?.method === 'PATCH') {
    const body = JSON.parse(String(init.body)) as Record<string, unknown>
    patches.push({ url, body })
    if (refusePatch) return json({}, 500)
    if (url.includes('/health/days/')) {
      health = { ...health, ...body } as typeof health

      return json(health)
    }

    return json({})
  }
  if (url.includes('/tasks/view/today')) return json({ member: tasks, counts: {}, totalItems: tasks.length })
  if (url.includes('completedAt')) return json({ member: done })
  const found = url.match(/\/tasks\/([^/?]+)$/)
  if (found) return json([...tasks, ...done].find((candidate) => candidate.id === found[1]) ?? {}, [...tasks, ...done].some((candidate) => candidate.id === found[1]) ? 200 : 404)
  if (url.includes('/health/days/')) return json(health)
  if (url.match(/\/events\/E1$/)) return eventStartAt ? json({ startAt: eventStartAt }) : json({}, 500)

  return json({}, 404)
}

const Where = () => {
  const location = useLocation()

  return <output data-testid="where">{location.pathname}</output>
}

const renderPage = (theme = veilleuseDarkTheme) =>
  render(
    <ThemeProvider theme={theme}>
      <MemoryRouter initialEntries={[{ pathname: '/activites/bilan', state: { from: '/digest' } }]}>
        <Routes>
          <Route path="/activites/bilan" element={<BilanActivityPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )

const open = async () => {
  renderPage()
  await screen.findByRole('heading', { level: 2 })
}

const goTo = (name: string) => userEvent.click(within(screen.getByRole('navigation', { name: 'Étapes du bilan' })).getByRole('button', { name: new RegExp(name) }))

const lastStep = () => (status.current()?.state as { step: string }).step

beforeEach(() => {
  status = fakeBilanStatusApi()
  tasks = [task('T1', 'Appeler le notaire'), task('T2', 'Ranger le garage')]
  done = [task('T3', 'Payer la cantine', `${TODAY}T10:00:00+01:00`)]
  health = dayEntry()
  patches = []
  refusePatch = false
  eventStartAt = `${TODAY}T10:00:00+01:00`
  message = () => {}
  agendaState.value = {
    date: TODAY,
    now: `${TODAY}T20:00:00+01:00`,
    trips: [],
    events: [event('E1', 'Kiné', '09:30', '10:00'), event('E2', 'Dîner', '21:00', '23:00')],
  } satisfies AgendaHomeState
  healthCard.value = { day: 'today', steps: { count: 6200, goal: 8000 }, balance: null, fast: null, workout: { id: 'W', title: 'Course', time: '18:30', durationMinutes: 45 } }
  vi.stubGlobal('fetch', vi.fn(fakeFetch))
})

afterEach(() => vi.unstubAllGlobals())

describe('BilanActivityPage', () => {
  test('draws the header, the five steps, the heading, Maggie and the footer', async () => {
    await open()

    expect(screen.getByRole('heading', { level: 1, name: 'Bilan de la journée' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Quitter l’activité' })).toBeInTheDocument()
    expect(screen.getByText('Étape 1 sur 5')).toBeInTheDocument()
    const steps = within(screen.getByRole('navigation', { name: 'Étapes du bilan' })).getAllByRole('button')
    expect(steps.map((step) => step.textContent)).toEqual(['1Journée', '2Finance', '3Santé', '4Consignes', '5Demain'])
    expect(steps[0]).toHaveAttribute('aria-current', 'step')
    expect(screen.getByRole('heading', { level: 2, name: 'Votre journée' })).toBeInTheDocument()
    expect(screen.getByTestId('maggie-avatar')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Répondre' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Précédent' })).toBeDisabled()
    expect(screen.getByText('Ensuite · Finance')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Suivant' })).toBeInTheDocument()
    await waitFor(() => expect(status.writes()).toEqual([{ origin: { kind: 'day', id: TODAY }, state: { step: 'journee' } }]))
  })

  test('a step clicked in the column is gone to and written', async () => {
    await open()
    await goTo('Santé')

    expect(await screen.findByRole('heading', { level: 2, name: 'Votre santé' })).toBeInTheDocument()
    expect(screen.getByText('Étape 3 sur 5')).toBeInTheDocument()
    await waitFor(() => expect(lastStep()).toBe('sante'))
    expect(within(screen.getByRole('navigation', { name: 'Étapes du bilan' })).getByRole('button', { name: /Journée/ })).toHaveTextContent('Journée')
  })

  test('Suivant becomes Bonne nuit at the last step', async () => {
    await open()
    await goTo('Demain')

    expect(await screen.findByRole('button', { name: 'Bonne nuit' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suivant' })).not.toBeInTheDocument()
    expect(screen.getByText('Voici ce qui vous attend demain.')).toBeInTheDocument()
  })

  test('Journée lists the finished events and the remaining tasks', async () => {
    await open()

    const agenda = await screen.findByTestId('bilan-agenda')
    expect(within(agenda).getByText('Kiné')).toBeInTheDocument()
    expect(within(agenda).queryByText('Dîner')).not.toBeInTheDocument()
    expect(screen.getByTestId('bilan-tasks-count')).toHaveTextContent('1 faite sur 3')
    expect(screen.getByRole('radiogroup', { name: 'Appeler le notaire' })).toBeInTheDocument()
    expect(within(screen.getByRole('radiogroup', { name: 'Appeler le notaire' })).getByRole('radio', { name: 'Demain' })).toBeChecked()
    expect(screen.getByText('Faites · Payer la cantine')).toBeInTheDocument()
    expect(screen.getByTestId('bilan-say')).toHaveTextContent('1 tâche sur 3 aujourd’hui. Il reste Appeler le notaire et Ranger le garage : je les mets demain ?')
  })

  test('Demain then Plus tard patch the task then record the choice', async () => {
    await open()
    const group = await screen.findByRole('radiogroup', { name: 'Appeler le notaire' })

    await userEvent.click(within(group).getByRole('radio', { name: 'Demain' }))
    await waitFor(() => expect(patches).toContainEqual({ url: expect.stringContaining('/tasks/T1'), body: { dueDate: TOMORROW } }))
    await waitFor(() => expect((status.current()?.state as { answers: { tasks: unknown } }).answers.tasks).toEqual([{ taskId: 'T1', choice: 'tomorrow' }]))

    await userEvent.click(within(group).getByRole('radio', { name: 'Plus tard' }))
    await waitFor(() => expect(patches).toContainEqual({ url: expect.stringContaining('/tasks/T1'), body: { dueDate: null } }))
    await waitFor(() => expect((status.current()?.state as { answers: { tasks: unknown } }).answers.tasks).toEqual([{ taskId: 'T1', choice: 'later' }]))
    expect(within(group).getByRole('radio', { name: 'Plus tard' })).toBeChecked()
  })

  test('a refused patch puts the chip back and says so', async () => {
    await open()
    const group = await screen.findByRole('radiogroup', { name: 'Appeler le notaire' })
    refusePatch = true

    await userEvent.click(within(group).getByRole('radio', { name: 'Plus tard' }))

    expect(await screen.findByText('Impossible de ranger « Appeler le notaire » : réessayez.')).toBeInTheDocument()
    expect(within(group).getByRole('radio', { name: 'Demain' })).toBeChecked()
    expect(within(group).getByRole('radio', { name: 'Plus tard' })).not.toBeChecked()
    expect((status.current()?.state as { answers?: unknown }).answers).toBeUndefined()
  })

  test('Oui, demain files every remaining task', async () => {
    await open()
    await screen.findByRole('radiogroup', { name: 'Appeler le notaire' })

    await userEvent.click(screen.getByRole('button', { name: 'Oui, demain' }))

    await waitFor(() => expect(patches.filter((patch) => patch.body.dueDate === TOMORROW)).toHaveLength(2))
    await waitFor(() =>
      expect((status.current()?.state as { answers: { tasks: unknown[] } }).answers.tasks).toEqual(
        expect.arrayContaining([{ taskId: 'T1', choice: 'tomorrow' }, { taskId: 'T2', choice: 'tomorrow' }]),
      ),
    )
    await waitFor(() => expect(screen.getByTestId('bilan-say')).toHaveTextContent('C’est rangé. Passons à la suite ?'))
    expect(screen.queryByRole('button', { name: 'Oui, demain' })).not.toBeInTheDocument()
  })

  test('Santé draws the figures and writes mood then records it', async () => {
    await open()
    await goTo('Santé')

    expect(await screen.findByTestId('bilan-figure-balance')).toHaveTextContent('−312 kcal')
    expect(screen.getByTestId('bilan-figure-balance')).toHaveTextContent('1 806 · 2 118')
    expect(screen.getByTestId('bilan-figure-steps')).toHaveTextContent('objectif 8 000')
    expect(screen.getByTestId('bilan-figure-sport')).toHaveTextContent('45 min')
    expect(screen.getByTestId('bilan-figure-sport')).toHaveTextContent('Course')
    expect(screen.getByTestId('bilan-figure-weight')).toHaveTextContent('pesée · 07:12')

    await userEvent.click(within(screen.getByRole('radiogroup', { name: 'Moral' })).getByRole('radio', { name: '7' }))

    await waitFor(() => expect(patches).toContainEqual({ url: expect.stringContaining(`/health/days/${TODAY}`), body: { mood: 7 } }))
    await waitFor(() => expect((status.current()?.state as { answers: { mood: number } }).answers.mood).toBe(7))
    expect(within(screen.getByRole('radiogroup', { name: 'Moral' })).getByRole('radio', { name: '7' })).toBeChecked()
    expect(screen.getByText('7 / 10')).toBeInTheDocument()
  })

  test('a day without data shows dashes and no sub-line', async () => {
    health = dayEntry({ balanceKcal: null, eatenKcal: null, expendedKcal: null, steps: null, sportMinutes: null, weightKg: null, weighedAt: null })
    healthCard.value = { day: 'today', steps: null, balance: null, fast: null, workout: null }
    await open()
    await goTo('Santé')

    expect(await screen.findByTestId('bilan-figure-balance')).toHaveTextContent(/^Bilan calorique—$/)
    expect(screen.getByTestId('bilan-figure-weight')).toHaveTextContent(/^Poids—$/)
    expect(screen.getByTestId('bilan-say')).toHaveTextContent('Et votre moral, sur 10 ? Votre fatigue ?')
  })

  test('a value already noted is checked', async () => {
    health = dayEntry({ fatigue: 4, note: 'Journée longue' })
    await open()
    await goTo('Santé')

    const fatigue = await screen.findByRole('radiogroup', { name: 'Fatigue' })
    await waitFor(() => expect(within(fatigue).getByRole('radio', { name: '4' })).toBeChecked())
    expect(screen.getByText('Note · « Journée longue »')).toBeInTheDocument()
  })

  test('Consignes lists each with its tag and the reminder’s time', async () => {
    status = fakeBilanStatusApi({
      origin: { kind: 'day', id: TODAY },
      state: {
        step: 'consignes',
        instructions: [
          { id: '1', text: 'Acheter du pain', kind: 'task', targetId: 'T9' },
          { id: '2', text: 'Appeler maman', kind: 'reminder', targetId: 'E1' },
          { id: '3', text: 'Dormir tôt', kind: 'instruction', targetId: null },
        ],
      },
    })
    await open()

    const card = await screen.findByRole('region', { name: 'Pour demain' })
    const rows = within(card).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('TâcheAcheter du pain')
    expect(rows[2]).toHaveTextContent('ConsigneDormir tôt')
    await waitFor(() => expect(rows[1]).toHaveTextContent('RappelAppeler maman10:00'))
  })

  test('a reminder whose event cannot be read stays a line without time', async () => {
    eventStartAt = null
    status = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'consignes', instructions: [{ id: '2', text: 'Appeler maman', kind: 'reminder', targetId: 'E1' }] } })
    await open()

    expect(await screen.findByText('Appeler maman')).toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Pour demain' })
    expect(within(card).getByRole('listitem')).toHaveTextContent(/^RappelAppeler maman$/)
  })

  test('a consigne arriving over Mercure shows', async () => {
    status = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'consignes', instructions: [] } })
    await open()
    expect(await screen.findByText('Aucune consigne pour l’instant.')).toBeInTheDocument()

    status.set({ origin: { kind: 'day', id: TODAY }, state: { step: 'consignes', instructions: [{ id: '1', text: 'Acheter du pain', kind: 'task', targetId: 'T9' }] } })
    message()

    expect(await screen.findByText('Acheter du pain')).toBeInTheDocument()
  })

  test('a bilan of today resumes at its step', async () => {
    status = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'sante' } })
    renderPage()

    expect(await screen.findByRole('heading', { level: 2, name: 'Votre santé' })).toBeInTheDocument()
    expect(status.writes()).toEqual([])
  })

  test('quitting asks first, Rester stays, Quitter leaves and keeps the status', async () => {
    await open()
    await goTo('Santé')
    await waitFor(() => expect(lastStep()).toBe('sante'))

    await userEvent.click(screen.getByRole('button', { name: 'Quitter l’activité' }))
    expect(await screen.findByText('Quitter l\'activité ?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Rester' }))
    await waitFor(() => expect(screen.queryByText('Quitter l\'activité ?')).not.toBeInTheDocument())
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Quitter l’activité' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Quitter' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/digest')
    expect(status.deletes()).toHaveLength(0)
    expect(lastStep()).toBe('sante')
  })

  test('Échap asks too', async () => {
    await open()

    await userEvent.keyboard('{Escape}')

    expect(await screen.findByText('Quitter l\'activité ?')).toBeInTheDocument()
  })

  test('Bonne nuit deletes and leaves', async () => {
    await open()
    await goTo('Demain')

    await userEvent.click(await screen.findByRole('button', { name: 'Bonne nuit' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/digest')
    await waitFor(() => expect(status.deletes()).toHaveLength(1))
    expect(status.current()).toBeNull()
  })

  test('a 404 over Mercure closes the activity', async () => {
    await open()
    status.set(null)
    message()

    expect(await screen.findByTestId('where')).toHaveTextContent('/digest')
  })

  test('Répondre opens the chat', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Répondre' }))

    expect(await screen.findByTestId('chat-panel')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Maggie' })).not.toBeInTheDocument()
  })

  test('an unreachable status says so and retries', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('', { status: 503 }))))
    renderPage()
    expect(await screen.findByText('Le bilan n’a pas pu être chargé.')).toBeInTheDocument()

    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'Votre journée' })).toBeInTheDocument()
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('draws in the %s theme', async (_name, theme) => {
    renderPage(theme)

    expect(await screen.findByRole('heading', { level: 2, name: 'Votre journée' })).toBeInTheDocument()
  })
})
