import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import { DetectInternalTransfersButton } from './DetectInternalTransfersButton'

const PAIR = {
  transactionId: '01OUT',
  counterpartId: '01IN',
  amountCents: -300000,
  bookedAt: '2026-09-12',
  counterpartBookedAt: '2026-09-13',
  label: 'Virement vers Courant',
  counterpartLabel: 'Virement du Livret',
}

const fetchMock = vi.fn()

const respond = (ok: boolean, body: unknown) =>
  Promise.resolve({ ok, json: async () => body } as Response)

const bodies = () =>
  fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

const renderButton = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <DetectInternalTransfersButton />
      <Notification />
    </AdminContext>,
  )

describe('DetectInternalTransfersButton', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the pairs of a rehearsal first and writes nothing', async () => {
    fetchMock.mockImplementation(() => respond(true, { success: true, matched: 1, scanned: 3, dryRun: true, pairs: [PAIR] }))

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: 'Détecter les virements internes' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/12\/09\/2026 · Virement vers Courant/)).toBeInTheDocument()
    expect(within(dialog).getByText(/13\/09\/2026 · Virement du Livret/)).toBeInTheDocument()
    expect(bodies()).toEqual([{ dryRun: true }])
  })

  test('confirming applies, says how many pairs, and closes', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      respond(true, {
        success: true,
        matched: 1,
        scanned: 3,
        dryRun: JSON.parse(init?.body as string).dryRun,
        pairs: [PAIR],
      }),
    )

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: 'Détecter les virements internes' }))
    await userEvent.click(await screen.findByRole('button', { name: /Marquer ces 1 paire/ }))

    await waitFor(() => expect(bodies()).toEqual([{ dryRun: true }, { dryRun: false }]))
    expect(await screen.findByText('1 virement(s) interne(s) marqué(s) sur 3 ligne(s) analysée(s)')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('cancelling the preview never reaches the second call', async () => {
    fetchMock.mockImplementation(() => respond(true, { success: true, matched: 1, scanned: 3, dryRun: true, pairs: [PAIR] }))

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: 'Détecter les virements internes' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Annuler' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('an empty rehearsal says there is nothing to pair and offers no confirmation', async () => {
    fetchMock.mockImplementation(() => respond(true, { success: true, matched: 0, scanned: 12, dryRun: true, pairs: [] }))

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: 'Détecter les virements internes' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Aucun virement interne à apparier \(12 ligne\(s\) analysée\(s\)\)/)).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: /Marquer ces/ })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Fermer' })).toBeInTheDocument()
  })

  test('an API failure on the rehearsal is notified and opens nothing', async () => {
    fetchMock.mockImplementation(() => respond(false, {}))

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: 'Détecter les virements internes' }))

    expect(await screen.findByText('Impossible de détecter les virements internes')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
