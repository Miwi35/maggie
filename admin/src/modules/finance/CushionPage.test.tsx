import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { CushionPage } from './CushionPage'

const STATUS = {
  state: 'complete',
  targetMonths: 3,
  monthlyNetIncomeCents: 250000,
  targetCents: 750000,
  currentCents: 750000,
  deficitCents: 0,
  coveragePercent: 100,
  monthsCovered: 3,
  rechargeCapCents: 15000,
  rechargeTargetMonths: 6,
  monthlyRechargeCents: 0,
  rechargeMonths: 0,
  isCappedByRechargeCap: false,
  blocksGreenScore: false,
  isConfigured: true,
  accounts: [
    { id: 'a1', name: 'Livret A', bank: 'Boursorama', balanceCents: 500000, currency: 'EUR' },
    { id: 'a2', name: 'Livret A', bank: 'Crédit Agricole', balanceCents: 250000, currency: 'EUR' },
    { id: 'a3', name: 'Livret', bank: null, balanceCents: 0, currency: 'EUR' },
  ],
}

describe('CushionPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(STATUS) }))
  })

  test('names each cushion account as bank · currency · name', async () => {
    render(
      <AdminContext dataProvider={testDataProvider()}>
        <CushionPage />
      </AdminContext>,
    )

    expect(await screen.findByText(/^Boursorama · EUR · Livret A —/)).toBeInTheDocument()
    expect(screen.getByText(/^Crédit Agricole · EUR · Livret A —/)).toBeInTheDocument()
    expect(screen.getByText(/^EUR · Livret —/)).toBeInTheDocument()
  })
})
