import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

/**
 * The calendar screen honours what the user saved in « Préférences » (MAG-120):
 * the view the grid opens on and the agendas it shows. Both used to be stored and
 * never read, each screen keeping its own local filters.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

const mockGetList = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  }),
  useNotify: () => vi.fn(),
}))

const AGENDAS = [
  { id: '/api/agendas/01PERSO', name: 'Perso', color: '#3f51b5', default: true },
  { id: '/api/agendas/01FAMILLE', name: 'Famille', color: '#e91e63', default: false },
  { id: '/api/agendas/01TRAVAIL', name: 'Travail', color: '#009688', default: false },
]

const PREFERENCES = {
  id: 'pref-1',
  theme: 'light',
  locale: 'fr',
  timezone: 'Europe/Paris',
  defaultCalendarView: 'month',
  enabledAgendaIds: [] as string[],
  notificationsEnabled: true,
}

const stubPreferences = (overrides: Record<string, unknown>) => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ ...PREFERENCES, ...overrides }) }),
  )
}

const row = (name: string) =>
  screen.getAllByTestId('agenda-row').find((candidate) => candidate.textContent === name)!

describe('CalendarView — saved preferences', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve('agendas' === resource ? { data: AGENDAS, total: AGENDAS.length } : { data: [], total: 0 }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const open = async () => {
    render(<CalendarView />)
    await waitFor(() => expect(screen.getAllByTestId('agenda-row')).toHaveLength(AGENDAS.length))
  }

  test.each([
    ['week', 'Semaine'],
    ['day', 'Jour'],
    ['month', 'Mois'],
  ])('opens on the %s view the user saved', async (saved, label) => {
    stubPreferences({ defaultCalendarView: saved })
    await open()

    await waitFor(() => expect(screen.getByRole('button', { name: label })).toHaveClass('MuiButton-contained'))
  })

  test('shows only the agendas the user saved', async () => {
    stubPreferences({ enabledAgendaIds: ['/api/agendas/01PERSO', '/api/agendas/01TRAVAIL'] })
    await open()

    await waitFor(() => expect(within(row('Famille')).getByRole('checkbox')).not.toBeChecked())
    expect(within(row('Perso')).getByRole('checkbox')).toBeChecked()
    expect(within(row('Travail')).getByRole('checkbox')).toBeChecked()
  })

  test('reads a saved agenda by its bare id as well as by its IRI', async () => {
    stubPreferences({ enabledAgendaIds: ['01FAMILLE'] })
    await open()

    await waitFor(() => expect(within(row('Perso')).getByRole('checkbox')).not.toBeChecked())
    expect(within(row('Famille')).getByRole('checkbox')).toBeChecked()
  })

  test('shows every agenda when none was ever saved', async () => {
    stubPreferences({ enabledAgendaIds: [] })
    await open()

    for (const agenda of AGENDAS) {
      expect(within(row(agenda.name)).getByRole('checkbox')).toBeChecked()
    }
  })

  test('shows every agenda when the saved ones no longer exist', async () => {
    stubPreferences({ enabledAgendaIds: ['/api/agendas/01SUPPRIME'] })
    await open()

    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('agendas', expect.anything()))
    for (const agenda of AGENDAS) {
      expect(within(row(agenda.name)).getByRole('checkbox')).toBeChecked()
    }
  })

  test('a toggle made on the screen afterwards wins, and is not written back', async () => {
    stubPreferences({ enabledAgendaIds: ['/api/agendas/01PERSO'] })
    await open()
    await waitFor(() => expect(within(row('Famille')).getByRole('checkbox')).not.toBeChecked())

    await userEvent.click(row('Famille'))

    await waitFor(() => expect(within(row('Famille')).getByRole('checkbox')).toBeChecked())
    const writes = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'PATCH')
    expect(writes).toHaveLength(0)
  })

  test('keeps the default screen when the preferences cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await open()

    expect(screen.getByRole('button', { name: 'Mois' })).toHaveClass('MuiButton-contained')
    for (const agenda of AGENDAS) {
      expect(within(row(agenda.name)).getByRole('checkbox')).toBeChecked()
    }
  })
})
