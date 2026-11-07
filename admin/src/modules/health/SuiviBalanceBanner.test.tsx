import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { SuiviBalanceBanner } from './SuiviBalanceBanner'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const balance = (over: Record<string, unknown> = {}) => ({
  date: '2026-10-21',
  eatenKcal: 1500,
  plannedKcal: 500,
  expendedKcal: 1900,
  balanceKcal: 100,
  proteinG: 0,
  carbsG: 0,
  fatG: 0,
  plannedMeals: [],
  ...over,
})

function renderBanner() {
  render(
    <ThemeProvider theme={veilleuseLightTheme}>
      <MemoryRouter initialEntries={['/health/suivi']}>
        <Routes>
          <Route path="/health/suivi" element={<SuiviBalanceBanner />} />
          <Route path="/health/nutrition" element={<p>Page Nutrition</p>} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('SuiviBalanceBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 21, 15, 0))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('shows today’s balance in the zone’s tone, and opens Santé › Nutrition', async () => {
    const user = userEvent.setup()
    const fetcher = vi.fn(async () => json(balance()))
    vi.stubGlobal('fetch', fetcher)
    renderBanner()

    const banner = await screen.findByRole('button', { name: 'Bilan du jour : ouvrir Nutrition' })
    expect(fetcher).toHaveBeenCalledWith('/api/health/daily-balance?date=2026-10-21', expect.anything())
    expect(screen.getByTestId('balance-value')).toHaveTextContent('+100 kcal')
    expect(screen.getByTestId('balance-value')).toHaveAttribute('data-zone', 'balanced')
    expect(screen.getByTestId('balance-word')).toHaveTextContent('Équilibre')

    await user.click(banner)
    expect(screen.getByText('Page Nutrition')).toBeInTheDocument()
  })

  test('without an expenditure it shows what was eaten and planned', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(balance({ expendedKcal: null, balanceKcal: null }))))
    renderBanner()

    expect(await screen.findByTestId('balance-eaten')).toHaveTextContent(/^Mangé 1\s500 kcal · Prévu 500 kcal$/)
    expect(screen.queryByTestId('balance-value')).not.toBeInTheDocument()
  })

  test('stays out of the way when the balance cannot be read', async () => {
    const fetcher = vi.fn(async () => json({}, 500))
    vi.stubGlobal('fetch', fetcher)
    renderBanner()

    await waitFor(() => expect(fetcher).toHaveBeenCalled())
    expect(screen.queryByTestId('suivi-balance-banner')).not.toBeInTheDocument()
  })
})
