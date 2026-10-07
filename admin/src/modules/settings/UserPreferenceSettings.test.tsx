import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserPreferenceSettings } from './UserPreferenceSettings'

const notify = vi.fn()

vi.mock('react-admin', () => ({
  useNotify: () => notify,
  useDataProvider: () => ({ getList: () => Promise.resolve({ data: [] }) }),
  useStore: () => ['system', vi.fn()],
}))

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

/** The default city of the weather tool (MAG-156). */

const PREFERENCES = {
  id: 'p1',
  theme: 'system',
  locale: 'fr',
  timezone: 'Europe/Paris',
  defaultCalendarView: 'month',
  enabledAgendaIds: [],
  notificationsEnabled: true,
}

function stubApi(initial: Record<string, unknown>, patchStatus = 200) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PATCH') {
      if (patchStatus !== 200) return new Response(JSON.stringify({ error: 'nope' }), { status: patchStatus })
      const patch = JSON.parse(String(init.body)) as { defaultCity?: string }
      const next: Record<string, unknown> = { ...PREFERENCES }
      if (patch.defaultCity) next.defaultCity = patch.defaultCity
      return new Response(JSON.stringify(next))
    }
    return new Response(JSON.stringify({ ...PREFERENCES, ...initial }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const patches = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH').map(([, init]) => JSON.parse(String(init?.body)))

describe('UserPreferenceSettings — default city', () => {
  beforeEach(() => {
    notify.mockClear()
  })

  test('shows the city saved in the preferences', async () => {
    stubApi({ defaultCity: 'Rennes' })
    render(<UserPreferenceSettings />)

    expect(await screen.findByLabelText('Ville par défaut')).toHaveValue('Rennes')
  })

  test('saves the city, trimmed, when the field loses focus', async () => {
    const fetchMock = stubApi({})
    render(<UserPreferenceSettings />)

    const field = await screen.findByLabelText('Ville par défaut')
    await userEvent.type(field, '  Rennes ')
    await userEvent.tab()

    await waitFor(() => expect(patches(fetchMock)).toEqual([{ defaultCity: 'Rennes' }]))
    expect(notify).toHaveBeenCalledWith('Ville par défaut mise à jour', { type: 'success' })
  })

  test('sends nothing when the city did not change', async () => {
    const fetchMock = stubApi({ defaultCity: 'Rennes' })
    render(<UserPreferenceSettings />)

    const field = await screen.findByLabelText('Ville par défaut')
    await userEvent.click(field)
    await userEvent.tab()

    expect(patches(fetchMock)).toEqual([])
  })

  test('an emptied field tells the API to forget the city', async () => {
    const fetchMock = stubApi({ defaultCity: 'Rennes' })
    render(<UserPreferenceSettings />)

    const field = await screen.findByLabelText('Ville par défaut')
    await userEvent.clear(field)
    await userEvent.tab()

    await waitFor(() => expect(patches(fetchMock)).toEqual([{ defaultCity: '' }]))
    expect(notify).toHaveBeenCalledWith('Ville par défaut retirée', { type: 'success' })
  })

  test('a refused save puts the saved city back and says so', async () => {
    stubApi({ defaultCity: 'Rennes' }, 400)
    render(<UserPreferenceSettings />)

    const field = await screen.findByLabelText('Ville par défaut')
    await userEvent.clear(field)
    await userEvent.type(field, 'Brest')
    await userEvent.tab()

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Impossible de mettre à jour la ville par défaut', { type: 'error' }),
    )
    expect(field).toHaveValue('Rennes')
  })
})
