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
  peerClaimsCents: 0,
  peerClaimObjects: [],
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

const withClaims = {
  ...STATUS,
  state: 'building',
  currentCents: 466500,
  deficitCents: 283500,
  coveragePercent: 62,
  monthlyRechargeCents: 15000,
  rechargeMonths: 19,
  blocksGreenScore: true,
  peerClaimsCents: 53500,
  peerClaimObjects: ['Gîte de Crozon', 'Hellfest'],
}

// Intl separates thousands and the euro sign with (narrow) no-break spaces.
const plain = (text: string | null) => (text ?? '').replace(/\s/g, ' ')

const renderPage = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <CushionPage />
    </AdminContext>,
  )

const stubStatus = (status: object) =>
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(status) }))

describe('CushionPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
    stubStatus(STATUS)
  })

  test('shows what was advanced to people, and the part of the shortfall it makes', async () => {
    stubStatus(withClaims)
    renderPage()

    const line = await screen.findByTestId('cushion-peer-claims')
    expect(plain(line.textContent)).toBe(
      'Avancé à des proches (Gîte de Crozon, Hellfest) — revient au remboursement−535,00 €',
    )
    expect(
      screen.getByText((_, el) =>
        el?.tagName === 'P' ? /Il manque 2 835,00 €, dont 535,00 € avancés à des proches/.test(plain(el.textContent)) : false,
      ),
    ).toBeInTheDocument()
  })

  test('has no such line when nobody owes anything', async () => {
    renderPage()

    await screen.findByText(/^Boursorama · EUR · Livret A —/)
    expect(screen.queryByTestId('cushion-peer-claims')).not.toBeInTheDocument()
    expect(screen.queryByText(/avancés à des proches/)).not.toBeInTheDocument()
  })

  test('tells when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve({}) }))
    renderPage()

    expect(await screen.findByText('Impossible de charger le matelas de sécurité.')).toBeInTheDocument()
    expect(screen.queryByTestId('cushion-peer-claims')).not.toBeInTheDocument()
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
