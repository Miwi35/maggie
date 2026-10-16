import { beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UpcomingTab } from './UpcomingTab'
import { COURSES, emptyProjection, GIFT, LOGEMENT, RENT, SALARY, shortMonth, upcomingFixture } from './upcomingFixture'
import type { UpcomingProjection } from './useUpcomingProjection'

const mercure = vi.hoisted(() => ({ handlers: [] as Array<() => void> }))
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], onMessage: () => void) => {
    mercure.handlers = [onMessage]
  },
}))
const publishMercure = () => mercure.handlers.forEach((handler) => handler())

const answer = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) })

const stubApi = (payload: UpcomingProjection | unknown, ok = true) => {
  const fetchMock = vi.fn(() => answer(payload, ok))
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

// formatCents separates thousands with a narrow no-break space, and the unit with a no-break one.
const euros = (text: string) => new RegExp(text.replace(/ /g, '\\s'))

describe('UpcomingTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('reads /api/finance/upcoming with the bearer token', async () => {
    const fetchMock = stubApi(upcomingFixture())
    render(<UpcomingTab />)
    await screen.findByTestId('upcoming-week')

    expect(fetchMock).toHaveBeenCalledWith('/api/finance/upcoming', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('draws the week after: its range, its totals, its items with their source and its seven days', async () => {
    stubApi(upcomingFixture())
    render(<UpcomingTab />)

    const week = await screen.findByTestId('upcoming-week')
    expect(within(week).getByRole('heading', { name: 'La semaine suivante' })).toBeInTheDocument()
    expect(week).toHaveTextContent('du lundi 19 octobre au dimanche 25 octobre')
    expect(within(week).getByTestId('upcoming-week-income')).toHaveTextContent(euros('2 500,00 €'))
    expect(within(week).getByTestId('upcoming-week-expense')).toHaveTextContent(euros('20,00 €'))
    expect(within(week).getByTestId('upcoming-week-net')).toHaveTextContent(euros('2 480,00 €'))
    expect(within(week).getAllByTestId('upcoming-day')).toHaveLength(7)

    const [income] = within(within(week).getByTestId('upcoming-week-incomes')).getAllByTestId('upcoming-item')
    expect(income).toHaveAttribute('data-label', 'Salaire ACME')
    expect(income).toHaveAttribute('data-date', '2026-10-25')
    expect(income).toHaveTextContent('récurrente')

    const [expense] = within(within(week).getByTestId('upcoming-week-expenses')).getAllByTestId('upcoming-item')
    expect(expense).toHaveTextContent('Marché')
    expect(expense).toHaveTextContent('engagée')
  })

  test('draws the month after, named in French, with a bar per day and the planned lines', async () => {
    stubApi(upcomingFixture())
    render(<UpcomingTab />)

    const month = await screen.findByTestId('upcoming-month')
    expect(month).toHaveTextContent('novembre 2026')
    expect(within(month).getAllByTestId('upcoming-day')).toHaveLength(30)
    const rentDay = within(month).getAllByTestId('upcoming-day').find((day) => day.dataset.date === RENT.date)
    expect(rentDay).toHaveAttribute('data-expense-cents', '90000')

    const expenses = within(within(month).getByTestId('upcoming-month-expenses')).getAllByTestId('upcoming-item')
    expect(expenses.map((item) => item.dataset.label)).toEqual([RENT.label, GIFT.label])
    expect(expenses[1]).toHaveTextContent('prévue')
    expect(within(month).getByTestId('upcoming-month-net')).toHaveTextContent(euros('1 570,00 €'))
  })

  test('shows each envelope with its projected spending, its committed part and flags the one committed past itself', async () => {
    stubApi(upcomingFixture())
    render(<UpcomingTab />)

    const courses = await screen.findByTestId('upcoming-envelope-Courses')
    expect(courses).toHaveAttribute('data-overcommitted', 'false')
    expect(courses).toHaveTextContent(euros('400,00 €'))
    expect(courses).toHaveTextContent(euros('80,00 € engagés sur 400,00 €'))
    expect(courses).not.toHaveTextContent("engagé au-delà de l'enveloppe")

    const housing = screen.getByTestId('upcoming-envelope-Logement')
    expect(housing).toHaveAttribute('data-overcommitted', 'true')
    expect(housing).toHaveTextContent("engagé au-delà de l'enveloppe")
    expect(housing).toHaveTextContent(euros('900,00 €'))

    expect(screen.getByTestId('upcoming-envelopes')).toHaveTextContent('Enveloppes · novembre 2026 (ajustées)')
    expect(screen.getByTestId('upcoming-envelopes')).toHaveTextContent('une suggestion non appliquée ne pèse rien')
    expect(screen.getByTestId('upcoming-unbudgeted')).toHaveTextContent('Hors enveloppe')
    expect(screen.getByTestId('upcoming-unbudgeted')).toHaveTextContent(euros('30,00 €'))
  })

  test('puts what is left toward the projects as the headline: income less the envelopes and what is outside them', async () => {
    stubApi(upcomingFixture())
    render(<UpcomingTab />)

    const remaining = await screen.findByTestId('upcoming-remaining')
    expect(remaining).toHaveAttribute('data-short', 'false')
    expect(within(remaining).getByRole('heading', { name: 'Reste vers les projets' })).toBeInTheDocument()
    expect(within(remaining).getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('1 170,00 €'))
    expect(within(remaining).getByTestId('upcoming-remaining-income')).toHaveTextContent(euros('2 500,00 €'))
    expect(within(remaining).getByRole('img')).toHaveAccessibleName(/2\s500,00\s€ de revenus pour 1\s330,00\s€ de dépenses projetées/)
  })

  test('says the month is short, without a minus sign or blame, when it spends more than it earns', async () => {
    stubApi(upcomingFixture({ month: shortMonth() }))
    render(<UpcomingTab />)

    const remaining = await screen.findByTestId('upcoming-remaining')
    expect(remaining).toHaveAttribute('data-short', 'true')
    expect(within(remaining).getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('Mois déficitaire de 330,00 €'))
    expect(remaining).not.toHaveTextContent('-330')
    expect(remaining).not.toHaveTextContent('−330')
  })

  test('says so when nothing is expected: no item, no envelope', async () => {
    stubApi(emptyProjection())
    render(<UpcomingTab />)

    const week = await screen.findByTestId('upcoming-week')
    expect(within(week).getByText('Aucun revenu attendu sur cette période.')).toBeInTheDocument()
    expect(within(week).getByText('Aucune opération récurrente ni dépense prévue sur cette période.')).toBeInTheDocument()
    expect(within(week).queryAllByTestId('upcoming-item')).toHaveLength(0)
    expect(screen.getByTestId('upcoming-envelopes-empty')).toHaveTextContent('Aucune enveloppe pour ce mois.')
    expect(screen.getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('0,00 €'))
  })

  test('says the read failed and retries on demand', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn()
    fetchMock.mockImplementationOnce(() => answer({ error: 'boom' }, false))
    fetchMock.mockImplementation(() => answer(upcomingFixture()))
    vi.stubGlobal('fetch', fetchMock)
    render(<UpcomingTab />)

    expect(await screen.findByText("Les prévisions n'ont pas pu être chargées.")).toBeInTheDocument()
    expect(screen.queryByTestId('upcoming-week')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('upcoming-week')).toBeInTheDocument()
    expect(screen.queryByText("Les prévisions n'ont pas pu être chargées.")).not.toBeInTheDocument()
  })

  test('keeps the last projection on screen when a refresh fails', async () => {
    const fetchMock = stubApi(upcomingFixture())
    render(<UpcomingTab />)
    await screen.findByTestId('upcoming-remaining')

    fetchMock.mockImplementation(() => answer({}, false))
    act(() => publishMercure())

    expect(await screen.findByText("Les prévisions n'ont pas pu être chargées.")).toBeInTheDocument()
    expect(screen.getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('1 170,00 €'))
  })

  test('a Mercure message reads again: an envelope raised changes the remainder', async () => {
    const fetchMock = stubApi(upcomingFixture())
    render(<UpcomingTab />)
    expect(await screen.findByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('1 170,00 €'))

    // Courses goes from 400 € to 600 €: 200 € less for the projects.
    const raised = upcomingFixture()
    raised.month.envelopes = [{ ...COURSES, amountCents: 60000, projectedCents: 60000 }, LOGEMENT]
    raised.month.envelopesCents = 150000
    raised.month.projectedExpenseCents = 153000
    raised.month.remainingCents = SALARY.amountCents - 153000
    fetchMock.mockClear()
    fetchMock.mockImplementation(() => answer(raised))
    act(() => publishMercure())

    await waitFor(() => expect(screen.getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('970,00 €')))
    expect(screen.getByTestId('upcoming-envelope-Courses')).toHaveTextContent(euros('600,00 €'))
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('only the last read written may land: a slow stale answer does not overwrite a newer one', async () => {
    let releaseFirst: (value: unknown) => void = () => undefined
    const fetchMock = vi.fn()
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve }))
    const newer = upcomingFixture()
    newer.month.remainingCents = 55500
    fetchMock.mockImplementation(() => answer(newer))
    vi.stubGlobal('fetch', fetchMock)
    render(<UpcomingTab />)

    act(() => publishMercure())
    await waitFor(() => expect(screen.getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('555,00 €')))

    await act(async () => {
      releaseFirst({ ok: true, status: 200, json: () => Promise.resolve(upcomingFixture()) })
    })

    expect(screen.getByTestId('upcoming-remaining-amount')).toHaveTextContent(euros('555,00 €'))
  })
})
