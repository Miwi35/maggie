import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { PeerTransferQualifier } from './PeerTransferQualifier'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CONTACTS = [
  { id: '/api/contacts/01JULIE', name: 'Julie', notes: null },
  { id: '/api/contacts/01TOM', name: 'Tom', notes: null },
]

const candidate = (shareId: string, object: string, contactName: string, remainingCents: number, fromContact: boolean) => ({
  shareId,
  peerDebtId: `D-${shareId}`,
  object,
  contactId: contactName === 'Tom' ? '01TOM' : '01JULIE',
  contactName,
  amountCents: remainingCents,
  repaidCents: 0,
  remainingCents,
  allocatedCents: 0,
  availableCents: remainingCents,
  fromContact,
})

/** From Tom, +400 €: Julie's resto (listed first by the API order on purpose) and Tom's gîte. */
const FROM_TOM = {
  transactionId: '01TX',
  amountCents: 40000,
  direction: 'incoming',
  canBeAllocated: true,
  peerKind: null,
  contact: { id: '01TOM', name: 'Tom' },
  allocatedCents: 0,
  unallocatedCents: 40000,
  allocations: [],
  candidates: [
    candidate('S-RESTO', 'Resto', 'Julie', 3000, false),
    candidate('S-GITE', 'Gîte de Crozon', 'Tom', 20000, true),
  ],
  peerDebt: null,
}

const TO_TOM = {
  ...FROM_TOM,
  amountCents: -6000,
  direction: 'outgoing',
  unallocatedCents: 6000,
  candidates: [candidate('S-CINE', 'Cinéma', 'Tom', 2500, true)],
}

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 400) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()
let state: Record<string, unknown> = FROM_TOM
let refusal: string | null = null

const api = (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.startsWith('/api/contacts?')) return answer(true, { member: CONTACTS })
  if (url === '/api/finance/transactions/01TX/allocation') {
    if (method === 'GET') return answer(true, state)
    if (refusal) return answer(false, { error: refusal })
    const body = JSON.parse(init?.body as string) as {
      kind: string
      allocations?: { shareId: string; amountCents: number }[]
    }
    const allocations = (body.allocations ?? []).map((a) => ({ ...a, peerDebtId: `D-${a.shareId}` }))
    const allocated = allocations.reduce((sum, a) => sum + a.amountCents, 0)
    state = {
      ...state,
      peerKind: body.kind,
      allocations,
      allocatedCents: allocated,
      unallocatedCents: Math.abs(state.amountCents as number) - allocated,
    }

    return answer(true, state)
  }

  return answer(false, {}, 404)
}

const puts = () =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === 'PUT')
    .map(([url, init]) => ({ url: url as string, body: JSON.parse((init as RequestInit).body as string) }))

const onSaved = vi.fn()

const renderQualifier = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider}>
        <PeerTransferQualifier transactionId="/api/transactions/01TX" label="VIR. DE TOM MARTIN" onSaved={onSaved} />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

/** Text with every kind of space made plain: fr-FR money carries no-break ones. */
const plain = (el: HTMLElement) => (el.textContent ?? '').replace(/\s/gu, ' ')

