import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { RoutinesWidget } from './RoutinesWidget'
import type { DayRoutine, RoutinesDay } from '../calendar/routinesApi'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({ useNotify: () => mockNotify }))

class MockEventSource {
  static last: MockEventSource | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.last = this
  }
}

const routine = (id: string, title: string, extra: Partial<DayRoutine> = {}): DayRoutine => ({
  id,
  title,
  anchor: 'wake',
  anchorTime: null,
  time: '07:00',
  remind: false,
  recurrence: { kind: 'daily' },
  checked: false,
  ...extra,
})

let day: RoutinesDay

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const respond = (url: string, init?: RequestInit): Response => {
  const method = init?.method ?? 'GET'
  if (url.endsWith(`/routines/day/${day.date}`)) return json(day)
  const check = /\/routines\/(\w+)\/checks\/([\d-]+)$/.exec(url)
  if (check) {
    const checked = method === 'PUT'
    day = {
      ...day,
      moments: day.moments.map((m) => ({ ...m, routines: m.routines.map((r) => (r.id === check[1] ? { ...r, checked } : r)) })),
    }

    return json({ id: check[1], checkedToday: checked })
  }

  return json({ error: 'not found' }, 404)
}

const Where = () => {
  const { pathname, search } = useLocation()

  return <p data-testid="where">{`${pathname}${search}`}</p>
}

const renderWidget = () =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<RoutinesWidget />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )

describe('RoutinesWidget (MAG-512)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    day = {
      date: '2026-10-15',
      moments: [
        { anchor: 'wake', time: '07:00', routines: [routine('01WATER', "Grand verre d'eau", { checked: true }), routine('01VITA', 'Vitamines')] },
        { anchor: 'time', time: '22:30', routines: [routine('01READ', 'Lire dix pages', { anchor: 'time', time: '22:30' })] },
      ],
    }
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    mockFetch.mockImplementation((url, init) => Promise.resolve(respond(String(url), init)))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('counts what is done of what is due today, and groups the routines by moment', async () => {
    renderWidget()

    expect(await screen.findByText("1 sur 3 aujourd'hui")).toBeInTheDocument()
    const morning = screen.getByRole('region', { name: 'Au réveil · 7 h' })
    expect(within(morning).getAllByRole('checkbox').map((c) => c.getAttribute('aria-label'))).toEqual([
      "Grand verre d'eau : fait aujourd'hui",
      "Vitamines : fait aujourd'hui",
    ])
    expect(within(morning).getByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" })).toBeChecked()
    const evening = screen.getByRole('region', { name: '22 h 30' })
    expect(within(evening).getByRole('checkbox', { name: "Lire dix pages : fait aujourd'hui" })).not.toBeChecked()
  })

  test('ticks in place, then unticks, and the count follows', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderWidget()
    await screen.findByText("1 sur 3 aujourd'hui")

    await user.click(screen.getByRole('checkbox', { name: "Vitamines : fait aujourd'hui" }))
    expect(await screen.findByText("2 sur 3 aujourd'hui")).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: "Vitamines : fait aujourd'hui" })).toBeChecked()
    expect(mockFetch).toHaveBeenCalledWith('http://localhost/api/routines/01VITA/checks/2026-10-15', expect.objectContaining({ method: 'PUT' }))

    await user.click(screen.getByRole('checkbox', { name: "Vitamines : fait aujourd'hui" }))
    expect(await screen.findByText("1 sur 3 aujourd'hui")).toBeInTheDocument()
    expect(mockFetch).toHaveBeenCalledWith('http://localhost/api/routines/01VITA/checks/2026-10-15', expect.objectContaining({ method: 'DELETE' }))
  })

  test('a failed tick says so and keeps the count', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderWidget()
    await screen.findByText("1 sur 3 aujourd'hui")
    mockFetch.mockImplementation((url, init) =>
      Promise.resolve(init?.method === 'PUT' ? json({ error: 'Routine inconnue' }, 404) : respond(String(url), init)),
    )

    await user.click(screen.getByRole('checkbox', { name: "Vitamines : fait aujourd'hui" }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur : Routine inconnue', { type: 'info' }))
    expect(screen.getByText("1 sur 3 aujourd'hui")).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: "Vitamines : fait aujourd'hui" })).not.toBeChecked()
  })

  test('its title opens the routines of the tasks', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderWidget()
    await screen.findByText("1 sur 3 aujourd'hui")

    await user.click(screen.getByRole('link', { name: 'Routines' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/tasks?tab=routines')
  })

  test('follows a tick that arrives on Mercure', async () => {
    renderWidget()
    await screen.findByText("1 sur 3 aujourd'hui")

    day = {
      ...day,
      moments: day.moments.map((m) => ({ ...m, routines: m.routines.map((r) => (r.id === '01READ' ? { ...r, checked: true } : r)) })),
    }
    act(() => MockEventSource.last?.onmessage?.({ data: '{}' } as MessageEvent))

    expect(await screen.findByText("2 sur 3 aujourd'hui")).toBeInTheDocument()
  })

  test('shows nothing when no routine is due today', async () => {
    day = { ...day, moments: [] }
    const { container } = renderWidget()

    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    await act(async () => {})
    expect(container).toBeEmptyDOMElement()
  })

  test('says the routines are unavailable when they cannot be read', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    mockFetch.mockResolvedValue(json({ error: 'boom' }, 500))
    renderWidget()

    expect(await screen.findByRole('alert')).toHaveTextContent('Routines indisponibles')
    expect(screen.getByRole('link', { name: 'Routines' })).toBeInTheDocument()
  })
})
