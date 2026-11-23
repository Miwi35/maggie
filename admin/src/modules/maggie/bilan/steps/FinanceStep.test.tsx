import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../../../theme'
import { financeSay, useFinanceSlot } from './FinanceStep'

const TODAY = '2026-11-23'

const curve = (length: number, elapsed: number) =>
  Array.from({ length }, (_, index) => ({ date: `2026-11-${String(index + 1).padStart(2, '0')}`, spentCents: index < elapsed ? 100 : null, expectedCents: 100 }))

const period = (spentCents: number, plannedCents: number, length: number, elapsed: number) => ({
  status: 'green',
  label: 'Dans le rythme',
  reasons: [],
  rhythm: { spentCents, expectedSpentCents: 0, plannedCents, unclassifiedCount: 0, unclassifiedCents: 0, curve: curve(length, elapsed), debits: [] },
})

let transactions: Record<string, unknown>[]
let planned: { week: number; month: number }
let broken: boolean

const json = (body: unknown, code = 200) => Promise.resolve(new Response(JSON.stringify(body), { status: code }))

beforeEach(() => {
  broken = false
  planned = { week: 32000, month: 120000 }
  transactions = [
    { amountCents: -2500, label: 'Carrefour', category: '/api/categories/C1', status: 'spent', transferKind: 'none' },
    { amountCents: -1280, label: 'Boulangerie', category: null, status: 'spent', transferKind: 'none' },
    { amountCents: -500, label: 'Virement épargne', category: null, status: 'spent', transferKind: 'internal' },
    { amountCents: 9000, label: 'Salaire', category: null, status: 'spent', transferKind: 'none' },
  ]
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (broken) return json({}, 500)
      if (url.includes('/finance/scores')) return json({ date: '2026-11-23', week: period(18600, planned.week, 7, 4), month: period(90000, planned.month, 30, 23), global: { status: 'green', label: '', reasons: [] } })
      if (url.includes('/categories')) return json({ member: [{ '@id': '/api/categories/C1', name: 'Courses' }] })
      if (url.includes('/transactions')) return json({ member: transactions })

      return json({}, 404)
    }),
  )
})

afterEach(() => vi.unstubAllGlobals())

const Harness = () => {
  const slot = useFinanceSlot(TODAY, true)

  return (
    <div>
      <p data-testid="say">{slot.say}</p>
      {slot.content}
    </div>
  )
}

const renderStep = (theme = veilleuseDarkTheme) =>
  render(
    <ThemeProvider theme={theme}>
      <Harness />
    </ThemeProvider>,
  )

describe('FinanceStep', () => {
  test('sums the day’s debits by category, and by label when unfiled', async () => {
    renderStep()

    const card = await screen.findByTestId('bilan-day-spending')
    expect(within(card).getByTestId('bilan-day-total')).toHaveTextContent('37,80')
    expect(card).toHaveTextContent('Courses 25,00')
    expect(card).toHaveTextContent('Boulangerie 12,80')
    expect(card).not.toHaveTextContent('Virement épargne')
    expect(card).not.toHaveTextContent('Salaire')
    expect(screen.getByTestId('say')).toHaveTextContent('Aujourd’hui, 37,80')
    expect(screen.getByTestId('say')).toHaveTextContent('Voilà où en sont la semaine et le mois.')
  })

  test('shows spent against planned with the time gone', async () => {
    renderStep()

    const week = await screen.findByTestId('bilan-rhythm-semaine')
    expect(week).toHaveTextContent('186 € / 320 €')
    expect(week).toHaveTextContent('4e jour sur 7')
    expect(within(week).getByRole('img').getAttribute('aria-label')?.replace(/\s/g, ' ')).toBe('Semaine : 186 € dépensés sur 320 €, 4e jour sur 7')
    expect(within(week).getByTestId('bilan-elapsed-mark')).toBeInTheDocument()
    expect(screen.getByTestId('bilan-rhythm-mois')).toHaveTextContent('23 jours sur 30')
  })

  test('without a plan the line shows the spent amount only, no bar', async () => {
    planned = { week: 0, month: 0 }
    renderStep()

    const week = await screen.findByTestId('bilan-rhythm-semaine')
    expect(week).toHaveTextContent('186 €')
    expect(week).not.toHaveTextContent('/')
    expect(within(week).queryByRole('img')).not.toBeInTheDocument()
  })

  test('without a debit Maggie says so', async () => {
    transactions = []
    renderStep()

    expect(await screen.findByTestId('bilan-day-total')).toHaveTextContent('0,00')
    expect(screen.getByTestId('say')).toHaveTextContent('Aucune dépense aujourd’hui. Voilà où en sont la semaine et le mois.')
    expect(financeSay({ totalCents: 0, parts: [] })).toContain('Aucune dépense')
  })

  test('a failed read says so and offers to retry', async () => {
    broken = true
    renderStep()

    expect(await screen.findByText('Les comptes n’ont pas pu être chargés.')).toBeInTheDocument()
    broken = false
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByTestId('bilan-day-spending')).toBeInTheDocument()
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('draws no verdict, in the %s theme', async (_name, theme) => {
    renderStep(theme)

    const week = await screen.findByTestId('bilan-rhythm-semaine')
    expect(screen.queryByText(/dépassé|en retard|attention|bravo|vert|rouge/i)).not.toBeInTheDocument()
    expect(week.innerHTML).not.toMatch(/#[0-9a-f]{3,8}\b/i)
  })
})
