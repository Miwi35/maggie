import { screen } from '@testing-library/react'
import { describe, test, expect, vi } from 'vitest'
import { renderLiveList } from '../../test/mercure'
import { LoanList } from './LoanList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const loan = {
  id: '/api/loans/01LOAN',
  name: 'Prêt immo',
  lender: 'Crédit Agricole',
  type: 'in_fine',
  principalRemainingCents: 9800000,
  monthlyPaymentCents: 62500,
  annualRateBasisPoints: 180,
  priority: 1,
  currency: 'EUR',
}

describe('LoanList', () => {
  test('names the type of each loan', async () => {
    renderLiveList(<LoanList />, 'loans', [loan])

    expect(await screen.findByText('In fine')).toBeTruthy()
  })

  test('marks a projected loan and only that one', async () => {
    renderLiveList(<LoanList />, 'loans', [
      loan,
      { ...loan, id: '/api/loans/02LOAN', name: 'Prêt travaux', projected: true },
    ])

    await screen.findByText('Prêt travaux')
    expect(screen.getAllByText('Projeté')).toHaveLength(1)
  })
})
