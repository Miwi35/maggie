import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { TransactionShareSection } from './TransactionShareSection'
import { parseEuros, splitEqually } from './peerDebtsApi'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CONTACTS = [
  { id: '/api/contacts/01JULIE', name: 'Julie', notes: null },
  { id: '/api/contacts/01TOM', name: 'Tom', notes: null },
  { id: '/api/contacts/01INES', name: 'Inès', notes: null },
]

const NOT_SHARED = { transactionId: '01TX', amountCents: -80000, myShareCents: null, canBeShared: true, peerDebt: null }

const share = (contactId: string, contactName: string, amountCents: number, repaidCents = 0) => ({
  id: `S-${contactId}`,
  contactId,
  contactName,
  amountCents,
  repaidCents,
  remainingCents: amountCents - repaidCents,
})

const SHARED = {
  ...NOT_SHARED,
  myShareCents: 20000,
  peerDebt: {
    id: '01DEBT',
    direction: 'owed_to_me',
    object: 'Gîte Ardèche',
    notes: 'Clés chez Tom',
    originTransactionId: '01TX',
    amountCents: 60000,
    repaidCents: 0,
    remainingCents: 60000,
    status: 'open',
    createdAt: '2026-08-14T10:00:00+00:00',
    shares: [share('01JULIE', 'Julie', 20000), share('01TOM', 'Tom', 20000), share('01INES', 'Inès', 20000)],
  },
}

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 400) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()
let state: unknown = NOT_SHARED
let refusal: string | null = null

const api = (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.startsWith('/api/contacts?')) return answer(true, { member: CONTACTS })
  if (url === '/api/finance/transactions/01TX/my-share') {
    if (method === 'GET') return answer(true, state)
    if (refusal) return answer(false, { error: refusal })
    if (method === 'DELETE') {
      state = NOT_SHARED
      return answer(true, state)
    }
    const body = JSON.parse(init?.body as string) as { myShareCents: number; shares: { contactId: string }[] }
    const owed = 80000 - body.myShareCents
    const parts = splitEqually(owed, body.shares.length)
    state = {
      ...SHARED,
      myShareCents: body.myShareCents,
      peerDebt: {
        ...SHARED.peerDebt,
        amountCents: owed,
        shares: body.shares.map((s, i) => share(s.contactId, CONTACTS.find((c) => c.id.endsWith(s.contactId))!.name, parts[i])),
      },
    }

    return answer(true, state)
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
        <TransactionShareSection transactionId="/api/transactions/01TX" label="GITE DES CEVENNES" />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

/** Text with every kind of space made plain: fr-FR money carries no-break ones. */
const plain = (el: HTMLElement) => (el.textContent ?? '').replace(/\s/gu, ' ')

const partOf = (name: string) => screen.getByLabelText(`Part de ${name} (€)`)

const pick = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
  await user.click(screen.getByRole('button', { name: 'Ajouter une personne' }))
  const dialog = await screen.findByRole('dialog', { name: 'Qui te doit ?' })
  await user.click(await within(dialog).findByText(name))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Qui te doit ?' })).not.toBeInTheDocument())
}

describe('splitEqually and parseEuros', () => {
  test('the last share takes the odd cents, like the API', () => {
    expect(splitEqually(60000, 3)).toEqual([20000, 20000, 20000])
    expect(splitEqually(1000, 3)).toEqual([333, 333, 334])
    expect(splitEqually(500, 0)).toEqual([])
  })

  test('reads euros typed the French way', () => {
    expect(parseEuros('200')).toBe(20000)
    expect(parseEuros('12,5')).toBe(1250)
    expect(parseEuros('12.05 €')).toBe(1205)
    expect(parseEuros('')).toBeNull()
    expect(parseEuros('abc')).toBeNull()
    expect(parseEuros('-3')).toBeNull()
  })
})

