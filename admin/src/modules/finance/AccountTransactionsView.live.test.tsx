import { screen } from '@testing-library/react'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { emitMercure, liveListSuite, renderLiveList } from '../../test/mercure'
import { AccountTransactionsView } from './AccountTransactionsView'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const route = { path: '/accounts/:id/transactions', entry: '/accounts/01ACCOUNT/transactions' }

const listed = {
  id: '/api/transactions/01TRANSACTION',
  label: 'Courses Carrefour',
  amountCents: -4250,
  currency: 'EUR',
  bookedAt: '2026-10-10T00:00:00+00:00',
  status: 'committed',
  isExceptional: false,
  account: '/api/accounts/01ACCOUNT',
  category: null,
}

const created = {
  label: 'Boulangerie',
  amountCents: -320,
  currency: 'EUR',
  bookedAt: '2026-10-12',
  status: 'committed',
  isExceptional: false,
  categoryId: null,
}

liveListSuite({
  name: 'AccountTransactionsView',
  screen: () => <AccountTransactionsView />,
  resource: 'transactions',
  topic: '/api/transactions/{id}',
  route,
  listed,
  listedText: 'Courses Carrefour',
  created: { payload: { ...created, accountId: '01ACCOUNT' }, text: 'Boulangerie' },
  updated: { payload: { label: 'Courses Carrefour City' }, text: 'Courses Carrefour City' },
})

describe('AccountTransactionsView — recurring series', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('a transaction attached to a series by an event carries the badge without a reload', async () => {
    renderLiveList(<AccountTransactionsView />, 'transactions', [listed], route)
    await screen.findByText('Courses Carrefour')
    expect(screen.queryByText(/Série \u00b7/)).not.toBeInTheDocument()

    await emitMercure('/api/transactions/{id}', { '@id': listed.id, recurringOperationId: '01SERIES' })

    expect(await screen.findByText(/Série \u00b7/)).toBeInTheDocument()
  })
})

describe('AccountTransactionsView — another account', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('a transaction created on another account does not join this one', async () => {
    renderLiveList(<AccountTransactionsView />, 'transactions', [listed], route)
    await screen.findByText('Courses Carrefour')

    await emitMercure('/api/transactions/{id}', {
      '@id': '/api/transactions/01OTHER',
      ...created,
      accountId: '01ELSEWHERE',
    })

    expect(screen.queryByText('Boulangerie')).not.toBeInTheDocument()
  })
})
