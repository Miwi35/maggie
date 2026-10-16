import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { ToSortPage } from './ToSortPage'

const CATEGORIES = [
  { id: '/api/categories/COURSES', name: 'Courses', path: 'Dépenses courantes › Alimentation › Courses', parent: null },
  { id: '/api/categories/LOISIRS', name: 'Loisirs', path: 'Loisirs', parent: null },
]

const CARREFOUR = {
  id: 'T1',
  label: 'CARREFOUR MARKET 1234',
  counterpartyName: null,
  amountCents: -4599,
  currency: 'EUR',
  bookedAt: '2026-10-12',
  accountId: 'A1',
  accountName: 'Courant',
  judgeable: true,
  retrospect: 'unrated',
  suggestion: {
    categoryId: 'COURSES',
    categoryPath: 'Dépenses courantes › Alimentation › Courses',
    source: 'rule',
    reason: 'La règle « contient CARREFOUR » classe ce marchand en Courses.',
  },
  contact: null,
  asksForContact: false,
  counterpartyLabel: 'CARREFOUR MARKET 1234',
}

const MYSTERY = {
  id: 'T2',
  label: 'PAIEMENT PAR CARTE X',
  counterpartyName: null,
  amountCents: -1200,
  currency: 'EUR',
  bookedAt: '2026-10-10',
  accountId: 'A1',
  accountName: 'Courant',
  suggestion: null,
  judgeable: true,
  retrospect: 'unrated',
  contact: null,
  asksForContact: false,
  counterpartyLabel: 'PAIEMENT PAR CARTE X',
}

const SALARY = {
  ...MYSTERY,
  id: 'T3',
  label: 'VIREMENT SALAIRE',
  amountCents: 250000,
  judgeable: false,
}

const FROM_TOM = {
  id: 'T3',
  label: 'VIR. DE TOM MARTIN 12/10',
  counterpartyName: null,
  amountCents: 40000,
  currency: 'EUR',
  bookedAt: '2026-10-12',
  accountId: 'A1',
  accountName: 'Courant',
  suggestion: null,
  judgeable: false,
  retrospect: 'unrated',
  contact: null,
  asksForContact: true,
  counterpartyLabel: 'VIR. DE TOM MARTIN',
}

const CONTACTS = [{ id: '01ANNA', name: 'Anna', notes: null }]

const pileOf = (...transactions: unknown[]) => ({ total: transactions.length, totalCents: 5799, transactions })

const answer = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const SORTED = { success: true, rule: null, ruleCreated: false, alsoSorted: 0, remaining: 1 }

const fetchMock = vi.fn()
const dataProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
})

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider}>
        <ToSortPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

const writes = (method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([url, init]) => ({
      url: url as string,
      body: JSON.parse((init as RequestInit).body as string),
      contentType: ((init as RequestInit).headers as Record<string, string>)['Content-Type'],
    }))

const posts = () => writes('POST').map(({ url, body }) => ({ url, body }))

