import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { veilleuseLightTheme } from '../../theme'
import { JournalPage } from './JournalPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

const day = (mood: number | null) => ({
  id: '01TODAY',
  date: '2026-11-07',
  weightKg: 71.6,
  bodyFatPercent: null,
  weighedAt: '2026-11-07T07:12:00+01:00',
  steps: null,
  eatenKcal: null,
  expendedKcal: null,
  balanceKcal: null,
  sportMinutes: null,
  mood,
  fatigue: mood === null ? null : 3,
  note: mood === null ? null : 'Bonne soirée',
  notedBy: mood === null ? null : 'maggie',
  notedAt: mood === null ? null : '2026-11-07T21:42:00+01:00',
})

// What Maggie notes (update_health_day, the bilan du soir) or the phone writes shows without a reload: the journal is served by Doctrine.
describe('JournalPage — live updates from Mercure', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 7, 22, 0))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('a day Maggie noted reads the journal again', async () => {
    let told = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const { pathname } = new URL(url, 'http://localhost')
        if (pathname === '/api/health/days') return json({ days: [day(told ? 8 : null)] })

        return json(day(told ? 8 : null))
      }),
    )
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme}>
          <JournalPage />
        </AdminContext>
      </MemoryRouter>,
    )
    const entry = screen.getByRole('region', { name: 'Entrée du jour' })
    await within(entry).findByTestId('journal-weight-value')
    expect(within(entry).queryByTestId('journal-noted-by')).not.toBeInTheDocument()

    told = true
    await emitMercure('/api/health/days/{date}', day(8))

    expect(await within(entry).findByTestId('journal-noted-by')).toHaveTextContent('avec Maggie · 21:42')
    expect(within(within(entry).getByRole('radiogroup', { name: 'Moral' })).getByRole('radio', { name: '8' })).toHaveAttribute('aria-checked', 'true')
    expect(within(entry).getByRole('textbox', { name: 'Note' })).toHaveValue('Bonne soirée')
    await waitFor(() => expect(screen.getByTestId('journal-row-2026-11-07')).toHaveTextContent('Bonne soirée'))
  })
})
