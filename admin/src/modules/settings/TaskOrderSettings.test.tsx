import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskOrderSettings } from './TaskOrderSettings'

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

const DEFAULT_LEVELS = { timePressure: 2, criticality: 2, priority: 2 }

const SAVED = {
  id: '01TS',
  orderWeights: {},
  orderLevels: { timePressure: 3, criticality: 1, priority: 2 },
  alertSchedule: {},
  morningTime: '08:00',
  defaults: { orderWeights: {}, orderLevels: DEFAULT_LEVELS, alertSchedule: {}, morningTime: '08:00' },
}

const task = (id: string, title: string, urgency = 'normal') => ({ id, title, dueDate: null, urgency, orderScore: 0 })

const FIRST = [task('a', 'Payer le loyer', 'critical'), task('b', 'Appeler le garage', 'high'), task('c', 'Ranger la cave', 'low')]
const AFTER = [FIRST[2], FIRST[0], FIRST[1]]

type Handler = (init?: RequestInit) => Response | Promise<Response>

function stubApi({ put, previews = [FIRST, AFTER] }: { put?: Handler; previews?: unknown[][] } = {}) {
  let stored = SAVED
  let previewCalls = 0
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).endsWith('/preview')) {
      const member = previews[Math.min(previewCalls++, previews.length - 1)]
      return new Response(JSON.stringify({ member }))
    }
    if (init?.method === 'PUT') {
      if (put) return put(init)
      const body = JSON.parse(String(init.body))
      stored = { ...stored, orderLevels: { ...stored.orderLevels, ...body.orderLevels } }
      return new Response(JSON.stringify(stored))
    }
    return new Response(JSON.stringify(stored))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const puts = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init?.body)))

const previewCalls = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([url]) => String(url) === '/api/task-settings/me/preview')

const titles = () => within(screen.getByRole('list', { name: "Aperçu de l'ordre" })).getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent)

const row = (title: string) =>
  within(screen.getByRole('list', { name: "Aperçu de l'ordre" }))
    .getAllByRole('listitem')
    .find((li) => li.textContent?.includes(title)) as HTMLElement

