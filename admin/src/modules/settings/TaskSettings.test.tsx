import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

const LEVELS = { timePressure: 2, criticality: 2, priority: 2 }

const SCHEDULE = {
  critical: { daysBefore: [3, 1], dueDay: true, overdueEveryDays: 1 },
  high: { daysBefore: [1], dueDay: true, overdueEveryDays: 1 },
  normal: { daysBefore: [], dueDay: true, overdueEveryDays: 2 },
}

const REWARD_DEFAULTS = { rewardAnimation: true, rewardVibration: true, rewardSound: true, tone: 'doux' }

const DEFAULTS = {
  orderWeights: {},
  orderLevels: LEVELS,
  alertSchedule: SCHEDULE,
  morningTime: '08:00',
  eveningTime: '21:00',
  ...REWARD_DEFAULTS,
}

/** A user who already changed a few things — J-5 on Haute came through the API. */
const SAVED = {
  id: '01TS',
  orderWeights: {},
  orderLevels: LEVELS,
  alertSchedule: {
    ...SCHEDULE,
    high: { daysBefore: [5, 1], dueDay: true, overdueEveryDays: 3 },
    normal: { daysBefore: [2], dueDay: false, overdueEveryDays: null },
  },
  morningTime: '07:00',
  eveningTime: '22:00',
  rewardAnimation: true,
  rewardVibration: false,
  rewardSound: true,
  tone: 'direct',
  defaults: DEFAULTS,
}

type Saved = typeof SAVED
type Handler = (init?: RequestInit) => Response | Promise<Response>

/** The API's PUT: a partial body merged into what is stored. */
function merge(stored: Saved, body: Record<string, unknown>): Saved {
  const schedule = { ...stored.alertSchedule } as Record<string, object>
  for (const [urgency, patch] of Object.entries((body.alertSchedule ?? {}) as Record<string, object>)) {
    schedule[urgency] = { ...schedule[urgency], ...patch }
  }
  const rewards = Object.fromEntries(
    Object.entries(body).filter(([key]) => ['rewardAnimation', 'rewardVibration', 'rewardSound', 'tone'].includes(key)),
  )
  return {
    ...stored,
    ...rewards,
    ...(body.morningTime ? { morningTime: body.morningTime as string } : {}),
    ...(body.eveningTime ? { eveningTime: body.eveningTime as string } : {}),
    alertSchedule: schedule as Saved['alertSchedule'],
  }
}

function stubApi({ get, put }: { get?: Handler; put?: Handler } = {}) {
  let stored = SAVED
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      if (put) return put(init)
      stored = merge(stored, JSON.parse(String(init.body)))
      return new Response(JSON.stringify(stored))
    }
    return get ? get(init) : new Response(JSON.stringify(stored))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const puts = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init?.body)))

const chip = (name: string) => screen.getByRole('button', { name })

