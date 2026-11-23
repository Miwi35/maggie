import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../../theme'
import { cardRegistry } from '../cardRegistry'
import homeConfig from '../home.config'
import { DigestTile } from './DigestTile'
import type { HomeDay } from '../homeDay'

const NOW = new Date(2026, 9, 27, 7, 30, 0)
const block = (over: Record<string, unknown> = {}) => ({
  type: 'digest',
  day: '2026-10-27',
  text: 'Il fait beau.',
  expiresAt: new Date(2026, 9, 27, 14, 0, 0).toISOString(),
  openedAt: null,
  ...over,
})
const message = (over: Record<string, unknown> = {}) => ({
  id: 'm-d',
  content: 'Votre digest du matin est disponible.',
  audio: { type: 'audio/mpeg', durationMs: 72000, size: 1, url: '/a', transcript: 't' },
  blocks: [block(over)],
})

let answer: () => { ok: boolean; status: number; json: () => Promise<unknown> }
const reply = (body: unknown) => () => ({ ok: true, status: 200, json: () => Promise.resolve(body) })

const renderTile = (day: HomeDay = 'today', theme = veilleuseLightTheme) =>
  render(
    <MemoryRouter>
      <ThemeProvider theme={theme}>
        <DigestTile day={day} />
      </ThemeProvider>
    </MemoryRouter>,
  )

const flush = () => act(async () => { await Promise.resolve() })

describe('DigestTile', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    vi.setSystemTime(NOW)
    answer = reply([message()])
    vi.stubGlobal('fetch', vi.fn(async () => answer()))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test("shows today's unopened digest before its expiry", async () => {
    renderTile()
    await flush()
    const tile = screen.getByRole('link', { name: 'Digest du matin, mardi 27 octobre, 1 minute 12 : écouter' })
    expect(tile).toHaveAttribute('href', '/digest?message=m-d')
    expect(tile).toHaveTextContent('Mardi 27 octobre · 1:12')
    expect(tile).toHaveTextContent('Écouter')
  })

  test.each([
    ['opened', 'today', [message({ openedAt: '2026-10-27T07:35:00Z' })]],
    ['another day', 'today', [message({ day: '2026-10-26' })]],
    ['expired', 'today', [message({ expiresAt: new Date(2026, 9, 27, 7, 0, 0).toISOString() })]],
    ['no digest', 'today', []],
    ['Demain', 'tomorrow', [message()]],
  ] as [string, HomeDay, unknown[]][])('hides when %s', async (_name, day, body) => {
    answer = reply(body)
    const { container } = renderTile(day)
    await flush()
    expect(container).toBeEmptyDOMElement()
  })

  test('hides itself at expiresAt', async () => {
    renderTile()
    await flush()
    expect(screen.getByTestId('home-digest-tile')).toBeInTheDocument()
    await act(async () => {
      vi.setSystemTime(new Date(2026, 9, 27, 14, 0, 1))
      vi.advanceTimersByTime(6.5 * 3600 * 1000)
    })
    expect(screen.queryByTestId('home-digest-tile')).not.toBeInTheDocument()
  })

  test('reads again when the window comes back, and keeps the last answer when the read fails', async () => {
    renderTile()
    await flush()
    answer = reply([message({ openedAt: '2026-10-27T07:35:00Z' })])
    await act(async () => {
      window.dispatchEvent(new Event('focus'))
    })
    await flush()
    expect(screen.queryByTestId('home-digest-tile')).not.toBeInTheDocument()
  })

  test('a failed read keeps the last answer', async () => {
    renderTile()
    await flush()
    answer = () => ({ ok: false, status: 500, json: () => Promise.resolve({}) })
    await act(async () => {
      window.dispatchEvent(new Event('focus'))
    })
    await flush()
    expect(screen.getByTestId('home-digest-tile')).toBeInTheDocument()
  })

  test('is placed first in the launch slot of the Accueil', () => {
    expect(homeConfig.launch).toEqual([{ key: 'digest' }])
    expect(cardRegistry.digest).toBe(DigestTile)
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('renders in the %s theme', async (_name, theme) => {
    renderTile('today', theme)
    await flush()
    expect(screen.getByTestId('home-digest-tile')).toBeInTheDocument()
  })
})
