import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RoutinesBand } from './RoutinesBand'
import type { DayRoutine, RoutinesDay } from './routinesApi'

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

const calls = (method: string) =>
  mockFetch.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method).map(([url]) => String(url))

const at = (hours: number, minutes = 0) => vi.setSystemTime(new Date(2026, 9, 15, hours, minutes))

const pill = (title: string) => screen.getByRole('button', { name: title })

describe('RoutinesBand (MAG-512)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    at(14, 0)
    day = {
      date: '2026-10-15',
      moments: [
        { anchor: 'wake', time: '07:00', routines: [routine('01WATER', "Grand verre d'eau", { checked: true }), routine('01VITA', 'Vitamines')] },
        { anchor: 'afterLunch', time: '13:00', routines: [routine('01WALK', 'Marcher dix minutes', { anchor: 'afterLunch', time: '13:00' })] },
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

  test('titles itself by the part of the day of the current moment and lists its routines and the earlier ones left', async () => {
    render(<RoutinesBand />)

    const band = await screen.findByRole('region', { name: 'Routines · après-midi' })
    expect(within(band).getAllByRole('button').map((b) => b.textContent)).toEqual(['Vitamines', 'Marcher dix minutes'])
    expect(pill('Vitamines')).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('Lire dix pages')).toBeNull()
    expect(screen.queryByText("Grand verre d'eau")).toBeNull()
  })

  test('before the first moment, it shows the first moment', async () => {
    at(5, 30)
    render(<RoutinesBand />)

    await screen.findByRole('region', { name: 'Routines · matin' })
    expect(pill("Grand verre d'eau")).toHaveAttribute('aria-pressed', 'true')
    expect(pill('Vitamines')).toBeInTheDocument()
  })

  test('is absent when nothing is due', async () => {
    day = { ...day, moments: [] }
    render(<RoutinesBand />)

    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    await act(async () => {})
    expect(screen.queryByRole('region')).toBeNull()
  })

  test('is absent when the earlier moments are done and the current one holds nothing', async () => {
    day = {
      date: day.date,
      moments: [
        { anchor: 'wake', time: '07:00', routines: [routine('01WATER', "Grand verre d'eau", { checked: true })] },
        { anchor: 'afterLunch', time: '13:00', routines: [] },
      ],
    }
    render(<RoutinesBand />)

    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    await act(async () => {})
    expect(screen.queryByRole('region')).toBeNull()
  })

  test('a tap ticks a routine, a second tap takes the tick back', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<RoutinesBand />)
    await screen.findByRole('region', { name: 'Routines · après-midi' })

    await user.click(pill('Marcher dix minutes'))
    await waitFor(() => expect(pill('Marcher dix minutes')).toHaveAttribute('aria-pressed', 'true'))
    expect(calls('PUT')).toEqual(['http://localhost/api/routines/01WALK/checks/2026-10-15'])

    await user.click(pill('Marcher dix minutes'))
    await waitFor(() => expect(pill('Marcher dix minutes')).toHaveAttribute('aria-pressed', 'false'))
    expect(calls('DELETE')).toEqual(['http://localhost/api/routines/01WALK/checks/2026-10-15'])
  })

  test('a failed tick says so and leaves the routine as it was', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<RoutinesBand />)
    await screen.findByRole('region', { name: 'Routines · après-midi' })
    mockFetch.mockImplementation((url, init) =>
      Promise.resolve(init?.method === 'PUT' ? json({ error: 'Routine inconnue' }, 404) : respond(String(url), init)),
    )

    await user.click(pill('Vitamines'))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur : Routine inconnue', { type: 'info' }))
    expect(pill('Vitamines')).toHaveAttribute('aria-pressed', 'false')
    expect(pill('Vitamines')).toBeEnabled()
  })

  test('reads the day again when a tick or a routine arrives on Mercure', async () => {
    render(<RoutinesBand />)
    await screen.findByRole('region', { name: 'Routines · après-midi' })
    expect(pill('Marcher dix minutes')).toHaveAttribute('aria-pressed', 'false')
    expect(MockEventSource.last?.url).toContain(encodeURIComponent('/users/U1/api/routine_checks/:id'))

    // Ticked elsewhere: the phone, or the home widget.
    day = {
      ...day,
      moments: day.moments.map((m) => ({ ...m, routines: m.routines.map((r) => (r.id === '01WALK' ? { ...r, checked: true } : r)) })),
    }
    act(() => MockEventSource.last?.onmessage?.({ data: '{}' } as MessageEvent))

    await waitFor(() => expect(pill('Marcher dix minutes')).toHaveAttribute('aria-pressed', 'true'))
  })
})
