import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { emitMercure } from '../../test/mercure'
import { LIVE_DEBOUNCE_MS } from '../calendar/calendarLive'
import { dashboardFixture } from '../finance/dashboardFixture'
import { Home } from './Home'
import { cardRegistry } from './cardRegistry'
import homeConfig from './home.config'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const fetchMock = vi.fn()

const respond = (url: string) => {
  const body = url.includes('/finance/dashboard') ? dashboardFixture() : { member: [] }

  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
}

const readsByPath = () => {
  const counts = new Map<string, number>()
  for (const [input] of fetchMock.mock.calls) {
    const path = new URL(String(input), 'http://localhost').pathname
    counts.set(path, (counts.get(path) ?? 0) + 1)
  }

  return counts
}

const burst = async (size: number) => {
  for (let i = 0; i < size; i++) await emitMercure('/api/tasks/{id}', { '@id': `/api/tasks/${i}` })
}

const settle = () => act(() => vi.advanceTimersByTimeAsync(LIVE_DEBOUNCE_MS))

const renderHome = () =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <Home config={homeConfig} registry={cardRegistry} />
      </ThemeProvider>
    </MemoryRouter>,
  )

describe('Home — live reads', () => {
  beforeEach(async () => {
    localStorage.setItem('token', 'test-jwt')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    fetchMock.mockReset().mockImplementation((input: unknown) => respond(String(input)))
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const loaded = async () => {
    const view = renderHome()
    await waitFor(() => expect(readsByPath().get('/api/finance/dashboard')).toBe(1))
    await act(() => vi.advanceTimersByTimeAsync(100))
    fetchMock.mockClear()

    return view
  }

  test("a burst of 40 messages reads each card's endpoints once", async () => {
    await loaded()

    await burst(40)
    await settle()

    const counts = readsByPath()
    expect(counts.size).toBeGreaterThanOrEqual(10)
    expect(counts.get('/api/finance/dashboard')).toBe(1)
    expect(Object.fromEntries([...counts].filter(([, n]) => n !== 1))).toEqual({})
  })

  test('a single message still reads every card once', async () => {
    await loaded()

    await burst(1)
    expect(fetchMock).not.toHaveBeenCalled()
    await settle()

    expect(readsByPath().get('/api/finance/dashboard')).toBe(1)
    expect(Object.fromEntries([...readsByPath()].filter(([, n]) => n !== 1))).toEqual({})
  })

  test('a message after the read triggers another read', async () => {
    await loaded()
    await burst(3)
    await settle()
    fetchMock.mockClear()

    await burst(1)
    await settle()

    expect(readsByPath().get('/api/finance/dashboard')).toBe(1)
  })

  test('unmounting during a burst sends nothing', async () => {
    const view = await loaded()

    await burst(5)
    view.unmount()
    await act(() => vi.advanceTimersByTimeAsync(LIVE_DEBOUNCE_MS * 6))

    expect(fetchMock).not.toHaveBeenCalled()
  })
})
