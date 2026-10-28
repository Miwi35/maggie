import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { PeerDebtsPage } from './PeerDebtsPage'

const share = (id: string, contactId: string, contactName: string, amountCents: number, repaidCents = 0) => ({
  id,
  contactId,
  contactName,
  amountCents,
  repaidCents,
  remainingCents: amountCents - repaidCents,
})

const debt = (id: string, direction: 'owed_to_me' | 'i_owe', object: string, shares: ReturnType<typeof share>[], notes: string | null = null) => ({
  id,
  direction,
  object,
  notes,
  originTransactionId: null,
  amountCents: shares.reduce((sum, s) => sum + s.amountCents, 0),
  repaidCents: 0,
  remainingCents: shares.reduce((sum, s) => sum + s.remainingCents, 0),
  status: 'open',
  createdAt: '2026-08-14T10:00:00+00:00',
  shares,
  history: [],
})

/** Tom owes 200 € (the gîte, with Julie) and I owe him 280 € (the petrol): 80 € net, I owe. */
const DEBTS = [
  debt('d1', 'owed_to_me', 'Gîte de Crozon', [share('s1', 'JULIE', 'Julie', 20000), share('s2', 'TOM', 'Tom', 20000)], 'Pris sur ma carte'),
  debt('d2', 'i_owe', 'Essence du road trip', [share('s3', 'TOM', 'Tom', 28000)]),
]
const SUMMARY = {
  owedToMeCents: 40000,
  iOweCents: 28000,
  byContact: [
    { contactId: 'TOM', name: 'Tom', netCents: -8000, compensates: true },
    { contactId: 'JULIE', name: 'Julie', netCents: 20000, compensates: false },
  ],
}

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 500) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()}>
        <PeerDebtsPage />
      </AdminContext>
    </MemoryRouter>,
  )

const flat = (el: HTMLElement) => el.textContent?.replace(/\s/gu, ' ') ?? ''

describe('PeerDebtsPage — « Créances et dettes »', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/finance/peer-debts/summary') return answer(true, SUMMARY)
      if (url === '/api/finance/peer-debts?status=open') return answer(true, { peerDebts: DEBTS })

      return answer(false, {}, 404)
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the two tiles: what is owed to the owner and what the owner owes', async () => {
    renderPage()

    const owed = await screen.findByTestId('tile-owed-to-me')
    expect(owed).toHaveTextContent('On te doit')
    expect(flat(owed)).toContain('400,00 €')
    expect(owed).not.toHaveTextContent('avancés sur ton matelas')
    const due = screen.getByTestId('tile-i-owe')
    expect(due).toHaveTextContent('Tu dois')
    expect(flat(due)).toContain('280,00 €')
    expect(due).not.toHaveTextContent('hors revenus et épargne')
  })

  test('« Par personne » gives each person their net, and Tom the compensation', async () => {
    const user = userEvent.setup()
    renderPage()

    const people = await screen.findAllByTestId('peer-debt-person')
    expect(people).toHaveLength(2)
    expect(people[0]).toHaveTextContent('Tom')
    expect(flat(people[0])).toContain('tu lui dois 80,00 €')
    expect(flat(people[0])).toContain('Les deux se compensent : un seul virement de 80,00 € à Tom solde tout')
    expect(flat(people[1])).toContain('te doit 200,00 €')
    expect(people[1]).not.toHaveTextContent('se compensent')

    await user.click(within(people[0]).getByRole('button', { name: 'Tom' }))
    const lines = within(people[0]).getByRole('list', { name: 'Détail de Tom' })
    expect(flat(lines)).toContain('Gîte de Crozon')
    expect(flat(lines)).toContain('te doit 200,00 €')
    expect(flat(lines)).toContain('Essence du road trip')
    expect(flat(lines)).toContain('tu lui dois 280,00 €')
    expect(within(lines).getByRole('link', { name: 'Fiche et comptes de Tom' })).toHaveAttribute(
      'href',
      `/contacts/${encodeURIComponent('/api/contacts/TOM')}`,
    )
  })

  test('« Par objet » gives the amount, who and the note of each object', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Par objet' }))

    const objects = screen.getAllByTestId('peer-debt-object')
    expect(objects).toHaveLength(2)
    expect(objects[0]).toHaveTextContent('Gîte de Crozon')
    expect(flat(objects[0])).toContain('on te doit 400,00 €')
    expect(flat(objects[0])).toContain('Julie 200,00 € · Tom 200,00 €')
    expect(objects[0]).toHaveTextContent('Pris sur ma carte')
    expect(flat(objects[1])).toContain('tu dois 280,00 €')
  })

  test('is a section with its heading and no tabs: « Par objet » is a switch, not a tab', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Créances et dettes' })).toBeInTheDocument()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.getByRole('button', { name: 'Par personne' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('« Personne » opens the new contact form', async () => {
    renderPage()

    expect(await screen.findByRole('link', { name: /Personne/ })).toHaveAttribute('href', '/contacts/create')
  })

  test('says so when nobody owes anything', async () => {
    fetchMock.mockImplementation((url: string) =>
      url === '/api/finance/peer-debts/summary'
        ? answer(true, { owedToMeCents: 0, iOweCents: 0, byContact: [] })
        : answer(true, { peerDebts: [] }),
    )
    renderPage()

    expect(await screen.findByText('Rien en cours')).toBeInTheDocument()
    expect(screen.queryByText(/partage une dépense ou affecte un virement/)).not.toBeInTheDocument()
  })

  test('shows the message and lets the owner try again when a read fails', async () => {
    const user = userEvent.setup()
    fetchMock.mockImplementationOnce(() => answer(false, { error: 'Boum' }))
    renderPage()

    expect(await screen.findByText('Boum')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('tile-owed-to-me')).toBeInTheDocument()
  })
})
