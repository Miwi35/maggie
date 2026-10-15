import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskSettings } from './TaskSettings'

const notify = vi.fn()

vi.mock('react-admin', () => ({
  useNotify: () => notify,
}))

const mercure = vi.hoisted(() => ({ topics: [] as string[], onMessage: null as null | (() => void) }))

vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: () => void) => {
    mercure.topics = topics
    mercure.onMessage = onMessage
  },
}))

const WEIGHTS = {
  timePressure: {
    overdue: 65,
    overduePerDay: 2,
    overdueMax: 85,
    today: 55,
    tomorrow: 45,
    in2To3Days: 30,
    in4To7Days: 15,
    in8To30Days: 5,
    later: 0,
  },
  criticality: { critical: 30, high: 20, medium: 10, low: 0 },
  priority: { high: 6, medium: 3, low: 0 },
}

const SCHEDULE = {
  critical: { daysBefore: [3, 1], dueDay: true, overdueEveryDays: 1 },
  high: { daysBefore: [1], dueDay: true, overdueEveryDays: 1 },
  normal: { daysBefore: [], dueDay: true, overdueEveryDays: 2 },
}

const DEFAULTS = { orderWeights: WEIGHTS, alertSchedule: SCHEDULE, morningTime: '08:00' }

/** A user who already changed a few things. */
const SAVED = {
  id: '01TS',
  orderWeights: { ...WEIGHTS, criticality: { ...WEIGHTS.criticality, high: 25 } },
  alertSchedule: { ...SCHEDULE, normal: { daysBefore: [2], dueDay: false, overdueEveryDays: null } },
  morningTime: '07:00',
  defaults: DEFAULTS,
}

type Handler = (init?: RequestInit) => Response | Promise<Response>

function stubApi({ get, put }: { get?: Handler; put?: Handler } = {}) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      if (put) return put(init)
      const body = JSON.parse(String(init.body))
      return new Response(JSON.stringify({ ...SAVED, ...body }))
    }
    return get ? get(init) : new Response(JSON.stringify(SAVED))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const puts = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init?.body)))

const field = (name: string) => screen.getByLabelText(name)

beforeEach(() => {
  notify.mockClear()
  mercure.onMessage = null
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TaskSettings', () => {
  test('shows the saved settings', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    expect(await screen.findByLabelText('Heure du matin')).toHaveValue('07:00')
    expect(field("Critique — jours avant l'échéance")).toHaveValue('3, 1')
    expect(field('Critique — le jour J')).toBeChecked()
    expect(field('Critique — en retard, tous les … jours')).toHaveValue(1)
    expect(field("Normale — jours avant l'échéance")).toHaveValue('2')
    expect(field('Normale — le jour J')).not.toBeChecked()
    expect(field('Normale — en retard, tous les … jours')).toHaveValue(null)
    expect(field('Pression du temps — En retard')).toHaveValue(65)
    expect(field('Criticité — Haute')).toHaveValue(25)
    expect(field('Priorité — Haute')).toHaveValue(6)
    expect(screen.getByText("Basse : jamais d'alerte, seulement l'affichage.")).toBeInTheDocument()

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/task-settings/me')
    expect(init?.method).toBeUndefined()
  })

  test('saves the whole form and says so', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    fireEvent.change(await screen.findByLabelText('Heure du matin'), { target: { value: '07:30' } })
    await userEvent.clear(field("Critique — jours avant l'échéance"))
    await userEvent.type(field("Critique — jours avant l'échéance"), '1')
    await userEvent.click(field('Haute — le jour J'))
    await userEvent.clear(field('Haute — en retard, tous les … jours'))
    await userEvent.clear(field('Priorité — Moyenne'))
    await userEvent.type(field('Priorité — Moyenne'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    expect(puts(fetchMock)[0]).toEqual({
      morningTime: '07:30',
      alertSchedule: {
        critical: { daysBefore: [1], dueDay: true, overdueEveryDays: 1 },
        high: { daysBefore: [1], dueDay: false, overdueEveryDays: null },
        normal: { daysBefore: [2], dueDay: false, overdueEveryDays: null },
      },
      orderWeights: { ...SAVED.orderWeights, priority: { high: 6, medium: 4, low: 0 } },
    })
    const [, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'PUT')!
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' })
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Réglages des tâches enregistrés', { type: 'success' }))
    expect(field("Critique — jours avant l'échéance")).toHaveValue('1')
  })

  test('« Valeurs par défaut » fills the form with the defaults without saving', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await screen.findByLabelText('Heure du matin')
    await userEvent.click(screen.getByRole('button', { name: 'Valeurs par défaut' }))

    expect(field('Heure du matin')).toHaveValue('08:00')
    expect(field("Normale — jours avant l'échéance")).toHaveValue('')
    expect(field('Normale — le jour J')).toBeChecked()
    expect(field('Normale — en retard, tous les … jours')).toHaveValue(2)
    expect(field('Criticité — Haute')).toHaveValue(20)
    expect(puts(fetchMock)).toEqual([])
  })

  test('an invalid day list is not sent and the field says why', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    const days = await screen.findByLabelText("Critique — jours avant l'échéance")
    await userEvent.clear(days)
    await userEvent.type(days, '3, x')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Des nombres de 1 à 30 séparés par des virgules')).toBeInTheDocument()
    expect(days).toHaveAttribute('aria-invalid', 'true')
    expect(puts(fetchMock)).toEqual([])
  })

  test('a refusal shows the API message and keeps the form', async () => {
    stubApi({ put: () => new Response(JSON.stringify({ error: 'morningTime must be HH:MM' }), { status: 400 }) })
    render(<TaskSettings />)

    const days = await screen.findByLabelText("Critique — jours avant l'échéance")
    await userEvent.clear(days)
    await userEvent.type(days, '5')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('morningTime must be HH:MM', { type: 'error' }))
    expect(days).toHaveValue('5')
  })

  test('a network failure on save gives a generic error', async () => {
    stubApi({ put: () => Promise.reject(new TypeError('offline')) })
    render(<TaskSettings />)

    await screen.findByLabelText('Heure du matin')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith("Impossible d'enregistrer les réglages des tâches", { type: 'error' }),
    )
  })

  test('says so when the settings cannot be loaded', async () => {
    stubApi({ get: () => new Response('down', { status: 500 }) })
    render(<TaskSettings />)

    expect(await screen.findByText('Impossible de charger les réglages des tâches')).toBeInTheDocument()
  })

  test('shows a spinner while loading', () => {
    stubApi({ get: () => new Promise<Response>(() => {}) })
    render(<TaskSettings />)

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  test('a Mercure update re-reads the settings', async () => {
    let morningTime = '07:00'
    const fetchMock = stubApi({ get: () => new Response(JSON.stringify({ ...SAVED, morningTime })) })
    render(<TaskSettings />)

    expect(await screen.findByLabelText('Heure du matin')).toHaveValue('07:00')
    expect(mercure.topics).toEqual(['/api/task_settings/{id}'])

    morningTime = '06:45'
    await act(async () => mercure.onMessage?.())

    await waitFor(() => expect(field('Heure du matin')).toHaveValue('06:45'))
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method !== 'PUT')).toHaveLength(2)
  })
})
