import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalCard } from './ApprovalCard'
import type { ApprovalItem } from '../../hooks/useApprovals'

const IN_ONE_HOUR = new Date(Date.now() + 60 * 60 * 1000).toISOString()

function approval(overrides: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id: 'a1',
    toolName: 'delete_event',
    arguments: { id: 'evt-42', title: 'Dentiste' },
    summary: 'Supprimer l’événement « Dentiste » — le 15 octobre',
    status: 'pending',
    result: null,
    createdAt: '2026-10-06T08:00:00.000Z',
    expiresAt: IN_ONE_HOUR,
    ...overrides,
  }
}

function renderCard(item: ApprovalItem) {
  const onApprove = vi.fn()
  const onDeny = vi.fn()
  render(<ApprovalCard approval={item} onApprove={onApprove} onDeny={onDeny} />)
  return { onApprove, onDeny }
}

describe('ApprovalCard', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  test('shows the readable summary and both dates', () => {
    renderCard(approval())

    expect(screen.getByText('Supprimer l’événement « Dentiste » — le 15 octobre')).toBeInTheDocument()
    expect(screen.getByText(/Demandée le .+ · expire le /)).toBeInTheDocument()
    expect(screen.getByText('En attente')).toBeInTheDocument()
    expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'pending')
  })

  test('never shows the tool name, its raw arguments or the identifier', () => {
    renderCard(approval({ arguments: { id: '01M4S6K9ABCDEF', notify: false } }))

    const card = screen.getByTestId('approval-card')
    expect(card).not.toHaveTextContent('delete_event')
    expect(card).not.toHaveTextContent('01M4S6K9ABCDEF')
    expect(card).not.toHaveTextContent('notify')
  })

  test.each([[''], [null], [undefined], ['   ']])('falls back on a generic label when the summary is %j', (summary) => {
    renderCard(approval({ summary }))

    const card = screen.getByTestId('approval-card')
    expect(screen.getByText('Action en attente de validation')).toBeInTheDocument()
    expect(card).not.toHaveTextContent('delete_event')
  })

  test('Autoriser and Refuser answer with the action id', async () => {
    const user = userEvent.setup()
    const { onApprove, onDeny } = renderCard(approval())

    await user.click(screen.getByRole('button', { name: 'Autoriser' }))
    await user.click(screen.getByRole('button', { name: 'Refuser' }))

    expect(onApprove).toHaveBeenCalledWith('a1')
    expect(onDeny).toHaveBeenCalledWith('a1')
  })

  test('is « en cours » while the answer is sent, buttons locked', () => {
    renderCard(approval({ busy: 'approve' }))

    expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'running')
    expect(screen.getByText('En cours')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Autoriser' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Refuser' })).toBeDisabled()
  })

  test('is « en cours » when approved but the result is not in yet', () => {
    renderCard(approval({ status: 'approved', result: null }))

    expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'running')
  })

  test.each([
    ['approved', 'Validée', '{"deleted":true}'],
    ['denied', 'Refusée', null],
    ['failed', 'Échouée', '{"error":"Événement introuvable"}'],
    ['expired', 'Expirée', null],
  ] as const)('a %s action reads « %s » and offers no button', (status, label, result) => {
    renderCard(approval({ status, result }))

    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', status)
    expect(screen.queryByRole('button', { name: 'Autoriser' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Refuser' })).not.toBeInTheDocument()
  })

  test('a failed action says why', () => {
    renderCard(approval({ status: 'failed', result: '{"error":"Événement introuvable"}' }))

    expect(screen.getByText('Événement introuvable')).toBeInTheDocument()
  })

  test('a pending action past its expiry reads expired before the scheduler says so', () => {
    renderCard(approval({ expiresAt: '2020-01-01T00:00:00.000Z' }))

    expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'expired')
    expect(screen.queryByRole('button', { name: 'Autoriser' })).not.toBeInTheDocument()
  })

  test('shows the error of a failed answer and keeps the buttons usable', () => {
    renderCard(approval({ error: "Impossible d'envoyer ta réponse. Réessaie." }))

    expect(screen.getByRole('alert')).toHaveTextContent("Impossible d'envoyer ta réponse")
    expect(screen.getByRole('button', { name: 'Autoriser' })).toBeEnabled()
  })
})