describe('PeerTransferQualifier', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    state = FROM_TOM
    refusal = null
    onSaved.mockReset()
    fetchMock.mockImplementation(api)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('a received transfer offers « Une recette », « Un remboursement » and « À rembourser », none chosen yet', async () => {
    renderQualifier()

    expect(await screen.findByText('Ce virement est')).toBeInTheDocument()
    for (const name of ['Une recette', 'Un remboursement', 'À rembourser']) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'false')
    }
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).not.toBeInTheDocument()
  })

  test('a sent transfer offers « Rembourse une dette », « Nouvelle créance » and « Autre »', async () => {
    state = TO_TOM
    renderQualifier()

    for (const name of ['Rembourse une dette', 'Nouvelle créance', 'Autre']) {
      expect(await screen.findByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.queryByRole('button', { name: 'Une recette' })).not.toBeInTheDocument()
  })

  test('an internal transfer cannot be allocated', async () => {
    state = { ...FROM_TOM, canBeAllocated: false }
    renderQualifier()

    expect(await screen.findByText(/Un virement interne ou un rejet/)).toBeInTheDocument()
    expect(screen.queryByText('Ce virement est')).not.toBeInTheDocument()
  })

  test('« Une recette » is saved with its kind alone, and the caller hears it', async () => {
    const user = userEvent.setup()
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Une recette' }))
    expect(screen.getByText('Le virement compte en entier dans tes recettes.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(puts()).toEqual([{ url: '/api/finance/transactions/01TX/allocation', body: { kind: 'income' } }]))
    expect(await screen.findByText('Virement enregistré')).toBeInTheDocument()
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ peerKind: 'income' }))
  })

  test('« Un remboursement » lists the contact\'s créances first; checking one fills what it may take', async () => {
    const user = userEvent.setup()
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Un remboursement' }))
    const covers = screen.getByRole('list', { name: 'Il couvre' })
    const boxes = within(covers).getAllByRole('checkbox')
    expect(boxes.map((b) => b.closest('label')?.textContent)).toEqual(['Gîte de Crozon · Tom', 'Resto · Julie'])
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 400,00 €')
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))

    expect(screen.getByLabelText('Montant pour Gîte de Crozon (€)')).toHaveValue('200')
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 200,00 €, compté dans tes recettes')
  })

  test('the amount on a créance is adjustable, within what it may take, and the rest stays unallocated', async () => {
    const user = userEvent.setup()
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Un remboursement' }))
    await user.click(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))
    await user.click(screen.getByRole('button', { name: 'Diminuer le montant pour Gîte de Crozon' }))
    expect(screen.getByLabelText('Montant pour Gîte de Crozon (€)')).toHaveValue('199')
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 201,00 €')

    const amount = screen.getByLabelText('Montant pour Gîte de Crozon (€)')
    await user.clear(amount)
    await user.type(amount, '250')
    expect(plain(screen.getByText(/Entre 0,01 € et/))).toContain('Entre 0,01 € et 200,00 €')
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('a second créance takes only what is left of the transfer', async () => {
    const user = userEvent.setup()
    state = { ...FROM_TOM, amountCents: 21000, unallocatedCents: 21000 }
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Un remboursement' }))
    await user.click(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))
    await user.click(screen.getByRole('checkbox', { name: 'Resto · Julie' }))

    expect(screen.getByLabelText('Montant pour Resto (€)')).toHaveValue('10')
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 0,00 €')
  })

  test('the créances covered are saved with one PUT, and the answer redraws the form', async () => {
    const user = userEvent.setup()
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Un remboursement' }))
    await user.click(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(puts()).toEqual([
        {
          url: '/api/finance/transactions/01TX/allocation',
          body: { kind: 'repayment', allocations: [{ shareId: 'S-GITE', amountCents: 20000 }] },
        },
      ]),
    )
    expect(await screen.findByText('Virement enregistré')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Un remboursement' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' })).toBeChecked()
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 200,00 €')
  })

  test('a saved repayment opens on what it covers', async () => {
    state = {
      ...FROM_TOM,
      peerKind: 'repayment',
      allocations: [{ shareId: 'S-GITE', peerDebtId: 'D-S-GITE', amountCents: 15000 }],
      allocatedCents: 15000,
      unallocatedCents: 25000,
    }
    renderQualifier()

    expect(await screen.findByLabelText('Montant pour Gîte de Crozon (€)')).toHaveValue('150')
    expect(screen.getByRole('checkbox', { name: 'Resto · Julie' })).not.toBeChecked()
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 250,00 €')
  })

  test('« À rembourser » proposes the transfer\'s contact and half of it, sent as a new_debt', async () => {
    const user = userEvent.setup()
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'À rembourser' }))
    expect(screen.getByTestId('peer-person')).toHaveTextContent('Tom')
    expect(screen.getByLabelText('Montant à rendre (€)')).toHaveValue('200')
    expect(screen.getByLabelText('Objet')).toHaveValue('VIR. DE TOM MARTIN')
    expect(plain(screen.getByTestId('peer-summary'))).toContain('Crée une dette de 200,00 € envers Tom, dans « Tu dois »')

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(puts()).toEqual([
        {
          url: '/api/finance/transactions/01TX/allocation',
          body: {
            kind: 'new_debt',
            debt: { contactId: '01TOM', amountCents: 20000, object: 'VIR. DE TOM MARTIN', notes: null },
          },
        },
      ]),
    )
  })

  test('« Nouvelle créance » on a sent transfer can go to another person', async () => {
    const user = userEvent.setup()
    state = TO_TOM
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Nouvelle créance' }))
    await user.click(screen.getByRole('button', { name: 'Changer de personne' }))
    const dialog = await screen.findByRole('dialog', { name: 'Qui te doit ?' })
    await user.click(await within(dialog).findByText('Julie'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Qui te doit ?' })).not.toBeInTheDocument())
    expect(screen.getByTestId('peer-person')).toHaveTextContent('Julie')
    expect(plain(screen.getByTestId('peer-summary'))).toContain('Julie te doit 60,00 €')

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(puts()).toHaveLength(1))
    expect(puts()[0].body).toEqual({
      kind: 'new_claim',
      claim: { contactId: '01JULIE', object: 'VIR. DE TOM MARTIN', notes: null },
    })
  })

  test('« Rembourse une dette » covers the dettes owed to the person', async () => {
    const user = userEvent.setup()
    state = TO_TOM
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Rembourse une dette' }))
    await user.click(screen.getByRole('checkbox', { name: 'Cinéma · Tom' }))
    expect(plain(screen.getByTestId('peer-unallocated'))).toContain('Non affecté : 35,00 €, compté dans tes dépenses')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(puts()[0]?.body).toEqual({ kind: 'repayment', allocations: [{ shareId: 'S-CINE', amountCents: 2500 }] }),
    )
  })

  test('a refusal shows the API\'s message and calls nobody back', async () => {
    const user = userEvent.setup()
    refusal = 'Ce virement dépasse ce qui reste à Tom.'
    renderQualifier()

    await user.click(await screen.findByRole('button', { name: 'Un remboursement' }))
    await user.click(screen.getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText(refusal)).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
  })
})
