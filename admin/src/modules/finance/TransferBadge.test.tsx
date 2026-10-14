import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TransferBadge, TransferCounterpart, transferLabel } from './TransferBadge'

const LEG = {
  id: '01DEBIT',
  label: 'Prélèvement EDF',
  amountCents: -8000,
  currency: 'EUR',
  bookedAt: '2026-09-05',
  accountId: '01CHK',
  accountName: 'Courant',
}

const stubTransfer = (body: unknown) =>
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => body }))

describe('TransferBadge', () => {
  test('an internal transfer reads « Virement interne », whatever its sign', () => {
    render(<TransferBadge transferKind="internal" amountCents={-300000} />)

    expect(screen.getByText('Virement interne')).toBeInTheDocument()
  })

  test('a rejected debit reads « Rejeté », the credit that gave it back « Rejet »', () => {
    const { unmount } = render(<TransferBadge transferKind="rejected" amountCents={-8000} />)
    expect(screen.getByText('Rejeté')).toBeInTheDocument()
    unmount()

    render(<TransferBadge transferKind="rejected" amountCents={8000} />)
    expect(screen.getByText('Rejet')).toBeInTheDocument()
    expect(screen.queryByText('Rejeté')).not.toBeInTheDocument()
  })

  test('an advance, its repayment and a top-up are transfers too, each named (MAG-276)', () => {
    const { unmount } = render(<TransferBadge transferKind="advance" amountCents={-150000} />)
    expect(screen.getByText('Avance d’épargne')).toBeInTheDocument()
    unmount()

    const second = render(<TransferBadge transferKind="repayment" amountCents={-50000} />)
    expect(screen.getByText('Remboursement d’avance')).toBeInTheDocument()
    second.unmount()

    render(<TransferBadge transferKind="funding" amountCents={-20000} />)
    expect(screen.getByText('Approvisionnement')).toBeInTheDocument()
  })

  test('an ordinary line carries no badge', () => {
    const { container } = render(<TransferBadge transferKind="none" amountCents={-8000} />)

    expect(container).toBeEmptyDOMElement()
  })
})

describe('TransferCounterpart', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('the credit of a rejection names the payment it gives back', async () => {
    stubTransfer({ transferKind: 'rejected', transferSource: 'auto', counterpart: LEG })

    render(<TransferCounterpart transactionId="01CREDIT" amountCents={8000} />)

    expect(await screen.findByText(/Rejet de :/)).toBeInTheDocument()
    expect(screen.getByText(/Courant · 05\/09\/2026 · Prélèvement EDF/)).toBeInTheDocument()
  })

  test('the rejected debit names the credit that gave it back', async () => {
    stubTransfer({
      transferKind: 'rejected',
      transferSource: 'auto',
      counterpart: { ...LEG, id: '01CREDIT', label: 'Rejet prélèvement EDF', amountCents: 8000, bookedAt: '2026-09-08' },
    })

    render(<TransferCounterpart transactionId="01DEBIT" amountCents={-8000} />)

    expect(await screen.findByText(/Rendu par :/)).toBeInTheDocument()
    expect(screen.getByText(/Courant · 08\/09\/2026 · Rejet prélèvement EDF/)).toBeInTheDocument()
  })

  test('a rejection marked alone says it has no counterpart', async () => {
    stubTransfer({ transferKind: 'rejected', transferSource: 'manual', counterpart: null })

    render(<TransferCounterpart transactionId="01CREDIT" amountCents={8000} />)

    expect(await screen.findByText('Sans contrepartie')).toBeInTheDocument()
  })
})

describe('transferLabel', () => {
  test('names a marked line, and nothing for an ordinary one', () => {
    expect(transferLabel('internal', -100)).toBe('Virement interne')
    expect(transferLabel('rejected', -100)).toBe('Rejeté')
    expect(transferLabel('rejected', 100)).toBe('Rejet')
    expect(transferLabel('none', -100)).toBeNull()
    expect(transferLabel(undefined, -100)).toBeNull()
  })
})