beforeEach(() => {
  notify.mockClear()
  mercure.onMessage = null
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TaskSettings — alerts', () => {
  test('shows the saved settings as chips, a select and a time, without help text', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    expect(await screen.findByLabelText('Heure du matin')).toHaveValue('07:00')
    expect(screen.getByRole('heading', { name: 'Tâches' })).toBeInTheDocument()

    expect(chip('Critique — J-7')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Critique — J-3')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Critique — J-2')).toHaveAttribute('aria-pressed', 'false')
    expect(chip('Critique — J-1')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Critique — Jour J')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Normale — J-2')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Normale — Jour J')).toHaveAttribute('aria-pressed', 'false')

    expect(screen.getByLabelText('Critique — en retard')).toHaveValue('1')
    expect(screen.getByLabelText('Normale — en retard')).toHaveValue('')
    // A value only the API can set gets its own option.
    expect(screen.getByLabelText('Haute — en retard')).toHaveValue('3')
    expect(within(screen.getByLabelText('Haute — en retard')).getByRole('option', { name: 'Tous les 3 jours' })).toBeInTheDocument()
    expect(within(screen.getByLabelText('Critique — en retard')).queryByRole('option', { name: 'Tous les 3 jours' })).toBeNull()

    expect(screen.queryByRole('button', { name: 'Enregistrer' })).toBeNull()
    expect(screen.queryByText(/Basse/)).toBeNull()
    expect(screen.queryByText(/Vide/)).toBeNull()
    expect(screen.queryByText('Enregistré')).toBeNull()

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/task-settings/me')
    expect(init?.method).toBeUndefined()
  })

  test('untoggling a chip saves the day list at once and says « Enregistré »', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Critique — J-3' }))

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ alertSchedule: { critical: { daysBefore: [1] } } }]))
    const [, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'PUT')!
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' })
    expect(await screen.findByText('Enregistré')).toBeInTheDocument()
    expect(chip('Critique — J-3')).toHaveAttribute('aria-pressed', 'false')
  })

  test('quick toggles are not lost while earlier saves are on their way', async () => {
    const gates: (() => void)[] = []
    let stored = SAVED
    const fetchMock = stubApi({
      put: async (init) => {
        await new Promise<void>((resolve) => gates.push(resolve))
        stored = merge(stored, JSON.parse(String(init?.body)))
        return new Response(JSON.stringify(stored))
      },
    })
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Critique — J-7' }))
    await userEvent.click(chip('Critique — J-2'))
    // A Mercure re-read meanwhile does not undo the clicks.
    await act(async () => mercure.onMessage?.())
    expect(chip('Critique — J-2')).toHaveAttribute('aria-pressed', 'true')

    // The first answer ([7, 3, 1]) comes back while J-2 is still queued: J-2 stays on.
    await act(async () => gates[0]())
    await waitFor(() => expect(gates).toHaveLength(2))
    expect(chip('Critique — J-2')).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(chip('Critique — J-1'))

    await act(async () => gates[1]())
    await waitFor(() => expect(gates).toHaveLength(3))
    await act(async () => gates[2]())

    await waitFor(() =>
      expect(puts(fetchMock)).toEqual([
        { alertSchedule: { critical: { daysBefore: [7, 3, 1] } } },
        { alertSchedule: { critical: { daysBefore: [7, 3, 2, 1] } } },
        { alertSchedule: { critical: { daysBefore: [7, 3, 2] } } },
      ]),
    )
    expect(await screen.findByText('Enregistré')).toBeInTheDocument()
    expect(chip('Critique — J-2')).toHaveAttribute('aria-pressed', 'true')
    expect(chip('Critique — J-1')).toHaveAttribute('aria-pressed', 'false')
  })

  test('toggling a chip keeps the days only the API knows', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Haute — J-7' }))

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ alertSchedule: { high: { daysBefore: [7, 5, 1] } } }]))
  })

  test('« Jour J » saves dueDay', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Normale — Jour J' }))

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ alertSchedule: { normal: { dueDay: true } } }]))
  })

  test('the overdue select saves its value, « Jamais » as null', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.selectOptions(await screen.findByLabelText('Critique — en retard'), 'Chaque semaine')
    await waitFor(() => expect(puts(fetchMock)).toHaveLength(1))
    await userEvent.selectOptions(screen.getByLabelText('Haute — en retard'), 'Jamais')

    await waitFor(() =>
      expect(puts(fetchMock)).toEqual([
        { alertSchedule: { critical: { overdueEveryDays: 7 } } },
        { alertSchedule: { high: { overdueEveryDays: null } } },
      ]),
    )
    expect(screen.getByLabelText('Critique — en retard')).toHaveValue('7')
  })

  test('the morning time is saved when left, only once complete', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    const time = await screen.findByLabelText('Heure du matin')
    fireEvent.change(time, { target: { value: '07:30' } })
    expect(puts(fetchMock)).toEqual([])
    fireEvent.blur(time)

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ morningTime: '07:30' }]))
    await waitFor(() => expect(screen.getByLabelText('Heure du matin')).toHaveValue('07:30'))
  })

  test('the evening time is saved when left (MAG-500)', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    const time = await screen.findByLabelText('Heure du soir')
    expect(time).toHaveValue('22:00')
    fireEvent.change(time, { target: { value: '21:30' } })
    fireEvent.blur(time)

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ eveningTime: '21:30' }]))
    await waitFor(() => expect(screen.getByLabelText('Heure du soir')).toHaveValue('21:30'))
    expect(screen.getByLabelText('Heure du matin')).toHaveValue('07:00')
  })

  test('« Rappels et récompenses » shows the saved toggles and tone (MAG-508)', async () => {
    stubApi()
    render(<TaskSettings />)

    expect(await screen.findByRole('heading', { name: 'Rappels et récompenses' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Animation' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Vibration' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Son' })).toBeChecked()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Direct' })).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.getByRole('button', { name: 'Doux' })).toHaveAttribute('aria-pressed', 'false')
  })

  test('a reward switch and the tone are saved at once (MAG-508)', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('switch', { name: 'Son' }))
    await waitFor(() => expect(puts(fetchMock)).toEqual([{ rewardSound: false }]))
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Son' })).not.toBeChecked())

    await userEvent.click(screen.getByRole('button', { name: 'Drôle' }))
    await waitFor(() => expect(puts(fetchMock)).toEqual([{ rewardSound: false }, { tone: 'drole' }]))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Drôle' })).toHaveAttribute('aria-pressed', 'true'))

    // The tone chosen again is not unset: one is always chosen.
    await userEvent.click(screen.getByRole('button', { name: 'Drôle' }))
    expect(puts(fetchMock)).toHaveLength(2)
  })

  test('a half-typed time is not sent and goes back to the saved one', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    const time = await screen.findByLabelText('Heure du matin')
    fireEvent.change(time, { target: { value: '' } })
    fireEvent.blur(time)

    expect(puts(fetchMock)).toEqual([])
    expect(time).toHaveValue('07:00')
  })

  test('« Valeurs par défaut » asks first, then saves the default alerts', async () => {
    const fetchMock = stubApi()
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Valeurs par défaut' }))
    expect(screen.getByText('Revenir aux valeurs par défaut ?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Annuler' }))
    await waitFor(() => expect(screen.queryByText('Revenir aux valeurs par défaut ?')).toBeNull())
    expect(puts(fetchMock)).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: 'Valeurs par défaut' }))
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ alertSchedule: SCHEDULE, morningTime: '08:00', eveningTime: '21:00' }]))
    // The rewards keep theirs (MAG-508), as on the phone.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Direct' })).toHaveAttribute('aria-pressed', 'true'))
    await waitFor(() => expect(screen.getByLabelText('Heure du matin')).toHaveValue('08:00'))
    await waitFor(() => expect(screen.getByLabelText('Heure du soir')).toHaveValue('21:00'))
    await waitFor(() => expect(chip('Normale — J-2')).toHaveAttribute('aria-pressed', 'false'))
  })

  test('a refusal shows the API message and goes back to the saved values', async () => {
    stubApi({ put: () => new Response(JSON.stringify({ error: 'daysBefore must be 1..30' }), { status: 400 }) })
    render(<TaskSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Critique — J-3' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('daysBefore must be 1..30', { type: 'error' }))
    expect(chip('Critique — J-3')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText('Enregistré')).toBeNull()
  })

  test('a network failure on save gives a generic error and reverts', async () => {
    stubApi({ put: () => Promise.reject(new TypeError('offline')) })
    render(<TaskSettings />)

    await userEvent.selectOptions(await screen.findByLabelText('Critique — en retard'), 'Jamais')

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith("Impossible d'enregistrer les réglages des tâches", { type: 'error' }),
    )
    expect(screen.getByLabelText('Critique — en retard')).toHaveValue('1')
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

    await waitFor(() => expect(screen.getByLabelText('Heure du matin')).toHaveValue('06:45'))
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method !== 'PUT')).toHaveLength(2)
  })
})
