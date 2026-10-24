import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { TOKENS } from '../../design/tokens'
import { emitMercure } from '../../test/mercure'
import { FinanceHomeCard } from './FinanceHomeCard'
import type { HomeDay } from '../home/homeDay'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => {
  const { pathname, search } = useLocation()

  return <output data-testid="where">{pathname + search}</output>
}

const renderCard = (day: HomeDay = 'today', theme = veilleuseLightTheme) =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={theme}>
        <FinanceHomeCard day={day} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const ring = (extra: Record<string, unknown> = {}) => ({
  status: 'on_track',
  label: 'Dans le rythme',
  budgetCents: 30000,
  remainingCents: 21000,
  expectedRemainingCents: 18000,
  ...extra,
})

const STATE = {
  week: ring({ budgetCents: 7000, remainingCents: 4500, expectedRemainingCents: 4000 }),
  month: ring(),
  toQualifyCount: 3,
  nextProject: { projectId: 'p1', name: 'Vélo', amountCents: 100000, gatheredCents: 74000, purchaseMonth: '2099-10' },
}

const ok = (body: unknown) => ({ ok: true, json: () => Promise.resolve(body) })

const ringOf = (period: 'week' | 'month') => screen.getAllByTestId('home-finance-ring').find((r) => r.dataset.period === period) as HTMLElement

describe('FinanceHomeCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('shows the week and the month rings with what is left, in €', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(STATE)))
    renderCard()

    await screen.findAllByTestId('home-finance-ring')

    expect(within(ringOf('week')).getByTestId('home-finance-remaining').textContent).toMatch(/45,00\s€/)
    expect(within(ringOf('month')).getByTestId('home-finance-remaining').textContent).toMatch(/210,00\s€/)
    expect(ringOf('week')).toHaveAccessibleName(/Semaine : Dans le rythme/)
  })

  test('says « Dans le rythme » on track, in the on-track colour of the charter', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(STATE)))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    const status = within(ringOf('month')).getByTestId('home-finance-status')

    expect(ringOf('month')).toHaveAttribute('data-status', 'on_track')
    expect(status).toHaveTextContent('Dans le rythme')
    expect(status).toHaveStyle({ color: TOKENS.rhythm.onTrack.light })
  })

  test('says « Au-dessus du rythme » above it, in the above colour, in dark mode too', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(ok({ ...STATE, month: ring({ status: 'above', label: 'Au-dessus du rythme', remainingCents: -5800 }) })),
    )
    renderCard('today', veilleuseDarkTheme)
    await screen.findAllByTestId('home-finance-ring')

    const status = within(ringOf('month')).getByTestId('home-finance-status')

    expect(ringOf('month')).toHaveAttribute('data-status', 'above')
    expect(status).toHaveTextContent('Au-dessus du rythme')
    expect(status).toHaveStyle({ color: TOKENS.rhythm.above.dark })
    expect(within(ringOf('week')).getByTestId('home-finance-status')).toHaveTextContent('Dans le rythme')
  })

  test('a ring with no envelope says so instead of an amount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ ...STATE, week: ring({ budgetCents: 0, remainingCents: 0, expectedRemainingCents: 0 }) })))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    expect(within(ringOf('week')).getByText('Aucune enveloppe')).toBeInTheDocument()
    expect(within(ringOf('week')).queryByTestId('home-finance-status')).not.toBeInTheDocument()
    expect(within(ringOf('week')).queryByTestId('home-finance-remaining')).toBeNull()
  })

  test('tells how many lines are left to qualify and opens the review on them', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(STATE)))
    renderCard()

    const pill = await screen.findByTestId('home-finance-to-qualify')
    expect(pill).toHaveTextContent('3 à qualifier')

    await user.click(pill)

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review?')
    expect(screen.getByTestId('where')).toHaveTextContent('state=unrecognised')
  })

  test('has no « à qualifier » pill when there is nothing to qualify', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ ...STATE, toQualifyCount: 0 })))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    expect(screen.queryByTestId('home-finance-to-qualify')).toBeNull()
  })

  test('shows the next project with what is gathered, and none when there is none', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(STATE)).mockResolvedValueOnce(ok({ ...STATE, nextProject: null })))
    renderCard()

    const project = await screen.findByTestId('home-finance-next-project')

    expect(project).toHaveTextContent('Vélo')
    expect(project.textContent).toMatch(/740,00\s€ sur 1\s000,00\s€/)
    expect(within(project).getByRole('progressbar', { name: 'Vélo : 74 % atteint' })).toBeInTheDocument()

    await act(async () => {
      await emitMercure('/home/{module}', { action: 'refresh', module: 'finance' })
    })

    await waitFor(() => expect(screen.queryByTestId('home-finance-next-project')).toBeNull())
  })

  test('opens Finance › Dashboard from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(STATE)))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    await user.click(screen.getByRole('link', { name: 'Finance' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/dashboard')
  })

  test('does not follow the day: the same card is asked for tomorrow', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(STATE))
    vi.stubGlobal('fetch', fetchMock)
    renderCard('tomorrow')

    expect(await screen.findAllByTestId('home-finance-ring')).toHaveLength(2)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/home/finance?day=tomorrow')
  })

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({}) }).mockResolvedValueOnce(ok(STATE)))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findAllByTestId('home-finance-ring')).toHaveLength(2)
  })
})
