import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { TransactionDebtSection } from './TransactionDebtSection'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CONTACTS = [
  { id: '/api/contacts/01JULIE', name: 'Julie', notes: null },
  { id: '/api/contacts/01TOM', name: 'Tom', notes: null },
]

const NO_DEBT = {
  transactionId: '01TX',
  amountCents: 56000,
  canBeDebt: true,
  suggestedContactId: '01TOM',
  peerDebt: null,
}

const debtTo = (contactId: string, contactName: string, amountCents: number) => ({
  id: '01DEBT',
  direction: 'i_owe',
  object: 'Remboursement du gîte',
  notes: 'À rendre en octobre',
  originTransactionId: '01TX',
  amountCents,
  repaidCents: 0,
  remainingCents: amountCents,
  status: 'open',
  createdAt: '2026-10-14T10:00:00+00:00',
  shares: [{ id: 'S1', contactId, contactName, amountCents, repaidCents: 0, remainingCents: amountCents }],
})

const DECLARED = { ...NO_DEBT, peerDebt: debtTo('01JULIE', 'Julie', 30000) }

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 400) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()
let state: unknown = NO_DEBT
let refusal: string | null = null

const api = (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.startsWith('/api/contacts?')) return answer(true, { member: CONTACTS })
  if (url === '/api/finance/transactions/01TX/debt') {
    if (method === 'GET') return answer(true, state)
    if (refusal) return answer(false, { error: refusal })
    if (method === 'DELETE') {
      state = NO_DEBT
      return answer(true, state)
    }
    const body = JSON.parse(init?.body as string) as { contactId: string; amountCents: number }
    state = {
      ...NO_DEBT,
      peerDebt: debtTo(body.contactId, CONTACTS.find((c) => c.id.endsWith(body.contactId))!.name, body.amountCents),
    }

    return answer(true, state, 201)
  }

  return answer(false, {}, 404)
}

const writes = (method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') === method)
    .map(([url, init]) => ({
      url: url as string,
      body: (init as RequestInit).body ? JSON.parse((init as RequestInit).body as string) : undefined,
    }))

const renderSection = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider}>
        <TransactionDebtSection transactionId="/api/transactions/01TX" label="VIR. DE TOM MARTIN" />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

/** Text with every kind of space made plain: fr-FR money carries no-break ones. */
const plain = (el: HTMLElement) => (el.textContent ?? '').replace(/\s/gu, ' ')

describe('TransactionDebtSection', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    state = NO_DEBT
    refusal = null
    fetchMock.mockImplementation(api)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('an internal transfer or a rejection cannot be given back', async () => {
    state = { ...NO_DEBT, canBeDebt: false, suggestedContactId: null }
    renderSection()

    expect(await screen.findByText('Seul un virement reçu peut être à rembourser.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'À rembourser' })).not.toBeInTheDocument()
  })

  test('a received transfer is an income by default', async () => {
    renderSection()

    const income = await screen.findByRole('button', { name: 'Une recette' })
    expect(screen.getByText('Ce virement est')).toBeInTheDocument()
    expect(income).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'À rembourser' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByLabelText('Montant à rendre (€)')).not.toBeInTheDocument()
  })

  test('choosing « À rembourser » proposes the person of the label and half of the transfer', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'À rembourser' }))

    await waitFor(() => expect(screen.getByTestId('debt-person')).toHaveTextContent('Tom'))
    expect(screen.getByLabelText('Montant à rendre (€)')).toHaveValue('280')
    expect(screen.getByLabelText('Objet')).toHaveValue('VIR. DE TOM MARTIN')
    expect(screen.getByRole('button', { name: 'Changer de personne' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voir tout ce que tu dois' })).toHaveAttribute('href', '/contacts')
  })

  test('the amount to give back is saved with one POST, and the debt is described', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'À rembourser' }))
    await waitFor(() => expect(screen.getByTestId('debt-person')).toHaveTextContent('Tom'))
    const amount = screen.getByLabelText('Montant à rendre (€)')
    await user.clear(amount)
    await user.type(amount, '280')
    expect(plain(screen.getByTestId('debt-summary'))).toContain('Crée une dette de 280,00 € envers Tom, dans « Tu dois »')
    expect(screen.getByText(/restent une recette/)).toBeInTheDocument()

    expect(screen.getByRole('toolbar', { name: 'Barre de mise en forme' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Notes'), 'À rendre en octobre')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(writes('POST')).toEqual([
        {
          url: '/api/finance/transactions/01TX/debt',
          body: { contactId: '01TOM', amountCents: 28000, object: 'VIR. DE TOM MARTIN', notes: 'À rendre en octobre' },
        },
      ]),
    )
    expect(await screen.findByText('Dette enregistrée')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'À rembourser' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('the steppers move the amount by one euro, within one cent and the transfer', async () => {
    const user = userEvent.setup()
    state = DECLARED
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Augmenter le montant à rendre' }))
    expect(screen.getByLabelText('Montant à rendre (€)')).toHaveValue('301')

    const amount = screen.getByLabelText('Montant à rendre (€)')
    await user.clear(amount)
    await user.type(amount, '560')
    expect(screen.getByRole('button', { name: 'Augmenter le montant à rendre' })).toBeDisabled()

    await user.clear(amount)
    await user.type(amount, '561')
    expect(screen.getByText(/Entre 0,01 € et 560,00/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('the person can be changed from the contacts', async () => {
    const user = userEvent.setup()
    state = DECLARED
    renderSection()

    expect(await screen.findByTestId('debt-person')).toHaveTextContent('Julie')
    await user.click(screen.getByRole('button', { name: 'Changer de personne' }))
    const dialog = await screen.findByRole('dialog', { name: 'À qui le rendre ?' })
    await user.click(await within(dialog).findByText('Tom'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'À qui le rendre ?' })).not.toBeInTheDocument())

    expect(screen.getByTestId('debt-person')).toHaveTextContent('Tom')
    expect(plain(screen.getByTestId('debt-summary'))).toContain('envers Tom')

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(writes('POST')).toHaveLength(1))
    expect(writes('POST')[0].body).toEqual({
      contactId: '01TOM',
      amountCents: 30000,
      object: 'Remboursement du gîte',
      notes: 'À rendre en octobre',
    })
  })

  test('without a suggestion the person has to be chosen before saving', async () => {
    const user = userEvent.setup()
    state = { ...NO_DEBT, suggestedContactId: null }
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'À rembourser' }))

    expect(screen.getByRole('button', { name: 'Choisir la personne' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('a refusal shows the API\'s message', async () => {
    const user = userEvent.setup()
    state = DECLARED
    refusal = 'Le montant ne peut pas dépasser celui du virement.'
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText(refusal)).toBeInTheDocument()
  })

  test('going back to « Une recette » on a declared debt asks, then removes it', async () => {
    const user = userEvent.setup()
    state = DECLARED
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Une recette' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Ce virement redevient une recette entière/)).toBeInTheDocument()
    expect(writes('DELETE')).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Redevenir une recette' }))

    await waitFor(() => expect(writes('DELETE')).toEqual([{ url: '/api/finance/transactions/01TX/debt', body: undefined }]))
    expect(await screen.findByText('Ce virement est de nouveau une recette')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Une recette' })).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.queryByLabelText('Montant à rendre (€)')).not.toBeInTheDocument()
  })

  test('going back to « Une recette » without a debt needs no confirmation', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'À rembourser' }))
    await user.click(screen.getByRole('button', { name: 'Une recette' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Montant à rendre (€)')).not.toBeInTheDocument()
    expect(writes('DELETE')).toEqual([])
  })
})
