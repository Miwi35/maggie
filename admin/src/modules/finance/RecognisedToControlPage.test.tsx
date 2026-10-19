import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { emitMercure } from '../../test/mercure'
import { RecognisedToControlPage } from './RecognisedToControlPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const CATEGORIES = [
  { id: '/api/categories/COURSES', name: 'Courses', path: 'Dépenses courantes › Alimentation › Courses', parent: null },
  { id: '/api/categories/LOISIRS', name: 'Loisirs', path: 'Loisirs', parent: null },
]

const line = (id: string, label: string, bookedAt: string, amountCents: number, extra: Record<string, unknown> = {}) => ({
  recognised: true,
  id,
  label,
  counterpartyName: null,
  amountCents,
  currency: 'EUR',
  bookedAt,
  accountId: 'A1',
  accountName: 'Courant',
  judgeable: amountCents < 0,
  retrospect: 'unrated',
  suggestion: null,
  categoryId: 'COURSES',
  categoryPath: 'Dépenses courantes › Alimentation › Courses',
  qualifiedBy: 'rule',
  qualifiedBySeriesId: null,
  qualifiedByRuleId: 'R1',
  ...extra,
})

const COURSES_GROUP = {
  operationType: 'rule',
  operationId: 'R1',
  label: 'Courses',
  count: 2,
  totalCents: -7000,
  unratedCount: 2,
  transactions: [
    line('T1', 'CARREFOUR CITY 12', '2026-10-03', -4500),
    line('T2', 'CARREFOUR MARKET 34', '2026-10-09', -2500),
  ],
}

const GYM_GROUP = {
  operationType: 'series',
  operationId: 'S1',
  label: 'Salle de sport',
  count: 1,
  totalCents: -2990,
  unratedCount: 0,
  transactions: [
    line('T3', 'BASIC FIT', '2026-10-05', -2990, {
      retrospect: 'keep',
      qualifiedBy: 'series',
      qualifiedByRuleId: null,
      qualifiedBySeriesId: 'S1',
    }),
  ],
}

const GONE_GROUP = {
  operationType: 'series',
  operationId: null,
  label: 'Règle ou série supprimée',
  count: 1,
  totalCents: -990,
  unratedCount: 1,
  transactions: [line('T4', 'ANCIEN ABONNEMENT', '2026-10-02', -990, { qualifiedBy: 'series' })],
}

const SALARY_GROUP = {
  operationType: 'rule',
  operationId: 'R2',
  label: 'Remboursements',
  count: 1,
  totalCents: 5000,
  unratedCount: 0,
  transactions: [line('T5', 'REMBOURSEMENT MUTUELLE', '2026-10-07', 5000, { qualifiedByRuleId: 'R2' })],
}

const reviewOf = (...groups: Record<string, unknown>[]) => ({
  month: '2026-10',
  total: groups.reduce((sum, group) => sum + (group.count as number), 0),
  groups,
})

const answer = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const CONTROLLED = { controlledCount: 2, judgedCount: 2 }

const fetchMock = vi.fn()
const dataProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
})

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider}>
        <RecognisedToControlPage />
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

/** Reads answer with `read`; any write succeeds. */
const serve = (read: () => unknown) =>
  fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
    init?.method ? answer(true, CONTROLLED) : answer(true, read()),
  )

const groupOf = async (name: string) => within(await screen.findByRole('region', { name: `Groupe ${name}` }))

