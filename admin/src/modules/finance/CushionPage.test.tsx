import { describe, test, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
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
  criticalMonths: 2,
  criticalCents: 400000,
  criticalDeficitCents: 0,
  cushionSharePercent: 60,
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

  test('says where the saving goes, below the threshold and past it', async () => {
    stubStatus({ ...withClaims, criticalDeficitCents: 20000, criticalCents: 400000 })
    const { unmount } = renderPage()
    expect(plain((await screen.findByTestId('cushion-share-rule')).textContent)).toBe(
      "Sous le seuil critique (2 mois de dépenses, 4 000,00 €) : toute l'épargne va au matelas.",
    )
    unmount()

    stubStatus(withClaims)
    renderPage()
    expect(plain((await screen.findByTestId('cushion-share-rule')).textContent)).toBe(
      'Seuil critique atteint : 60 % de l’épargne va au matelas, le reste aux projets.'.replace('’', "'"),
    )
  })

  test('sends the threshold and the share with the other settings', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(STATUS) })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    fireEvent.change(await screen.findByLabelText('Seuil critique (mois de dépenses)'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Part du matelas (%)'), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/finance/cushion-config', expect.anything()))
    const [, init] = fetchMock.mock.calls.find(([url]) => url === '/api/finance/cushion-config')!
    expect(JSON.parse(init.body)).toMatchObject({ criticalMonths: 3, cushionSharePercent: 100 })
  })

  test('tells when the settings are refused', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(STATUS) })
      .mockResolvedValue({ ok: false, json: () => Promise.resolve({ error: 'out of bounds' }) })
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AdminContext dataProvider={testDataProvider()}>
        <CushionPage />
        <Notification />
      </AdminContext>,
    )

    fireEvent.change(await screen.findByLabelText('Part du matelas (%)'), { target: { value: '150' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Impossible de mettre à jour le matelas')).toBeInTheDocument()
  })
})
