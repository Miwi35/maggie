import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { emitMercure } from '../../../test/mercure'
import { veilleuseDarkTheme } from '../../../theme'
import { CookingActivityPage } from './CookingActivityPage'
import { fakeTimersApi } from './fakeTimersApi'
import type { FakeTimersApi } from './fakeTimersApi'

vi.mock('../../../components/chat/ChatWidget', () => ({ ChatWidget: () => null }))
vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const TOPIC = '/api/cooking/timers/{id}'

const step = (position: number, text: string, extra: Record<string, unknown> = {}) => ({
  id: `s${position}`,
  position,
  text,
  durationMinutes: null,
  background: false,
  ingredients: [],
  utensils: [],
  waitsFor: [],
  ...extra,
})

/** The reference example: water 10 min in the background, cut, pasta 12 min in the background, vegetables 8 min, drain. */
const REFERENCE = {
  id: 'REF',
  name: 'Pâtes aux légumes',
  servings: 2,
  ingredients: [],
  utensils: [],
  steps: [
    step(1, "Mettre l'eau à bouillir", { durationMinutes: 10, background: true }),
    step(2, 'Couper les légumes'),
    step(3, 'Cuire les pâtes', { durationMinutes: 12, background: true, waitsFor: [1] }),
    step(4, 'Faire revenir les légumes', { durationMinutes: 8 }),
    step(5, 'Égoutter, mélanger', { waitsFor: [3, 4] }),
  ],
}

let api: FakeTimersApi

const fakeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input)
  const timers = api.handle(url, init)
  if (timers) return timers
  if (/^\/api\/recipes\/REF(\?guests=\d+)?$/.test(url)) return Promise.resolve(new Response(JSON.stringify(REFERENCE), { status: 200 }))

  return Promise.resolve(new Response('', { status: 404 }))
}

const renderActivity = () =>
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MemoryRouter initialEntries={['/activites/cuisine?recipe=REF']}>
        <Routes>
          <Route path="/activites/cuisine" element={<CookingActivityPage />} />
          <Route path="*" element={<output data-testid="where" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )

const stepNumber = () => screen.getByTestId('cooking-step-number').textContent

const startReference = async () => {
  renderActivity()
  await screen.findByText('Pour combien ?')
  await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
  await screen.findByTestId('cooking-preparation')
  await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
  await screen.findByTestId('cooking-step')
}

const goTo = async (position: number) => {
  while (Number(stepNumber()) < position) {
    const from = stepNumber()
    await userEvent.click(screen.getByRole('button', { name: /Suivant|Lancer et continuer/ }))
    await waitFor(() => expect(stepNumber()).not.toBe(from))
  }
}

const rowOf = (position: number) => screen.getAllByTestId('cooking-step-row').find((row) => row.dataset.position === String(position))!

const posts = () => api.requests.filter((request) => request.method === 'POST')
const patches = () => api.requests.filter((request) => request.method === 'PATCH').map((request) => request.body)