describe('ToSortPage', () => {
  beforeEach(() => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? answer(true, SORTED) : answer(true, pileOf(CARREFOUR, MYSTERY)),
    )
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the oldest line as a card, with its suggestion, its reason and the counter', async () => {
    renderPage()

    const card = await screen.findByRole('region', { name: 'Transaction à qualifier' })
    expect(within(card).getByText('CARREFOUR MARKET 1234')).toBeInTheDocument()
    expect(within(card).getByText(/45,99/)).toBeInTheDocument()
    expect(within(card).getByText(/12\/10\/2026 · Courant/)).toBeInTheDocument()
    expect(within(card).getByText('→ Dépenses courantes › Alimentation › Courses')).toBeInTheDocument()
    expect(within(card).getByText(/La règle « contient CARREFOUR »/)).toBeInTheDocument()
    expect(screen.getByText(/2 à qualifier · 57,99/)).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: "File d'attente" })).getAllByRole('button')).toHaveLength(2)
  })

  test('a line without suggestion offers no « Valider »', async () => {
    fetchMock.mockImplementation(() => answer(true, pileOf(MYSTERY)))
    renderPage()

    expect(await screen.findByText('Aucune suggestion pour cette ligne.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
  })

  test('picking a line of the pile brings its card up', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /PAIEMENT PAR CARTE X/ }))

    const card = screen.getByRole('region', { name: 'Transaction à qualifier' })
    expect(within(card).getByText('PAIEMENT PAR CARTE X')).toBeInTheDocument()
  })

  test('« Valider » files the line under the suggested category, then the pile is read again', async () => {
    renderPage()

    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? answer(true, SORTED) : answer(true, pileOf(MYSTERY)),
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Valider' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0]).toEqual({
      url: '/api/finance/transactions/T1/sort',
      body: { categoryId: 'COURSES', alwaysForMerchant: false },
    })
    const card = await screen.findByRole('region', { name: 'Transaction à qualifier' })
    await waitFor(() => expect(within(card).getByText('PAIEMENT PAR CARTE X')).toBeInTheDocument())
    expect(screen.getByText(/1 à qualifier/)).toBeInTheDocument()
  })

  test('« Autre catégorie » files the line under the chosen category', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Autre catégorie' }))
    await userEvent.click(screen.getByLabelText('Catégorie'))
    await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
    await userEvent.click(screen.getByRole('button', { name: 'Qualifier' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0].body).toEqual({ categoryId: 'LOISIRS', alwaysForMerchant: false })
    expect(writes('PATCH')).toHaveLength(0)
  })

  test('a mandatory or a kind chosen with the category is written once the line is filed', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Autre catégorie' }))
    await userEvent.click(screen.getByLabelText('Catégorie'))
    await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
    await userEvent.click(screen.getByRole('switch', { name: 'Obligatoire' }))
    await userEvent.click(screen.getByRole('button', { name: 'Qualifier' }))

    await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
    expect(posts()[0].body).toEqual({ categoryId: 'LOISIRS', alwaysForMerchant: false })
    expect(writes('PATCH')[0]).toEqual({
      url: '/api/transactions/T1',
      body: { qualification: { mandatory: true, kind: 'none' } },
      contentType: 'application/merge-patch+json',
    })
  })

  test('the category cannot be confirmed before it is chosen', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Autre catégorie' }))

    expect(screen.getByRole('button', { name: 'Qualifier' })).toBeDisabled()
  })

  test('« Toujours pour ce marchand » asks for a rule and says what it filed', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST'
        ? answer(true, {
            ...SORTED,
            rule: { id: 'R1', labelPattern: 'CARREFOUR MARKET' },
            ruleCreated: true,
            alsoSorted: 2,
          })
        : answer(true, pileOf(CARREFOUR, MYSTERY)),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('switch', { name: 'Toujours pour ce marchand' }))
    await userEvent.click(screen.getByRole('button', { name: 'Valider' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0].body).toEqual({ categoryId: 'COURSES', alwaysForMerchant: true })
    expect(await screen.findByText('Règle créée · 2 autres lignes classées')).toBeInTheDocument()
  })

  test('« Virement interne » marks the line as a transfer between the owner\'s accounts', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Virement interne' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0]).toEqual({ url: '/api/finance/transactions/T1/sort', body: { internalTransfer: {} } })
  })

  test('an empty pile says there is nothing to qualify', async () => {
    fetchMock.mockImplementation(() => answer(true, pileOf()))
    renderPage()

    expect(await screen.findByText('Rien à qualifier')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
  })

  test('a failed read shows the server message and can be retried', async () => {
    fetchMock.mockImplementationOnce(() => answer(false, { error: 'Service indisponible' }))
    renderPage()

    expect(await screen.findByText('Service indisponible')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    const card = await screen.findByRole('region', { name: 'Transaction à qualifier' })
    expect(within(card).getByText('CARREFOUR MARKET 1234')).toBeInTheDocument()
  })

  test('a refused answer shows its message and keeps the card in place', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST'
        ? answer(false, { error: 'This transaction is not waiting to be sorted.' })
        : answer(true, pileOf(CARREFOUR, MYSTERY)),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Valider' }))

    expect(await screen.findByText('This transaction is not waiting to be sorted.')).toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Transaction à qualifier' })
    expect(within(card).getByText('CARREFOUR MARKET 1234')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Valider' })).toBeEnabled()
  })

  test('« Superflue » gives the spend its satisfaction, and pressing it again takes it back', async () => {
    renderPage()

    const first = await screen.findByRole('button', { name: 'Superflue' })
    fetchMock.mockImplementation(() => answer(true, pileOf({ ...CARREFOUR, retrospect: 'avoidable' }, MYSTERY)))
    await userEvent.click(first)

    await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
    expect(writes('PATCH')[0]).toEqual({
      url: '/api/transactions/T1',
      body: { retrospect: 'avoidable' },
      contentType: 'application/merge-patch+json',
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Superflue', pressed: true }))
    await waitFor(() => expect(writes('PATCH')).toHaveLength(2))
    expect(writes('PATCH')[1].body).toEqual({ retrospect: 'unrated' })
  })

  test('« À conserver » says the spend was worth it', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'À conserver' }))

    await waitFor(() => expect(writes('PATCH')[0]?.body).toEqual({ retrospect: 'keep' }))
  })

  test('an income offers no satisfaction at all', async () => {
    fetchMock.mockImplementation(() => answer(true, pileOf(SALARY)))
    renderPage()

    await screen.findByRole('region', { name: 'Transaction à qualifier' })
    expect(screen.queryByRole('button', { name: 'Superflue' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'À conserver' })).not.toBeInTheDocument()
  })

  test('a refused satisfaction shows its message', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'PATCH'
        ? answer(false, { detail: 'Only a spend can be judged.' })
        : answer(true, pileOf(CARREFOUR)),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Superflue' }))

    expect(await screen.findByText('Only a spend can be judged.')).toBeInTheDocument()
  })

  test('« Voir la fiche » links to the transaction sheet', async () => {
    renderPage()

    const link = await screen.findByRole('link', { name: 'Voir la fiche' })

    expect(link).toHaveAttribute('href', expect.stringContaining('/transactions/'))
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('/api/transactions/T1')
  })

  test('« Créer un filtre » opens the rule brick filled from the line, applying to the existing lines', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Créer un filtre' }))

    const dialog = await screen.findByRole('dialog', { name: 'Créer un filtre' })
    expect(within(dialog).getByLabelText('…ce texte')).toHaveValue('CARREFOUR MARKET')
    expect(within(dialog).getByRole('checkbox', { name: 'Appliquer aux transactions existantes' })).toBeChecked()
    expect(within(dialog).getByRole('button', { name: 'Créer le filtre' })).toBeInTheDocument()
  })

  describe('« Associer à un contact ? »', () => {
    /** The pile, the contacts, and the two writes of an association; `linkRefusal` makes the link fail. */
    const withContacts = (linkRefusal: string | null = null) => {
      let linkedTo: string | null = null
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (url.startsWith('/api/contacts?')) return answer(true, { member: CONTACTS })
        if (url === '/api/contacts' && init?.method === 'POST') {
          return answer(true, { '@id': '/api/contacts/01TOM', id: '01TOM', name: 'Tom', notes: null })
        }
        if (url === '/api/contact_counterparties') {
          if (linkRefusal) return answer(false, { detail: linkRefusal })
          linkedTo = JSON.parse(init?.body as string).contact === '/api/contacts/01TOM' ? 'Tom' : 'Anna'

          return answer(true, {})
        }

        return answer(
          true,
          pileOf(linkedTo ? { ...FROM_TOM, asksForContact: false, contact: { id: '01X', name: linkedTo } } : FROM_TOM),
        )
      })
    }

    test('is only asked on a transfer nobody claims', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Transaction à qualifier' })
      expect(screen.queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('a line already attached shows its contact instead', async () => {
      fetchMock.mockImplementation(() =>
        answer(true, pileOf({ ...FROM_TOM, asksForContact: false, contact: { id: '01TOM', name: 'Tom' } })),
      )
      renderPage()

      const card = await screen.findByRole('region', { name: 'Transaction à qualifier' })
      expect(within(card).getByText('Tom')).toBeInTheDocument()
      expect(within(card).queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('« + Créer « Tom » » creates the contact, links the label to it, and the line shows Tom', async () => {
      withContacts()
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Associer à un contact ?' }))
      const dialog = await screen.findByRole('dialog', { name: 'Associer à un contact' })
      await screen.findByRole('button', { name: /Anna/ })
      await userEvent.type(within(dialog).getByLabelText('Rechercher un contact'), 'Tom')
      await userEvent.click(within(dialog).getByRole('button', { name: '+ Créer « Tom »' }))

      await waitFor(() =>
        expect(posts()).toEqual([
          { url: '/api/contacts', body: { name: 'Tom' } },
          { url: '/api/contact_counterparties', body: { contact: '/api/contacts/01TOM', label: 'VIR. DE TOM MARTIN' } },
        ]),
      )
      const card = screen.getByRole('region', { name: 'Transaction à qualifier' })
      expect(await within(card).findByText('Tom')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(within(card).queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('picking an existing contact links the label to them', async () => {
      withContacts()
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Associer à un contact ?' }))
      await userEvent.click(await screen.findByRole('button', { name: /Anna/ }))

      await waitFor(() =>
        expect(posts()).toEqual([
          { url: '/api/contact_counterparties', body: { contact: '/api/contacts/01ANNA', label: 'VIR. DE TOM MARTIN' } },
        ]),
      )
      const card = screen.getByRole('region', { name: 'Transaction à qualifier' })
      expect(await within(card).findByText('Anna')).toBeInTheDocument()
    })

    test('a refused link shows the API\'s message and leaves the picker open', async () => {
      withContacts('« VIR. DE TOM MARTIN » is already linked to Anna.')
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Associer à un contact ?' }))
      await userEvent.click(await screen.findByRole('button', { name: /Anna/ }))

      expect(await screen.findByText('« VIR. DE TOM MARTIN » is already linked to Anna.')).toBeInTheDocument()
      expect(screen.getByRole('dialog', { name: 'Associer à un contact' })).toBeInTheDocument()
    })
  })
})
