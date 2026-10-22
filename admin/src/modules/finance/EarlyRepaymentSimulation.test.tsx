import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EarlyRepaymentSimulation } from './EarlyRepaymentSimulation'

const TOTAL = {
  amountCents: 9800000,
  penaltyMode: 'legal_cap',
  penaltyCents: 88200,
  remainingInterestCents: 620000,
  realGainCents: 531800,
}

const answer = (status: number, body: unknown) =>
  Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)

const renderSimulation = () =>
  render(<EarlyRepaymentSimulation loanId="01LOAN" principalRemainingCents={9800000} version="v1" />)

describe('EarlyRepaymentSimulation', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('a total repayment shows the penalty, the interest left and the real gain', async () => {
    fetchMock.mockImplementation(() => answer(200, TOTAL))
    renderSimulation()

    expect(await screen.findByTestId('ira-penalty')).toHaveTextContent(/882,00/)
    expect(screen.getByTestId('ira-interest')).toHaveTextContent(/6\s?200,00/)
    expect(screen.getByTestId('ira-gain')).toHaveTextContent(/5\s?318,00/)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/finance/loans/01LOAN/early-repayment')
  })

  test('typing an amount asks the API for that amount, in cents', async () => {
    const user = userEvent.setup()
    fetchMock.mockImplementation(() => answer(200, TOTAL))
    renderSimulation()
    await screen.findByTestId('ira-penalty')

    await user.type(screen.getByLabelText(/Montant remboursé/), '20000')

    await waitFor(() =>
      expect(fetchMock).toHaveBeenLastCalledWith(
        '/api/finance/loans/01LOAN/early-repayment?amountCents=2000000',
        expect.anything(),
      ),
    )
  })

  test("the API's refusal is shown as it came", async () => {
    fetchMock.mockImplementation(() =>
      answer(400, { error: 'This loan does not allow an early repayment (earlyRepaymentAllowed is false).' }),
    )
    renderSimulation()

    expect(await screen.findByRole('alert')).toHaveTextContent('earlyRepaymentAllowed is false')
    expect(screen.queryByTestId('ira-penalty')).not.toBeInTheDocument()
  })

  test('says nothing like advice', async () => {
    fetchMock.mockImplementation(() => answer(200, TOTAL))
    renderSimulation()
    await screen.findByTestId('ira-gain')

    expect(document.body.textContent).not.toMatch(/conseillé|préférable/i)
  })
})