describe('The timers of the Cuisine activity (MAG-582)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    api = fakeTimersApi()
    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, writable: true, value: () => Promise.reject(new Error('refused')) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('a step in the background shows its whole duration, and « Lancer et continuer » starts « Eau » and goes on', async () => {
    await startReference()
    expect(screen.getByTestId('cooking-main-timer-clock')).toHaveTextContent('10:00')
    expect(screen.getByTestId('cooking-main-timer-label')).toHaveTextContent('Eau')
    expect(within(rowOf(1)).queryByTestId('cooking-step-timer')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Lancer et continuer' }))

    await waitFor(() => expect(stepNumber()).toBe('2'))
    expect(posts().map((request) => request.body)).toEqual([{ label: 'Eau', recipe: '/api/recipes/REF', stepPosition: 1, durationSeconds: 600 }])
    const water = within(rowOf(1)).getByTestId('cooking-step-timer')
    expect(water).toHaveAttribute('data-status', 'running')
    expect(water).toHaveAccessibleName(/^Eau : 09:5\d|10:00$/)
    expect(rowOf(1)).toHaveAttribute('data-done', 'false')
    expect(screen.queryByTestId('cooking-main-timer')).not.toBeInTheDocument()
  })

  test('back on a step whose timer runs, the main button is « Suivant » and nothing is posted again', async () => {
    await startReference()
    await goTo(2)

    await userEvent.click(screen.getByRole('button', { name: /Précédent/ }))

    expect(screen.getByRole('button', { name: /Suivant/ })).toBeInTheDocument()
    expect(screen.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'running')
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    expect(stepNumber()).toBe('2')
    expect(posts()).toHaveLength(1)
  })

  test('a refused start says so and the step stays', async () => {
    await startReference()
    api.fail(true)

    await userEvent.click(screen.getByRole('button', { name: 'Lancer et continuer' }))

    expect(await screen.findByTestId('cooking-timer-error')).toHaveTextContent("Le minuteur n'a pas démarré")
    expect(stepNumber()).toBe('1')
    expect(screen.getByRole('button', { name: 'Lancer et continuer' })).toBeEnabled()
  })

  test('a timed step not in the background: Démarrer, Pause, −1 and +1 drive its timer, « Suivant » goes on', async () => {
    await startReference()
    await goTo(4)
    expect(screen.getByRole('button', { name: /Suivant/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajouter une minute' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Démarrer' }))
    await waitFor(() => expect(screen.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'running'))
    await userEvent.click(screen.getByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(screen.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'paused'))
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter une minute' }))
    await waitFor(() => expect(screen.getByTestId('cooking-main-timer-clock')).toHaveTextContent('09:00'))
    await userEvent.click(screen.getByRole('button', { name: 'Retirer une minute' }))
    await waitFor(() => expect(screen.getByTestId('cooking-main-timer-clock')).toHaveTextContent('08:00'))

    expect(posts().at(-1)?.body).toMatchObject({ label: 'Légumes', stepPosition: 4, durationSeconds: 480 })
    expect(patches()).toEqual([{ action: 'pause' }, { action: 'add', seconds: 60 }, { action: 'add', seconds: -60 }])
    expect(stepNumber()).toBe('4')
  })

  test('nothing waits: step 3 is never held back, whatever its timers do', async () => {
    await startReference()
    await goTo(3)

    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "C'est prêt" })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Lancer et continuer/ })).toBeEnabled()
  })

  test('a timer that rings lights the line of its step, without any window, and touching it goes there and acknowledges it', async () => {
    await startReference()
    await goTo(3)

    await emitMercure(TOPIC, api.ring('T1'))

    expect(screen.queryByTestId('cooking-timer-alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(rowOf(1)).toHaveAttribute('data-lit', 'true')
    expect(within(rowOf(1)).getByTestId('cooking-step-timer')).toHaveAttribute('data-status', 'done')
    expect(stepNumber()).toBe('3')

    await userEvent.click(rowOf(1))

    expect(stepNumber()).toBe('1')
    await waitFor(() => expect(rowOf(1)).toHaveAttribute('data-lit', 'false'))
    expect(patches()).toEqual([{ action: 'dismiss' }])
    expect(within(rowOf(1)).queryByTestId('cooking-step-timer')).not.toBeInTheDocument()
    expect(rowOf(1)).toHaveAttribute('data-done', 'false')
  })

  test('two timers ring: both lines are lit', async () => {
    await startReference()
    await goTo(4)

    await emitMercure(TOPIC, api.ring('T2'))
    await emitMercure(TOPIC, api.ring('T1'))

    expect(rowOf(1)).toHaveAttribute('data-lit', 'true')
    expect(rowOf(3)).toHaveAttribute('data-lit', 'true')
    expect(screen.queryByTestId('cooking-timer-alert')).not.toBeInTheDocument()
  })

  test('touching a running timer brings back to its step and acknowledges nothing', async () => {
    await startReference()
    await goTo(3)

    await userEvent.click(within(rowOf(1)).getByTestId('cooking-step-timer'))

    expect(stepNumber()).toBe('1')
    expect(patches()).toEqual([])
    expect(within(rowOf(1)).getByTestId('cooking-step-timer')).toHaveAttribute('data-status', 'running')
  })

  test('a reload reads the same timers back from GET, and posts nothing', async () => {
    const first = renderActivity()
    await screen.findByText('Pour combien ?')
    await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await userEvent.click(await screen.findByRole('button', { name: "C'est parti" }))
    await goTo(4)
    first.unmount()
    api.requests.length = 0

    renderActivity()

    await waitFor(() => expect(screen.getAllByTestId('cooking-step-timer')).toHaveLength(2))
    expect(screen.getAllByTestId('cooking-step-timer').map((timer) => timer.getAttribute('aria-label'))).toEqual([
      expect.stringContaining('Eau'),
      expect.stringContaining('Pâtes'),
    ])
    expect(stepNumber()).toBe('4')
    expect(posts()).toHaveLength(0)
  })

  test('quitting with timers running asks first, says which, and « Quitter » stops them all', async () => {
    await startReference()
    await goTo(4)

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent("Quitter l'activité ?")
    expect(dialog).toHaveTextContent("2 minuteurs tournent encore (Eau, Pâtes) : ils s'arrêteront.")
    await userEvent.click(within(dialog).getByRole('button', { name: 'Quitter' }))

    expect(await screen.findByTestId('where')).toBeInTheDocument()
    await waitFor(() => expect(api.all().map((timer) => timer.status)).toEqual(['cancelled', 'cancelled']))
  })

  test('a rung timer alone does not ask before quitting, and it is stopped too', async () => {
    await startReference()
    await goTo(2)
    await emitMercure(TOPIC, api.ring('T1'))

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))

    expect(await screen.findByTestId('where')).toBeInTheDocument()
    expect(screen.queryByText("Quitter l'activité ?")).not.toBeInTheDocument()
    await waitFor(() => expect(api.timer('T1').status).toBe('cancelled'))
  })
})
