import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, RecordContextProvider, testDataProvider } from 'react-admin'
import { TransferPanel } from './TransferPanel'

const RECORD = { id: '/api/transactions/01OUT', label: 'Virement vers Courant', amountCents: -300000 }

const LEG = {
  id: '01IN',
  label: 'Virement du Livret',
  amountCents: 300000,
  currency: 'EUR',
  bookedAt: '2026-09-13',
  accountId: '01CHK',
  accountName: 'Courant',
}

const NONE = { transferKind: 'none', transferSource: 'auto', counterpart: null }
const PAIRED = { transferKind: 'internal', transferSource: 'auto', counterpart: LEG }

const fetchMock = vi.fn()

const respond = (status: number, body: unknown) =>
  Promise.resolve({ ok: status < 400, status, json: async () => body } as Response)

const renderPanel = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <RecordContextProvider value={RECORD}>
        <TransferPanel />
      </RecordContextProvider>
      <Notification />
    </AdminContext>,
  )

const bodiesOf = (method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([url, init]) => ({ url, body: JSON.parse((init as RequestInit).body as string) }))

describe('TransferPanel', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('a line that is a transfer shows the badge, who decided and the other leg', async () => {
    fetchMock.mockImplementation(() => respond(200, PAIRED))

    renderPanel()

    expect(await screen.findByText('Virement interne', { selector: '.MuiChip-label' })).toBeInTheDocument()
    expect(screen.getByText('Détecté automatiquement')).toBeInTheDocument()
    expect(screen.getByText(/Courant · 13\/09\/2026 · Virement du Livret/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/finance/transactions/01OUT/transfer',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer jwt' }) }),
    )
    expect(screen.getByRole('button', { name: 'Ce n’est pas un virement interne' })).toBeInTheDocument()
  })

  test('an ordinary line shows no badge and offers to mark it', async () => {
    fetchMock.mockImplementation(() => respond(200, NONE))

    renderPanel()

    expect(await screen.findByRole('button', { name: 'C’est un virement interne' })).toBeInTheDocument()
    expect(screen.queryByText('Virement interne', { selector: '.MuiChip-label' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ce n’est pas un virement interne' })).not.toBeInTheDocument()
  })

  test('taking the marking off puts the owner’s word on the line and drops the badge', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'PUT'
        ? respond(200, { success: true, transferKind: 'none', transferSource: 'manual', counterpart: null })
        : respond(200, PAIRED),
    )

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'Ce n’est pas un virement interne' }))

    expect(await screen.findByRole('button', { name: 'C’est un virement interne' })).toBeInTheDocument()
    expect(screen.queryByText('Virement interne', { selector: '.MuiChip-label' })).not.toBeInTheDocument()
    expect(bodiesOf('PUT')).toEqual([
      { url: '/api/finance/transactions/01OUT/transfer', body: { transferKind: 'none' } },
    ])
    expect(await screen.findByText('Ce n’est plus un virement interne')).toBeInTheDocument()
  })

  test('marking by hand offers the candidates and sends the one chosen', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return respond(200, { success: true, ...PAIRED, transferSource: 'manual' })
      if (url.endsWith('/transfer-candidates')) return respond(200, { candidates: [LEG] })
      return respond(200, NONE)
    })

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'C’est un virement interne' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.click(await within(dialog).findByRole('radio', { name: /Virement du Livret/ }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme virement interne' }))

    await waitFor(() =>
      expect(bodiesOf('PUT')).toEqual([
        {
          url: '/api/finance/transactions/01OUT/transfer',
          body: { transferKind: 'internal', counterpartId: '01IN' },
        },
      ]),
    )
    expect(await screen.findByText('Marqué à la main')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('with no candidate, the owner can still mark a single leg', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        return respond(200, { success: true, transferKind: 'internal', transferSource: 'manual', counterpart: null })
      }
      if (url.endsWith('/transfer-candidates')) return respond(200, { candidates: [] })
      return respond(200, NONE)
    })

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'C’est un virement interne' }))

    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/Aucune ligne d’un autre de vos comptes ne correspond/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme virement interne' }))

    await waitFor(() => expect(bodiesOf('PUT')[0].body).toEqual({ transferKind: 'internal' }))
    expect(await screen.findByText(/Sans contrepartie/)).toBeInTheDocument()
  })

  test('an API refusal is shown and the line keeps its marking', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'PUT' ? respond(400, { error: 'An internal transfer goes between two different accounts.' }) : respond(200, PAIRED),
    )

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'Ce n’est pas un virement interne' }))

    expect(
      await screen.findByText('An internal transfer goes between two different accounts.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Détecté automatiquement')).toBeInTheDocument()
  })

  test('a failing read says so instead of showing a wrong state', async () => {
    fetchMock.mockImplementation(() => respond(500, {}))

    renderPanel()

    expect(await screen.findByText('Impossible de lire le virement interne')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'C’est un virement interne' })).not.toBeInTheDocument()
  })
})
