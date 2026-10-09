import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, SimpleForm, TextInput, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RulePreviewPanel } from './RulePreviewPanel'

const CATEGORIES = [
  { id: '/api/categories/1', name: 'Alimentation' },
  { id: '/api/categories/2', name: 'Voyages' },
]

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
})

const MATCHES = [
  {
    transactionId: 't1',
    label: 'CARREFOUR MARKET 123',
    amountCents: -4250,
    bookedAt: '2026-10-02',
    currentCategoryId: null,
    wouldChange: true,
  },
  {
    transactionId: 't2',
    label: 'CARREFOUR CITY',
    amountCents: 1200,
    bookedAt: '2026-09-28',
    currentCategoryId: '2',
    wouldChange: false,
  },
]

const fetchMock = vi.fn()

const respond = (ok: boolean, body: unknown) =>
  Promise.resolve({ ok, json: async () => body } as Response)

const bodies = () =>
  fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

const renderPanel = (defaultValues: Record<string, unknown> = {}) =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <SimpleForm toolbar={false} defaultValues={defaultValues}>
        <TextInput source="labelPattern" label="Texte" />
        <RulePreviewPanel />
      </SimpleForm>
    </AdminContext>,
  )

describe('RulePreviewPanel', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('asks for a pattern and calls nothing while it is empty', async () => {
    renderPanel()

    expect(await screen.findByText('Transactions trouvées')).toBeInTheDocument()
    expect(screen.getByText(/Saisissez le texte à reconnaître/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('lists the matches while typing, with one request for the whole word', async () => {
    fetchMock.mockImplementation(() =>
      respond(true, { total: 2, changeCount: 1, matches: MATCHES }),
    )
    const user = userEvent.setup()
    renderPanel({ category: '/api/categories/1' })

    await user.type(await screen.findByLabelText('Texte'), 'CARREFOUR')

    expect(await screen.findByText('CARREFOUR MARKET 123')).toBeInTheDocument()
    expect(screen.getByText(/2 transaction\(s\) trouvée\(s\) · 1 changeraient de catégorie/)).toBeInTheDocument()
    expect(screen.getByText('02/10/2026')).toBeInTheDocument()
    expect(screen.getByText(/42,50/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(bodies()[0]).toMatchObject({ labelPattern: 'CARREFOUR', categoryId: '1', ruleId: null })
  })

  test('sets the rows that would change apart, from the old category to the new', async () => {
    fetchMock.mockImplementation(() =>
      respond(true, { total: 2, changeCount: 1, matches: MATCHES }),
    )
    const user = userEvent.setup()
    renderPanel({ category: '/api/categories/1' })
    await user.type(await screen.findByLabelText('Texte'), 'CARREFOUR')

    const changing = (await screen.findByText('CARREFOUR MARKET 123')).closest('tr') as HTMLElement
    const staying = screen.getByText('CARREFOUR CITY').closest('tr') as HTMLElement

    expect(changing).toHaveAttribute('data-would-change', 'true')
    expect(within(changing).getByText('Sans catégorie → Alimentation')).toBeInTheDocument()
    expect(staying).toHaveAttribute('data-would-change', 'false')
    expect(within(staying).getByText('Voyages')).toBeInTheDocument()
  })

  test('says how many are shown when the list is truncated', async () => {
    fetchMock.mockImplementation(() =>
      respond(true, { total: 120, changeCount: 80, matches: MATCHES }),
    )
    const user = userEvent.setup()
    renderPanel({ category: '/api/categories/1' })
    await user.type(await screen.findByLabelText('Texte'), 'CARREFOUR')

    expect(await screen.findByText(/2 premières sur 120/)).toBeInTheDocument()
  })

  test('invites to pick a category when none is chosen', async () => {
    fetchMock.mockImplementation(() =>
      respond(true, { total: 2, changeCount: 0, matches: MATCHES.map((m) => ({ ...m, wouldChange: false })) }),
    )
    const user = userEvent.setup()
    renderPanel()
    await user.type(await screen.findByLabelText('Texte'), 'CARREFOUR')

    expect(await screen.findByText(/choisissez une catégorie pour voir celles qui changeraient/)).toBeInTheDocument()
    expect(bodies()[0].categoryId).toBeNull()
  })

  test('shows an empty state when nothing matches', async () => {
    fetchMock.mockImplementation(() => respond(true, { total: 0, changeCount: 0, matches: [] }))
    const user = userEvent.setup()
    renderPanel()
    await user.type(await screen.findByLabelText('Texte'), 'ZZZZ')

    expect(await screen.findByText('Aucune transaction trouvée')).toBeInTheDocument()
  })

  test('shows the API message on a 400 and keeps the form usable', async () => {
    fetchMock.mockImplementation(() => respond(false, { error: 'Motif invalide.' }))
    const user = userEvent.setup()
    renderPanel()
    await user.type(await screen.findByLabelText('Texte'), 'ZZZZ')

    expect(await screen.findByText(/Motif invalide\./)).toBeInTheDocument()
    expect(screen.getByText(/Vous pouvez quand même enregistrer la règle/)).toBeInTheDocument()
    expect(screen.getByLabelText('Texte')).toBeEnabled()
  })

  test('shows a generic message when the network fails', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const user = userEvent.setup()
    renderPanel()
    await user.type(await screen.findByLabelText('Texte'), 'ZZZZ')

    expect(await screen.findByText(/L'aperçu est indisponible pour le moment/)).toBeInTheDocument()
  })

  test('goes back to the prompt when the pattern is cleared', async () => {
    fetchMock.mockImplementation(() => respond(true, { total: 0, changeCount: 0, matches: [] }))
    const user = userEvent.setup()
    renderPanel()
    const input = await screen.findByLabelText('Texte')
    await user.type(input, 'ZZ')
    expect(await screen.findByText('Aucune transaction trouvée')).toBeInTheDocument()

    await user.clear(input)

    expect(screen.getByText(/Saisissez le texte à reconnaître/)).toBeInTheDocument()
    expect(screen.queryByText('Aucune transaction trouvée')).not.toBeInTheDocument()
  })
})
