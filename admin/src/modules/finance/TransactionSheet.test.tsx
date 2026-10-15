import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { TRANSACTION_SHEET_SECTIONS, TransactionSheet, toSheetPayload } from './TransactionSheet'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CATEGORIES = [
  { id: '/api/categories/loyer', name: 'Loyer', obligation: 'mandatory' },
  { id: '/api/categories/netflix', name: 'Netflix', obligation: 'optional' },
  { id: '/api/categories/salaire', name: 'Salaire', obligation: 'income' },
]
const SERIES = { id: '/api/recurring_operations/01CREDIT', label: 'Crédit auto' }
const ACCOUNTS = [{ id: '/api/accounts/1', name: 'Compte courant' }]
const ENVELOPES = [
  { id: '/api/envelopes/hellfest', mode: 'one_off', name: 'Hellfest 2026', amountCents: 40000 },
  { id: '/api/envelopes/loisirs', mode: 'monthly', category: '/api/categories/netflix', year: 2026, month: 10 },
]

const RECORD = {
  id: '/api/transactions/01TX',
  label: 'PRLV NETFLIX',
  amountCents: -1349,
  currency: 'EUR',
  bookedAt: '2026-10-12T00:00:00+00:00',
  account: '/api/accounts/1',
  category: '/api/categories/netflix',
  status: 'spent',
  isExceptional: false,
  recurringOperation: null,
  note: null,
  qualification: { mandatory: false, kind: 'none' },
  qualifiedBy: null,
  qualifiedBySeries: null,
  qualifiedByRule: null,
}

const NONE = { transferKind: 'none', transferSource: 'auto', counterpart: null }
const fetchMock = vi.fn()
const envelopeFilters: Record<string, unknown>[] = []
let transferState: unknown = NONE

const getList = ((resource: string, params?: { filter?: Record<string, unknown> }) => {
  if (resource === 'envelopes') envelopeFilters.push(params?.filter ?? {})
  const data =
    resource === 'categories' ? CATEGORIES : resource === 'accounts' ? ACCOUNTS : resource === 'envelopes' ? ENVELOPES : []

  return Promise.resolve({ data, total: data.length })
}) as unknown as DataProvider['getList']

const renderSheet = (record: Record<string, unknown> = RECORD, update = vi.fn().mockResolvedValue({ data: record })) => {
  render(
    <MemoryRouter initialEntries={[`/transactions/${encodeURIComponent(record.id as string)}`]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: ((resource: string, params: { id: string }) =>
            Promise.resolve({ data: resource === 'recurring_operations' && params.id === SERIES.id ? SERIES : record })) as unknown as DataProvider['getOne'],
          getList,
          getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
          update: update as unknown as DataProvider['update'],
        })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="transactions">
          <Routes>
            <Route path="/transactions/:id" element={<TransactionSheet />} />
          </Routes>
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return update
}

const sectionNames = () =>
  Array.from(document.querySelectorAll('[data-section]')).map((el) => el.getAttribute('data-section'))

