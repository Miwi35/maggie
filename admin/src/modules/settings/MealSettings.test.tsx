import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MealSettings } from './MealSettings'

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

const SAVED = { id: '01MS', defaultGuests: 2, defaults: { defaultGuests: 2 } }

type Handler = (init?: RequestInit) => Response | Promise<Response>

function stubApi({ get, put }: { get?: Handler; put?: Handler } = {}) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      if (put) return put(init)
      return new Response(JSON.stringify({ ...SAVED, ...JSON.parse(String(init.body)) }))
    }
    return get ? get(init) : new Response(JSON.stringify(SAVED))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const puts = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init?.body)))

const value = () => screen.getByTestId('guests-value')
const more = () => screen.getByRole('button', { name: /de plus/ })
const fewer = () => screen.getByRole('button', { name: /de moins/ })

beforeEach(() => {
  notify.mockClear()
  mercure.onMessage = null
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MealSettings', () => {
  test('shows the saved default number of guests', async () => {
    stubApi()
    render(<MealSettings />)

    expect(await screen.findByRole('group', { name: 'Nombre de convives par défaut' })).toBeInTheDocument()
    expect(value()).toHaveTextContent('2')
  })

  test('steps up and down, never below one', async () => {
    stubApi({ get: () => new Response(JSON.stringify({ ...SAVED, defaultGuests: 1 })) })
    render(<MealSettings />)
    await screen.findByTestId('guests-value')

    expect(fewer()).toBeDisabled()

    await userEvent.click(more())
    await userEvent.click(more())
    expect(value()).toHaveTextContent('3')

    await userEvent.click(fewer())
    expect(value()).toHaveTextContent('2')
  })

  test('saves the number with a PUT and confirms', async () => {
    const fetchMock = stubApi()
    render(<MealSettings />)
    await screen.findByTestId('guests-value')

    await userEvent.click(more())
    await userEvent.click(more())
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Réglages des repas enregistrés', { type: 'success' }))
    expect(puts(fetchMock)).toEqual([{ defaultGuests: 4 }])
    expect(value()).toHaveTextContent('4')
  })

  test('shows the API refusal and keeps what was typed', async () => {
    stubApi({
      put: () => new Response(JSON.stringify({ error: 'defaultGuests must be a whole number from 1 to 999.' }), { status: 400 }),
    })
    render(<MealSettings />)
    await screen.findByTestId('guests-value')

    await userEvent.click(more())
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('defaultGuests must be a whole number from 1 to 999.', { type: 'error' }),
    )
    expect(value()).toHaveTextContent('3')
  })

  test('says so when the save fails for any other reason', async () => {
    stubApi({ put: () => new Response('boom', { status: 500 }) })
    render(<MealSettings />)
    await screen.findByTestId('guests-value')

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith("Impossible d'enregistrer les réglages des repas", { type: 'error' }))
  })

  test('says so when the settings cannot be loaded', async () => {
    stubApi({ get: () => new Response('boom', { status: 500 }) })
    render(<MealSettings />)

    expect(await screen.findByText('Impossible de charger les réglages des repas')).toBeInTheDocument()
  })

  test('follows a change made elsewhere', async () => {
    let current = SAVED
    stubApi({ get: () => new Response(JSON.stringify(current)) })
    render(<MealSettings />)
    await screen.findByTestId('guests-value')
    expect(mercure.topics).toEqual(['/api/meal_settings/{id}'])

    current = { ...SAVED, defaultGuests: 5 }
    await act(async () => mercure.onMessage?.())

    await waitFor(() => expect(value()).toHaveTextContent('5'))
  })
})