describe('TransactionShareSection', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    state = NOT_SHARED
    refusal = null
    fetchMock.mockImplementation(api)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('an income or a transfer cannot be shared', async () => {
    state = { ...NOT_SHARED, amountCents: 80000, canBeShared: false }
    renderSection()

    expect(await screen.findByText('Seule une dépense peut être partagée.')).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Dépense partagée' })).not.toBeInTheDocument()
  })

  test('a spend not shared shows the switch off and its helper', async () => {
    renderSection()

    const toggle = await screen.findByRole('switch', { name: 'Dépense partagée' })
    expect(toggle).not.toBeChecked()
    expect(screen.getByText('Seule ta part compte dans tes enveloppes.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Ta part (€)')).not.toBeInTheDocument()
  })

  test('the gîte: my part of 200 € between Julie, Tom and Inès, split equally, is saved with one PUT', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(await screen.findByRole('switch', { name: 'Dépense partagée' }))
    expect(screen.getByLabelText('Ta part (€)')).toHaveValue('400')
    expect(plain(screen.getByTestId('owed-to-me'))).toContain('On te doit 400,00 €')
    expect(screen.getByLabelText('Objet')).toHaveValue('GITE DES CEVENNES')

    await pick(user, 'Julie')
    await pick(user, 'Tom')
    expect(partOf('Julie')).toHaveValue('200')
    expect(partOf('Tom')).toHaveValue('200')

    await pick(user, 'Inès')
    const part = screen.getByLabelText('Ta part (€)')
    await user.clear(part)
    await user.type(part, '200')

    expect(plain(screen.getByTestId('owed-to-me'))).toContain('On te doit 600,00 €')
    for (const name of ['Julie', 'Tom', 'Inès']) expect(partOf(name)).toHaveValue('200')

    expect(screen.getByRole('toolbar', { name: 'Barre de mise en forme' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Notes'), 'Clés chez Tom')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le partage' }))

    await waitFor(() =>
      expect(writes('PUT')).toEqual([
        {
          url: '/api/finance/transactions/01TX/my-share',
          body: {
            myShareCents: 20000,
            object: 'GITE DES CEVENNES',
            notes: 'Clés chez Tom',
            shares: [{ contactId: '01JULIE' }, { contactId: '01TOM' }, { contactId: '01INES' }],
          },
        },
      ]),
    )
    expect(await screen.findByText('Partage enregistré')).toBeInTheDocument()
  })

  test('the steppers move my part by one euro, and the split follows', async () => {
    const user = userEvent.setup()
    state = SHARED
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Augmenter ta part' }))
    expect(screen.getByLabelText('Ta part (€)')).toHaveValue('201')
    expect(plain(screen.getByTestId('owed-to-me'))).toContain('On te doit 599,00 €')
    expect(partOf('Julie')).toHaveValue('199,66')
    expect(partOf('Inès')).toHaveValue('199,68')

    await user.click(screen.getByRole('button', { name: 'Diminuer ta part' }))
    await user.click(screen.getByRole('button', { name: 'Diminuer ta part' }))
    expect(screen.getByLabelText('Ta part (€)')).toHaveValue('199')
  })

  test('my part must stay under the amount', async () => {
    const user = userEvent.setup()
    state = SHARED
    renderSection()

    const part = await screen.findByLabelText('Ta part (€)')
    await user.clear(part)
    await user.type(part, '800')

    expect(screen.getByText(/Entre 0 et moins de 800,00/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer le partage' })).toBeDisabled()
  })

  test('an amount typed by hand freezes the split, and every amount is sent', async () => {
    const user = userEvent.setup()
    state = SHARED
    renderSection()

    await user.clear(await screen.findByLabelText('Part de Julie (€)'))
    await user.type(partOf('Julie'), '100')

    expect(screen.getByText(/Les parts font 500,00.*elles doivent faire 600,00/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer le partage' })).toBeDisabled()

    await user.clear(partOf('Tom'))
    await user.type(partOf('Tom'), '300')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le partage' }))

    await waitFor(() => expect(writes('PUT')).toHaveLength(1))
    expect(writes('PUT')[0].body).toEqual({
      myShareCents: 20000,
      object: 'Gîte Ardèche',
      notes: 'Clés chez Tom',
      shares: [
        { contactId: '01JULIE', amountCents: 10000 },
        { contactId: '01TOM', amountCents: 30000 },
        { contactId: '01INES', amountCents: 20000 },
      ],
    })
  })

  test('a person can be taken off the list, and the others share their part', async () => {
    const user = userEvent.setup()
    state = SHARED
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Retirer Inès' }))

    expect(screen.queryByLabelText('Part de Inès (€)')).not.toBeInTheDocument()
    expect(partOf('Julie')).toHaveValue('300')
    expect(partOf('Tom')).toHaveValue('300')
  })

  test('a refusal shows the API\'s message', async () => {
    const user = userEvent.setup()
    state = SHARED
    refusal = 'La part de Tom ne peut pas descendre sous ce qu’il a déjà remboursé.'
    renderSection()

    await user.click(await screen.findByRole('button', { name: 'Enregistrer le partage' }))

    expect(await screen.findByText(refusal)).toBeInTheDocument()
  })

  test('turning the switch off on a shared spend asks, then removes the share', async () => {
    const user = userEvent.setup()
    state = SHARED
    renderSection()

    await user.click(await screen.findByRole('switch', { name: 'Dépense partagée' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Ne plus partager cette dépense ?')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Ne plus partager' }))

    await waitFor(() => expect(writes('DELETE')).toEqual([{ url: '/api/finance/transactions/01TX/my-share', body: undefined }]))
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Dépense partagée' })).not.toBeChecked())
    expect(screen.queryByLabelText('Ta part (€)')).not.toBeInTheDocument()
  })

  test('links to everything owed', async () => {
    state = SHARED
    renderSection()

    expect(await screen.findByRole('link', { name: 'Voir tout ce qu’on te doit' })).toHaveAttribute('href', '/contacts')
  })
})