describe('TransactionSheet', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    transferState = NONE
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(() =>
      Promise.resolve({ ok: true, status: 200, json: async () => transferState } as Response),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('draws the six sections in order, each with its title', async () => {
    renderSheet()

    await screen.findByRole('heading', { name: 'Nature' })

    expect(sectionNames()).toEqual([...TRANSACTION_SHEET_SECTIONS])
    for (const title of ['Nature', 'Classement', 'Récurrence', 'Partage', 'Jugement', 'Note']) {
      expect(screen.getByRole('heading', { name: title })).toBeInTheDocument()
    }
  })

  test.each(['internal', 'rejected', 'advance', 'repayment', 'funding'])(
    'a line marked %s keeps only Nature and Note',
    async (kind) => {
      transferState = { transferKind: kind, transferSource: 'manual', counterpart: null }

      renderSheet()

      await waitFor(() => expect(sectionNames()).toEqual(['nature', 'note']))
      expect(screen.queryByRole('heading', { name: 'Classement' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Créer un filtre' })).not.toBeInTheDocument()
    },
  )

  test('a rejection says whether it is regularised', async () => {
    transferState = { transferKind: 'rejected', transferSource: 'manual', counterpart: null }

    renderSheet()

    expect(await screen.findByText(/Non régularisé/)).toBeInTheDocument()
  })

  test('the classement section opens the filter dialog filled from the line', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.click(await screen.findByRole('button', { name: 'Créer un filtre' }))

    const dialog = await screen.findByRole('dialog', { name: 'Créer un filtre' })
    expect(within(dialog).getByLabelText('…ce texte')).toHaveValue('PRLV NETFLIX')
  })

  test('Enregistrer sends the typed fields and the note, never the series', async () => {
    const user = userEvent.setup()
    const update = renderSheet({ ...RECORD, recurringOperation: '/api/recurring_operations/01X', recurringOccurrenceOn: '2026-10-12' })

    await user.type(await screen.findByRole('textbox', { name: 'Note' }), 'Résilier en janvier')
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const data = update.mock.calls[0][1].data as Record<string, unknown>
    expect(data.note).toBe('Résilier en janvier')
    expect(data).not.toHaveProperty('recurringOperation')
    expect(data).not.toHaveProperty('recurringOccurrenceOn')
  })

  test('a refused save shows the API message and stays on the sheet', async () => {
    const user = userEvent.setup()
    const update = vi.fn().mockRejectedValue(new Error('Montant invalide.'))
    renderSheet(RECORD, update)

    await user.type(await screen.findByRole('textbox', { name: 'Note' }), 'x')
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    expect((await screen.findAllByText('Montant invalide.')).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Note' })).toBeInTheDocument()
  })

  test('a note over 2000 characters is refused before any call', async () => {
    const user = userEvent.setup()
    const update = renderSheet()
    const note = await screen.findByRole('textbox', { name: 'Note' })

    await user.click(note)
    await user.paste('a'.repeat(2001))
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    expect(await screen.findByText(/ne peut pas dépasser 2000 caractères/)).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
  })

  test('offers the one-off envelopes only, to attribute the spend to', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.click(await screen.findByLabelText('Attribuer à une enveloppe ponctuelle'))

    expect(await screen.findByRole('option', { name: 'Hellfest 2026' })).toBeInTheDocument()
    const listbox = screen.getByRole('listbox')
    expect(within(listbox).getByText('Aucune')).toBeInTheDocument()
    expect(within(listbox).getAllByRole('option')).toHaveLength(2)
    // Asked of the API: the one-off envelopes would sort after a page of monthly ones.
    expect(envelopeFilters).toContainEqual({ mode: 'one_off' })
  })

  test('Enregistrer attributes the spend to the chosen one-off envelope', async () => {
    const user = userEvent.setup()
    const update = renderSheet()

    await user.click(await screen.findByLabelText('Attribuer à une enveloppe ponctuelle'))
    await user.click(await screen.findByRole('option', { name: 'Hellfest 2026' }))
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const data = update.mock.calls[0][1].data as Record<string, unknown>
    expect(data.oneOffEnvelope).toBe('/api/envelopes/hellfest')
    expect(data.category).toBe('/api/categories/netflix')
  })

  test('choosing « Aucune » takes the attribution back', async () => {
    const user = userEvent.setup()
    const update = renderSheet({ ...RECORD, oneOffEnvelope: '/api/envelopes/hellfest' })

    const field = await screen.findByLabelText('Attribuer à une enveloppe ponctuelle')
    await waitFor(() => expect(field).toHaveTextContent('Hellfest 2026'))
    await user.click(field)
    await user.click(within(await screen.findByRole('listbox')).getByText('Aucune'))
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect((update.mock.calls[0][1].data as Record<string, unknown>).oneOffEnvelope).toBeNull()
  })

  test('a refused attribution shows the API message', async () => {
    const user = userEvent.setup()
    const update = vi.fn().mockRejectedValue(new Error('A spend is attributed by hand to a one-off envelope only.'))
    renderSheet(RECORD, update)

    await user.click(await screen.findByLabelText('Attribuer à une enveloppe ponctuelle'))
    await user.click(await screen.findByRole('option', { name: 'Hellfest 2026' }))
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    expect((await screen.findAllByText(/one-off envelope only/)).length).toBeGreaterThan(0)
  })

  test('the classement section offers every category, whatever the sign of the line', async () => {
    const user = userEvent.setup()
    renderSheet()

    await user.click(await screen.findByRole('combobox', { name: /Catégorie/ }))

    const options = await screen.findAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Loyer', 'Netflix', 'Salaire'])
  })

  test('a line the owner qualified says « à la main »', async () => {
    renderSheet({ ...RECORD, qualifiedBy: 'manual' })

    expect(await screen.findByTestId('qualification-provenance')).toHaveTextContent('à la main')
  })

  test('a line a series qualified says which series', async () => {
    renderSheet({
      ...RECORD,
      qualification: { mandatory: true, kind: 'repayment' },
      qualifiedBy: 'series',
      qualifiedBySeries: SERIES.id,
    })

    expect(await screen.findByText(/posée par la série Crédit auto/)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Obligatoire' })).toBeChecked()
    expect(screen.getByRole('combobox', { name: 'Type' })).toHaveTextContent('Remboursement')
    expect(screen.getByText(/Remboursement : ici, je rembourse/)).toBeInTheDocument()
  })

  test('editing the qualification sends it whole, and the line then reads « à la main »', async () => {
    const user = userEvent.setup()
    const qualified = {
      ...RECORD,
      qualification: { mandatory: true, kind: 'repayment' },
      qualifiedBy: 'manual',
    }
    const update = renderSheet(RECORD, vi.fn().mockResolvedValue({ data: qualified }))

    await user.click(await screen.findByRole('switch', { name: 'Obligatoire' }))
    await user.click(screen.getByRole('combobox', { name: 'Type' }))
    await user.click(await screen.findByRole('option', { name: 'Remboursement' }))
    expect(screen.queryByTestId('qualification-provenance')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].data).toMatchObject({
      category: '/api/categories/netflix',
      qualification: { mandatory: true, kind: 'repayment' },
    })
    expect(await screen.findByTestId('qualification-provenance')).toHaveTextContent('à la main')
  })

  test('a refused qualification shows the API message', async () => {
    const user = userEvent.setup()
    const update = vi.fn().mockRejectedValue(new Error('qualification: Type de mouvement inconnu.'))
    renderSheet(RECORD, update)

    await user.click(await screen.findByRole('switch', { name: 'Obligatoire' }))
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save/ }))

    expect((await screen.findAllByText(/Type de mouvement inconnu/)).length).toBeGreaterThan(0)
  })

  test('offers to delete the line', async () => {
    renderSheet()

    expect(await screen.findByRole('button', { name: /Supprimer|Delete/ })).toBeInTheDocument()
  })
})