describe('RecognisedToControlPage', () => {
  beforeEach(() => {
    serve(() => reviewOf(COURSES_GROUP, GYM_GROUP))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows one group per operation with its counter, the lines oldest first', async () => {
    renderPage()

    const courses = await groupOf('Courses')
    expect(courses.getByRole('heading', { name: 'Courses · 2' })).toBeInTheDocument()
    const rows = courses.getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('CARREFOUR CITY 12')).toBeInTheDocument()
    expect(within(rows[0]).getByText('03/10/2026')).toBeInTheDocument()
    expect(within(rows[0]).getByText(/45,00/)).toBeInTheDocument()
    expect(within(rows[1]).getByText('CARREFOUR MARKET 34')).toBeInTheDocument()
    expect((await groupOf('Salle de sport')).getByRole('heading', { name: 'Salle de sport · 1' })).toBeInTheDocument()
    expect(screen.getByText('3 à contrôler')).toBeInTheDocument()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/finance/review/recognised')
  })

  test('a transaction changed elsewhere makes the review read again', async () => {
    renderPage()
    await groupOf('Courses')

    serve(() => reviewOf(GYM_GROUP))
    await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/T1' })

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Groupe Courses' })).not.toBeInTheDocument())
    expect(screen.getByRole('region', { name: 'Groupe Salle de sport' })).toBeInTheDocument()
  })

  test('a group folds away and unfolds again', async () => {
    renderPage()

    const courses = await groupOf('Courses')
    await userEvent.click(courses.getByRole('button', { name: 'Replier Courses' }))
    await waitFor(() => expect(courses.queryByText('CARREFOUR CITY 12')).not.toBeInTheDocument())
    expect(courses.getByRole('button', { name: 'Valider' })).toBeInTheDocument()

    await userEvent.click(courses.getByRole('button', { name: 'Déplier Courses' }))
    expect(await courses.findByText('CARREFOUR CITY 12')).toBeInTheDocument()
  })

  test('nothing to control says so', async () => {
    serve(() => reviewOf())
    renderPage()

    expect(await screen.findByText('Rien à contrôler ce mois-ci')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
  })

  test('a failed read shows the server message and can be retried', async () => {
    fetchMock.mockImplementationOnce(() => answer(false, { error: 'Service indisponible' }))
    renderPage()

    expect(await screen.findByText('Service indisponible')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await groupOf('Courses')).toBeTruthy()
  })

  test('« Valider » sends the lines of the group, without satisfaction, then reads again', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    serve(() => reviewOf(GYM_GROUP))
    await userEvent.click(courses.getByRole('button', { name: 'Valider' }))

    await waitFor(() => expect(writes('POST')).toHaveLength(1))
    expect(writes('POST')[0]).toEqual({
      url: '/api/finance/review/controls',
      body: { transactionIds: ['T1', 'T2'] },
      contentType: 'application/json',
    })
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Groupe Courses' })).not.toBeInTheDocument())
    expect(screen.getByRole('region', { name: 'Groupe Salle de sport' })).toBeInTheDocument()
  })

  test('« Tout à conserver » asks to keep the unjudged spends of the group', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    await userEvent.click(courses.getByRole('button', { name: 'Tout à conserver' }))

    await waitFor(() => expect(writes('POST')).toHaveLength(1))
    expect(writes('POST')[0].body).toEqual({ transactionIds: ['T1', 'T2'], satisfaction: 'keep' })
  })

  test('« Tout à conserver » is out of reach when every spend of the group is already judged', async () => {
    renderPage()

    expect((await groupOf('Salle de sport')).getByRole('button', { name: 'Tout à conserver' })).toBeDisabled()
  })

  test('« Superflue » judges one line and pressing it again takes it back', async () => {
    renderPage()
    const courses = await groupOf('Courses')
    const first = within(courses.getAllByRole('row')[1])

    serve(() =>
      reviewOf({ ...COURSES_GROUP, transactions: [{ ...COURSES_GROUP.transactions[0], retrospect: 'avoidable' }, COURSES_GROUP.transactions[1]] }),
    )
    await userEvent.click(first.getByRole('button', { name: 'Superflue' }))

    await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
    expect(writes('PATCH')[0]).toEqual({
      url: '/api/transactions/T1',
      body: { retrospect: 'avoidable' },
      contentType: 'application/merge-patch+json',
    })

    await userEvent.click(await courses.findByRole('button', { name: 'Superflue', pressed: true }))
    await waitFor(() => expect(writes('PATCH')).toHaveLength(2))
    expect(writes('PATCH')[1].body).toEqual({ retrospect: 'unrated' })
  })

  test('« À conserver » says the spend was worth it', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    await userEvent.click(within(courses.getAllByRole('row')[2]).getByRole('button', { name: 'À conserver' }))

    await waitFor(() => expect(writes('PATCH')[0]).toMatchObject({ url: '/api/transactions/T2', body: { retrospect: 'keep' } }))
  })

  test('an income offers no satisfaction at all', async () => {
    serve(() => reviewOf(SALARY_GROUP))
    renderPage()

    const group = await groupOf('Remboursements')
    expect(group.getByText('REMBOURSEMENT MUTUELLE')).toBeInTheDocument()
    expect(group.queryByRole('button', { name: 'À conserver' })).not.toBeInTheDocument()
    expect(group.queryByRole('button', { name: 'Superflue' })).not.toBeInTheDocument()
  })

  test('« Corriger » qualifies the line by hand, and it is gone once the review is read again', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    await userEvent.click(within(courses.getAllByRole('row')[1]).getByRole('button', { name: 'Corriger' }))
    const dialog = await screen.findByRole('dialog', { name: 'Corriger' })
    expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
    await userEvent.click(within(dialog).getByLabelText('Catégorie'))
    await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
    await userEvent.click(within(dialog).getByRole('switch', { name: 'Obligatoire' }))

    serve(() => reviewOf({ ...COURSES_GROUP, count: 1, transactions: [COURSES_GROUP.transactions[1]] }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
    expect(writes('PATCH')[0]).toEqual({
      url: '/api/transactions/T1',
      body: { category: '/api/categories/LOISIRS', qualification: { mandatory: true, kind: 'none' } },
      contentType: 'application/merge-patch+json',
    })
    await waitFor(() => expect(screen.queryByText('CARREFOUR CITY 12')).not.toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('CARREFOUR MARKET 34')).toBeInTheDocument()
  })

  test('a refused gesture shows its message and keeps the lines in place', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method
        ? answer(false, { error: 'Transaction not found.' })
        : answer(true, reviewOf(COURSES_GROUP, GYM_GROUP)),
    )
    await userEvent.click(courses.getByRole('button', { name: 'Valider' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Transaction not found.')
    expect(courses.getByText('CARREFOUR CITY 12')).toBeInTheDocument()
    expect(courses.getByRole('button', { name: 'Valider' })).toBeEnabled()
  })

  test('a refused correction keeps its dialog open with the message', async () => {
    renderPage()
    const courses = await groupOf('Courses')

    await userEvent.click(within(courses.getAllByRole('row')[1]).getByRole('button', { name: 'Corriger' }))
    const dialog = await screen.findByRole('dialog', { name: 'Corriger' })
    await userEvent.click(within(dialog).getByLabelText('Catégorie'))
    await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method ? answer(false, { detail: 'Unknown category.' }) : answer(true, reviewOf(COURSES_GROUP)),
    )
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Unknown category.')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Corriger' })).toBeInTheDocument()
  })

  test('the rule behind a group is one link away', async () => {
    renderPage()

    const link = (await groupOf('Courses')).getAllByRole('link', { name: 'Voir la règle' })[0]

    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('/categorization_rules/')
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('/api/categorization_rules/R1')
  })

  test('the series behind a group leads to the recurring operations', async () => {
    renderPage()

    const link = (await groupOf('Salle de sport')).getByRole('link', { name: "Voir l'opération récurrente" })

    expect(link).toHaveAttribute('href', '/finance/recurring-operations')
  })

  test('a deleted operation offers no link to it, only the sheet of the line', async () => {
    serve(() => reviewOf(GONE_GROUP))
    renderPage()

    const group = await groupOf('Règle ou série supprimée')
    expect(group.queryByRole('link', { name: /Voir la règle|Voir l'opération récurrente/ })).not.toBeInTheDocument()
    expect(group.getByRole('link', { name: 'Voir la fiche' })).toBeInTheDocument()
  })

  test('« Voir la fiche » links to the transaction sheet', async () => {
    renderPage()

    const link = (await groupOf('Courses')).getAllByRole('link', { name: 'Voir la fiche' })[0]

    expect(link).toHaveAttribute('href', expect.stringContaining('/transactions/'))
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('/api/transactions/T1')
  })
})