beforeEach(() => {
  notify.mockClear()
  mercure.onMessage = null
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TaskOrderSettings', () => {
  test('shows the three levels as sliders and five real tasks in order', async () => {
    stubApi()
    render(<TaskOrderSettings />)

    const deadline = await screen.findByRole('slider', { name: "L'échéance" })
    expect(screen.getByRole('heading', { name: 'Ce qui passe en premier' })).toBeInTheDocument()
    expect(deadline).toHaveValue('3')
    expect(deadline).toHaveAttribute('aria-valuetext', 'Beaucoup')
    expect(screen.getByRole('slider', { name: 'La gravité' })).toHaveAttribute('aria-valuetext', 'Peu')
    expect(screen.getByRole('slider', { name: 'Ma priorité' })).toHaveAttribute('aria-valuetext', 'Normal')
    expect(screen.getByTestId('level-timePressure')).toHaveTextContent('Beaucoup')
    expect(screen.getByTestId('level-criticality')).toHaveTextContent('Peu')

    await waitFor(() => expect(titles()).toEqual(['Payer le loyer', 'Appeler le garage', 'Ranger la cave']))
    expect(screen.queryByTitle('Monte')).toBeNull()
    expect(screen.queryByTitle('Descend')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).toBeNull()
  })

  test('moving a slider saves its level, reorders the preview and marks what moved', async () => {
    const fetchMock = stubApi()
    render(<TaskOrderSettings />)

    const gravity = await screen.findByRole('slider', { name: 'La gravité' })
    await waitFor(() => expect(titles()).toHaveLength(3))

    fireEvent.change(gravity, { target: { value: 4 } })

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ orderLevels: { criticality: 4 } }]))
    await waitFor(() => expect(titles()).toEqual(['Ranger la cave', 'Payer le loyer', 'Appeler le garage']))
    expect(previewCalls(fetchMock)).toHaveLength(2)
    expect(within(row('Ranger la cave')).getByTitle('Monte')).toBeInTheDocument()
    expect(within(row('Payer le loyer')).getByTitle('Descend')).toBeInTheDocument()
    expect(within(row('Appeler le garage')).getByTitle('Descend')).toBeInTheDocument()
    expect(screen.getByTestId('level-criticality')).toHaveTextContent('Énormément')
    expect(await screen.findByText('Enregistré')).toBeInTheDocument()
  })

  test('a task entering the five is marked as going up', async () => {
    const entering = task('d', 'Renouveler le passeport', 'critical')
    stubApi({ previews: [FIRST, [entering, FIRST[0], FIRST[1]]] })
    render(<TaskOrderSettings />)

    fireEvent.change(await screen.findByRole('slider', { name: 'La gravité' }), { target: { value: 4 } })

    await waitFor(() => expect(within(row('Renouveler le passeport')).getByTitle('Monte')).toBeInTheDocument())
  })

  test('one level change reads the preview once, even with the Mercure echo', async () => {
    const fetchMock = stubApi()
    render(<TaskOrderSettings />)

    fireEvent.change(await screen.findByRole('slider', { name: 'La gravité' }), { target: { value: 4 } })
    await waitFor(() => expect(previewCalls(fetchMock)).toHaveLength(2))
    await act(async () => mercure.onMessage?.())

    expect(previewCalls(fetchMock)).toHaveLength(2)
  })

  test('a level changed elsewhere reloads the preview', async () => {
    const fetchMock = stubApi()
    render(<TaskOrderSettings />)
    await screen.findByRole('slider', { name: 'La gravité' })
    await waitFor(() => expect(previewCalls(fetchMock)).toHaveLength(1))

    await fetch('/api/task-settings/me', { method: 'PUT', body: JSON.stringify({ orderLevels: { priority: 0 } }) })
    await act(async () => mercure.onMessage?.())

    await waitFor(() => expect(previewCalls(fetchMock)).toHaveLength(2))
    expect(screen.getByRole('slider', { name: 'Ma priorité' })).toHaveAttribute('aria-valuetext', 'Très peu')
  })

  test('the Mercure echo of the same order keeps the arrows', async () => {
    stubApi()
    render(<TaskOrderSettings />)

    fireEvent.change(await screen.findByRole('slider', { name: 'La gravité' }), { target: { value: 4 } })
    await waitFor(() => expect(within(row('Ranger la cave')).getByTitle('Monte')).toBeInTheDocument())

    await act(async () => mercure.onMessage?.())

    expect(mercure.topics).toEqual(['/api/task_settings/{id}'])
    await waitFor(() => expect(within(row('Ranger la cave')).getByTitle('Monte')).toBeInTheDocument())
  })

  test('an empty preview draws nothing', async () => {
    stubApi({ previews: [[]] })
    render(<TaskOrderSettings />)

    await screen.findByRole('slider', { name: 'La gravité' })
    expect(screen.queryByRole('list', { name: "Aperçu de l'ordre" })).toBeNull()
  })

  test('« Valeurs par défaut » asks first, then saves the default levels', async () => {
    const fetchMock = stubApi()
    render(<TaskOrderSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Valeurs par défaut' }))
    expect(screen.getByText('Revenir aux valeurs par défaut ?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(puts(fetchMock)).toEqual([{ orderLevels: DEFAULT_LEVELS }]))
    await waitFor(() => expect(screen.getByRole('slider', { name: "L'échéance" })).toHaveAttribute('aria-valuetext', 'Normal'))
  })

  test('a refusal shows the API message and puts the slider back', async () => {
    stubApi({ put: () => new Response(JSON.stringify({ error: 'orderLevels.criticality must be 0..4' }), { status: 400 }) })
    render(<TaskOrderSettings />)

    fireEvent.change(await screen.findByRole('slider', { name: 'La gravité' }), { target: { value: 4 } })

    await waitFor(() => expect(notify).toHaveBeenCalledWith('orderLevels.criticality must be 0..4', { type: 'error' }))
    await waitFor(() => expect(screen.getByRole('slider', { name: 'La gravité' })).toHaveAttribute('aria-valuetext', 'Peu'))
    expect(screen.queryByText('Enregistré')).toBeNull()
  })

  test('says so when the settings cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('down', { status: 500 })))
    render(<TaskOrderSettings />)

    expect(await screen.findByText('Impossible de charger les réglages des tâches')).toBeInTheDocument()
  })
})