describe('toSheetPayload', () => {
  test('an empty note clears it, a typed one is kept', () => {
    const base = { label: 'X', amount: 5, nature: 'expense', bookedAt: '2026-10-12', account: '/api/accounts/1' }

    expect(toSheetPayload({ ...base, note: '   ' }).note).toBeNull()
    expect(toSheetPayload({ ...base, note: 'À revoir' }).note).toBe('À revoir')
  })

  test('« Aucune » sends a null attribution, a chosen envelope its IRI', () => {
    const base = { label: 'X', amount: 5, nature: 'expense', bookedAt: '2026-10-12', account: '/api/accounts/1' }

    expect(toSheetPayload({ ...base, oneOffEnvelope: '' }).oneOffEnvelope).toBeNull()
    expect(toSheetPayload({ ...base, oneOffEnvelope: '/api/envelopes/h' }).oneOffEnvelope).toBe('/api/envelopes/h')
  })

  test('never carries the series, which the gestures already wrote', () => {
    const payload = toSheetPayload({
      label: 'X',
      amount: 5,
      nature: 'expense',
      bookedAt: '2026-10-12',
      account: '/api/accounts/1',
      recurringOperation: '/api/recurring_operations/01X',
      recurringOccurrenceOn: '2026-10-12',
    })

    expect(payload).not.toHaveProperty('recurringOperation')
    expect(payload).not.toHaveProperty('recurringOccurrenceOn')
  })
})
