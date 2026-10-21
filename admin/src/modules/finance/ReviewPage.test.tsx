import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { emitMercure } from '../../test/mercure'
import { DESKTOP_WIDTH, resetViewport, setViewportWidth } from '../../test/viewport'
import { currentMonthKey } from './reviewActivity'
import { LEGACY_REVIEW_REDIRECTS, monthLabel, shiftMonth } from './reviewFilter'
import { ReviewPage } from './ReviewPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const MONTH = currentMonthKey()

const CATEGORIES = [
  { id: '/api/categories/COURSES', name: 'Courses', path: 'Dépenses courantes › Alimentation › Courses', parent: null },
  { id: '/api/categories/LOISIRS', name: 'Loisirs', path: 'Loisirs', parent: null },
]

const line = (id: string, label: string, day: string, amountCents: number, extra: Record<string, unknown> = {}) => ({
  recognised: false,
  id,
  label,
  counterpartyName: null,
  amountCents,
  currency: 'EUR',
  bookedAt: `${MONTH}-${day}`,
  accountId: 'A1',
  accountName: 'Courant',
  judgeable: amountCents < 0,
  retrospect: 'unrated',
  suggestion: null,
  categoryId: null,
  categoryPath: null,
  qualifiedBy: null,
  qualifiedBySeriesId: null,
  qualifiedByRuleId: null,
  operationLabel: null,
  contact: null,
  asksForContact: false,
  counterpartyLabel: label,
  reason: 'category',
  asksForPeerKind: false,
  ...extra,
})

const CARREFOUR = line('T1', 'CARREFOUR MARKET 1234', '12', -4599, {
  suggestion: {
    categoryId: 'COURSES',
    categoryPath: 'Dépenses courantes › Alimentation › Courses',
    source: 'rule',
    reason: 'La règle « contient CARREFOUR » classe ce marchand en Courses.',
  },
})
const MYSTERY = line('T2', 'PAIEMENT PAR CARTE X', '10', -1200)
const NETFLIX = line('T3', 'NETFLIX.COM', '05', -1299, {
  recognised: true,
  categoryId: 'ABOS',
  categoryPath: 'Loisirs › Abonnements',
  qualifiedBy: 'rule',
  qualifiedByRuleId: 'R1',
  operationLabel: 'Abonnements',
  counterpartyName: 'Netflix',
})
const SALARY = line('T4', 'VIREMENT SALAIRE', '03', 250000, {
  recognised: true,
  categoryId: 'SALAIRE',
  categoryPath: 'Revenus › Salaire',
  qualifiedBy: 'series',
  qualifiedBySeriesId: 'S1',
  operationLabel: 'Salaire',
})

const queueOf = (items: unknown[], extra: Record<string, unknown> = {}) => ({
  month: MONTH,
  unrecognisedCount: items.filter((i) => !(i as { recognised: boolean }).recognised).length,
  unrecognisedCents: 5799,
  recognisedCount: items.filter((i) => (i as { recognised: boolean }).recognised).length,
  period: 'month',
  sinceReviewDate: null,
  total: items.length,
  items,
  ...extra,
})

const SORTED = { success: true, rule: null, ruleCreated: false, alsoSorted: 0, remaining: 1 }

const answer = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()
const dataProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
})

const Where = () => {
  const { pathname, search } = useLocation()

  return <div data-testid="where">{pathname + search}</div>
}

const renderPage = (url = '/finance/review') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <AdminContext dataProvider={dataProvider}>
        <Routes>
          <Route path="/finance/review" element={<ReviewPage />} />
          <Route path="*" element={<div>ailleurs</div>} />
        </Routes>
        <Where />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

/** The queue answers with `read()`; any write succeeds with `written`. */
const serve = (read: () => unknown, written: unknown = SORTED) =>
  fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
    init?.method ? answer(true, written) : answer(true, read()),
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

const queueCalls = () =>
  fetchMock.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith('/api/finance/review/queue'))

const row = (label: string) => screen.getByRole('button', { name: label })
const rows = () => screen.queryAllByTestId('review-row').map((r) => r.getAttribute('aria-label'))
const stateOf = (label: string) => within(row(label)).getByText(/^(À qualifier|Reconnue)$/).textContent
const sheet = () => within(screen.getByRole('region', { name: 'Fiche de la transaction' }))
const shownLine = () => sheet().getByRole('heading', { level: 2 }).textContent
const where = () => screen.getByTestId('where').textContent

/** Waits for the list, then opens the line (the page opens on the list in a narrow window). */
const openLine = async (label: string) => {
  await userEvent.click(await screen.findByRole('button', { name: label }))

  return screen.findByRole('region', { name: 'Fiche de la transaction' })
}

const pill = (name: string) => screen.getByRole('button', { name })

describe('ReviewPage', () => {
  beforeEach(() => {
    serve(() => queueOf([CARREFOUR, MYSTERY, NETFLIX, SALARY]))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    resetViewport()
    fetchMock.mockReset()
  })

  describe('the list', () => {
    test('shows the unrecognised lines then the recognised ones, each oldest first, with its state', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Non reconnues' })
      expect(rows()).toEqual(['PAIEMENT PAR CARTE X', 'CARREFOUR MARKET 1234', 'VIREMENT SALAIRE', 'NETFLIX.COM'])
      expect(within(screen.getByRole('region', { name: 'Non reconnues' })).getAllByTestId('review-row')).toHaveLength(2)
      expect(within(screen.getByRole('region', { name: 'Reconnues' })).getAllByTestId('review-row')).toHaveLength(2)
      expect(stateOf('PAIEMENT PAR CARTE X')).toBe('À qualifier')
      expect(stateOf('NETFLIX.COM')).toBe('Reconnue')
      expect(within(row('CARREFOUR MARKET 1234')).getByText(`12/${MONTH.slice(5)}/${MONTH.slice(0, 4)}`)).toBeInTheDocument()
    })

    test('shows the amount signed, an income with a plus', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Reconnues' })
      expect(within(row('CARREFOUR MARKET 1234')).getByText(/45,99/).textContent).toMatch(/^-|−/)
      expect(within(row('VIREMENT SALAIRE')).getByText(/2\s?500,00/).textContent).toMatch(/^\+/)
    })

    test('reads the month of the period and every line of it in one call', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Reconnues' })
      expect(queueCalls()).toEqual([`/api/finance/review/queue?period=month&month=${MONTH}&limit=1000`])
    })

    test('shows the satisfaction of a line that was judged', async () => {
      serve(() => queueOf([line('T9', 'RESTO', '02', -3000, { retrospect: 'keep' }), line('T8', 'JEU', '04', -900, { retrospect: 'avoidable' })]))
      renderPage()

      await screen.findByRole('region', { name: 'Non reconnues' })
      expect(within(row('RESTO')).getByText('À conserver')).toBeInTheDocument()
      expect(within(row('JEU')).getByText('Superflue')).toBeInTheDocument()
      expect(within(row('JEU')).queryByText('À conserver')).not.toBeInTheDocument()
    })

    test('a line that was controlled shows a check', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      await userEvent.click(sheet().getByRole('button', { name: 'Correct' }))
      await sheet().findByText('Contrôlée')
      await userEvent.click(screen.getByRole('button', { name: 'Retour à la liste' }))

      await waitFor(() => expect(within(row('NETFLIX.COM')).getByTitle('Contrôlée')).toBeInTheDocument())
    })

    test('an empty period says so', async () => {
      serve(() => queueOf([]))
      renderPage()

      expect(await screen.findByText('Rien à revoir sur cette période.')).toBeInTheDocument()
      expect(pill('Toutes · 0')).toBeInTheDocument()
    })

    test('a failed read shows the server message and can be retried', async () => {
      fetchMock.mockImplementationOnce(() => answer(false, { error: 'Service indisponible' }))
      renderPage()

      expect(await screen.findByText('Service indisponible')).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

      expect(await screen.findByRole('region', { name: 'Non reconnues' })).toBeInTheDocument()
    })

    test('a transaction changed elsewhere makes the list read again, keeping what is on screen', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      serve(() => queueOf([CARREFOUR, NETFLIX, SALARY, line('T7', 'NOUVEAU PAIEMENT', '14', -500)]))
      await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/T7' })

      expect(await screen.findByRole('button', { name: 'NOUVEAU PAIEMENT' })).toBeInTheDocument()
      // MYSTERY is no longer in the answer: it stays until the next reload or filter change.
      expect(row('PAIEMENT PAR CARTE X')).toBeInTheDocument()
      expect(queueCalls()).toHaveLength(2)
    })
  })

  describe('the state pills', () => {
    test('count the lines of the period and are all selected by default', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Non reconnues' })
      expect(pill('Toutes · 4')).toHaveAttribute('aria-pressed', 'true')
      expect(pill('À qualifier · 2')).toHaveAttribute('aria-pressed', 'false')
      expect(pill('Reconnues · 2')).toHaveAttribute('aria-pressed', 'false')
    })

    test('filter the list and the URL, without reading the API again', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      await userEvent.click(pill('À qualifier · 2'))
      expect(rows()).toEqual(['PAIEMENT PAR CARTE X', 'CARREFOUR MARKET 1234'])
      expect(where()).toBe('/finance/review?state=unrecognised')
      expect(screen.queryByRole('region', { name: 'Reconnues' })).not.toBeInTheDocument()
      expect(pill('Reconnues · 2')).toBeInTheDocument()

      await userEvent.click(pill('Reconnues · 2'))
      expect(rows()).toEqual(['VIREMENT SALAIRE', 'NETFLIX.COM'])
      expect(where()).toBe('/finance/review?state=recognised')

      await userEvent.click(pill('Toutes · 4'))
      expect(rows()).toHaveLength(4)
      expect(where()).toBe('/finance/review')
      expect(queueCalls()).toHaveLength(1)
    })

    test('an empty filter says nothing matches', async () => {
      serve(() => queueOf([NETFLIX]))
      renderPage('/finance/review?state=unrecognised')

      expect(await screen.findByText('Aucune transaction ne correspond à ces filtres.')).toBeInTheDocument()
    })
  })

  describe('the period', () => {
    test('opens on the current month, named, with the other two periods beside it', async () => {
      renderPage()

      await screen.findByRole('region', { name: 'Non reconnues' })
      expect(screen.getByRole('button', { name: monthLabel(MONTH) })).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByRole('button', { name: 'Depuis la dernière revue' })).toHaveAttribute('aria-pressed', 'false')
      expect(screen.getByRole('button', { name: 'Toutes les dates' })).toHaveAttribute('aria-pressed', 'false')
      expect(screen.getByRole('button', { name: 'Mois suivant' })).toBeDisabled()
    })

    test('« Depuis la dernière revue » and « Toutes les dates » read the API again, and the URL follows', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      serve(() => queueOf([CARREFOUR], { period: 'since_review', sinceReviewDate: `${MONTH}-08` }))
      await userEvent.click(screen.getByRole('button', { name: 'Depuis la dernière revue' }))

      await waitFor(() => expect(rows()).toEqual(['CARREFOUR MARKET 1234']))
      expect(where()).toBe('/finance/review?period=since_review')
      expect(queueCalls()[1]).toBe('/api/finance/review/queue?period=since_review&limit=1000')
      expect(screen.getByText(`depuis le 08/${MONTH.slice(5)}/${MONTH.slice(0, 4)}`)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Mois précédent' })).not.toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Toutes les dates' }))

      await waitFor(() => expect(queueCalls()).toHaveLength(3))
      expect(queueCalls()[2]).toBe('/api/finance/review/queue?period=all&limit=1000')
      expect(where()).toBe('/finance/review?period=all')
    })

    test('the month selector goes back a month, and forward again', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })
      const before = shiftMonth(MONTH, -1)

      serve(() => queueOf([line('T0', 'LOYER', '01', -70000)], { month: before }))
      await userEvent.click(screen.getByRole('button', { name: 'Mois précédent' }))

      expect(await screen.findByRole('button', { name: 'LOYER' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: monthLabel(before) })).toHaveAttribute('aria-pressed', 'true')
      expect(where()).toBe(`/finance/review?month=${before}`)
      expect(queueCalls()[1]).toBe(`/api/finance/review/queue?period=month&month=${before}&limit=1000`)

      await userEvent.click(screen.getByRole('button', { name: 'Mois suivant' }))
      await waitFor(() => expect(where()).toBe('/finance/review'))
    })

    test('the URL round trip: state, period, month and search are read back on a reload', async () => {
      renderPage(`/finance/review?state=recognised&period=all&q=net`)

      expect(await screen.findByRole('button', { name: 'NETFLIX.COM' })).toBeInTheDocument()
      expect(rows()).toEqual(['NETFLIX.COM'])
      expect(pill('Reconnues · 2')).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByRole('button', { name: 'Toutes les dates' })).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByRole('searchbox', { name: 'Rechercher une transaction' })).toHaveValue('net')
      expect(queueCalls()).toEqual(['/api/finance/review/queue?period=all&limit=1000'])
    })

    test('a month in the URL is the month asked', async () => {
      renderPage('/finance/review?month=2026-03')

      await screen.findByRole('region', { name: 'Non reconnues' })
      expect(queueCalls()).toEqual(['/api/finance/review/queue?period=month&month=2026-03&limit=1000'])
      expect(screen.getByRole('button', { name: 'Mars 2026' })).toHaveAttribute('aria-pressed', 'true')
    })
  })

  describe('the search', () => {
    test('keeps the lines whose label or counterparty contains the text, whatever the case', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }), 'carre')
      expect(rows()).toEqual(['CARREFOUR MARKET 1234'])
      expect(where()).toBe('/finance/review?q=carre')

      await userEvent.clear(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }))
      await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }), 'NETFLIX')
      expect(rows()).toEqual(['NETFLIX.COM'])

      // « Netflix » is the counterparty of the line, not only a word of its label.
      await userEvent.clear(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }))
      await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }), 'netflix')
      expect(rows()).toEqual(['NETFLIX.COM'])
      expect(queueCalls()).toHaveLength(1)
    })

    test('does not change the counts of the pills', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher une transaction' }), 'zzz')

      expect(rows()).toEqual([])
      expect(pill('Toutes · 4')).toBeInTheDocument()
      expect(screen.getByText('Aucune transaction ne correspond à ces filtres.')).toBeInTheDocument()
    })
  })

  describe('the fiche of an unrecognised line', () => {
    test('opening a line shows Maggie\'s suggestion, its reason, its position and the three gestures', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      expect(shownLine()).toBe('CARREFOUR MARKET 1234')
      expect(sheet().getByText(/45,99/)).toBeInTheDocument()
      expect(sheet().getByText(new RegExp(`12/${MONTH.slice(5)}/${MONTH.slice(0, 4)} · Courant`))).toBeInTheDocument()
      expect(sheet().getByText('→ Dépenses courantes › Alimentation › Courses')).toBeInTheDocument()
      expect(sheet().getByText(/La règle « contient CARREFOUR »/)).toBeInTheDocument()
      expect(sheet().getByText('À qualifier')).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Valider' })).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Autre catégorie' })).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Créer une opération' })).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Créer un filtre' })).not.toBeInTheDocument()
      expect(screen.getByText('2 / 4')).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Non reconnues' })).not.toBeInTheDocument()
    })

    test('a line without suggestion offers no « Valider »', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      expect(sheet().getByText('Aucune suggestion pour cette ligne.')).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Autre catégorie' })).toBeInTheDocument()
    })

    test('« Voir la fiche complète » links to the transaction sheet', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      const link = sheet().getByRole('link', { name: 'Voir la fiche complète' })

      expect(link).toHaveAttribute('href', expect.stringContaining('/transactions/'))
      expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('/api/transactions/T1')
    })

    test('« Créer une opération » opens the rule brick filled from the line, applying to the existing lines', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      await userEvent.click(sheet().getByRole('button', { name: 'Créer une opération' }))

      const dialog = await screen.findByRole('dialog', { name: 'Créer une opération' })
      expect(within(dialog).getByLabelText('…ce texte')).toHaveValue('CARREFOUR MARKET')
      expect(within(dialog).getByRole('checkbox', { name: 'Appliquer aux transactions existantes' })).toBeChecked()
    })

    test('the admin extras are one tap away: « Virement interne », « Partagée »', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      expect(sheet().getByRole('button', { name: 'Virement interne' })).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Partagée' })).toBeInTheDocument()
    })

    test('an income offers « Créance / dette » where an expense offers « Partagée »', async () => {
      serve(() => queueOf([line('T6', 'VIR. RECU', '02', 5000)]))
      renderPage()
      await openLine('VIR. RECU')

      expect(sheet().getByRole('button', { name: 'Créance / dette' })).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Partagée' })).not.toBeInTheDocument()
    })
  })

  describe('the fiche of a recognised line', () => {
    test('shows its category, the operation that recognised it and the three gestures', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      expect(shownLine()).toBe('NETFLIX.COM')
      expect(sheet().getByText('Reconnue')).toBeInTheDocument()
      expect(sheet().getByText('Loisirs › Abonnements')).toBeInTheDocument()
      expect(sheet().getByTestId('sheet-operation')).toHaveTextContent('Abonnements')
      expect(sheet().getByRole('button', { name: 'Correct' })).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Corriger' })).toBeInTheDocument()
      expect(sheet().getByRole('link', { name: "Voir l'opération" })).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
    })

    test('« Voir l\'opération » leads to the rule that recognised the line', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      const href = decodeURIComponent(sheet().getByRole('link', { name: "Voir l'opération" }).getAttribute('href') ?? '')

      expect(href).toContain('/categorization_rules/')
      expect(href).toContain('/api/categorization_rules/R1')
    })

    test('« Voir l\'opération » leads to the recurring operations for a series', async () => {
      renderPage()
      await openLine('VIREMENT SALAIRE')

      expect(sheet().getByRole('link', { name: "Voir l'opération" })).toHaveAttribute('href', '/finance/recurring-operations')
    })

    test('a deleted operation offers no link to it', async () => {
      serve(() => queueOf([{ ...NETFLIX, qualifiedBy: 'series', qualifiedBySeriesId: null, qualifiedByRuleId: null, operationLabel: null }]))
      renderPage()
      await openLine('NETFLIX.COM')

      expect(sheet().queryByRole('link', { name: "Voir l'opération" })).not.toBeInTheDocument()
      expect(sheet().getByRole('link', { name: 'Voir la fiche complète' })).toBeInTheDocument()
    })

    test('« Correct » controls the line: it shows as checked and the fiche stays', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      await userEvent.click(sheet().getByRole('button', { name: 'Correct' }))

      await waitFor(() => expect(posts()).toHaveLength(1))
      expect(posts()[0]).toEqual({ url: '/api/finance/review/controls', body: { transactionIds: ['T3'] } })
      expect(await sheet().findByText('Contrôlée')).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Correct' })).not.toBeInTheDocument()
      expect(shownLine()).toBe('NETFLIX.COM')
    })

    test('« Corriger » qualifies the line by hand and the line stays, with its new category', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      await userEvent.click(sheet().getByRole('button', { name: 'Corriger' }))
      expect(sheet().getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
      await userEvent.click(sheet().getByRole('switch', { name: 'Obligatoire' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
      expect(writes('PATCH')[0]).toEqual({
        url: '/api/transactions/T3',
        body: { category: '/api/categories/LOISIRS', qualification: { mandatory: true, kind: 'none' } },
        contentType: 'application/merge-patch+json',
      })
      await waitFor(() => expect(sheet().getByText('Loisirs')).toBeInTheDocument())
      expect(shownLine()).toBe('NETFLIX.COM')
      expect(sheet().queryByRole('button', { name: 'Enregistrer' })).not.toBeInTheDocument()
    })

    test('a refused correction shows the message and keeps the panel open', async () => {
      renderPage()
      await openLine('NETFLIX.COM')
      await userEvent.click(sheet().getByRole('button', { name: 'Corriger' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))

      fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
        init?.method ? answer(false, { detail: 'Unknown category.' }) : answer(true, queueOf([NETFLIX])),
      )
      await userEvent.click(sheet().getByRole('button', { name: 'Enregistrer' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Unknown category.')
      expect(sheet().getByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
    })

    test('a refused control shows its message and keeps the line unchecked', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
        init?.method ? answer(false, { error: 'Transaction not found.' }) : answer(true, queueOf([NETFLIX])),
      )
      await userEvent.click(sheet().getByRole('button', { name: 'Correct' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Transaction not found.')
      expect(sheet().getByRole('button', { name: 'Correct' })).toBeEnabled()
    })
  })

  describe('Précédent / Suivant over the filtered list', () => {
    test('« Suivant » opens the line after, « Précédent » the one before, across the groups', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      await userEvent.click(screen.getByRole('button', { name: 'Suivant' }))
      expect(shownLine()).toBe('VIREMENT SALAIRE')
      expect(screen.getByText('3 / 4')).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Précédent' }))
      await userEvent.click(screen.getByRole('button', { name: 'Précédent' }))
      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')
      expect(screen.getByText('1 / 4')).toBeInTheDocument()
    })

    test('the position follows the filter: 1 / 2 among the lines to qualify', async () => {
      renderPage('/finance/review?state=unrecognised')
      await openLine('CARREFOUR MARKET 1234')

      expect(screen.getByText('2 / 2')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Suivant' })).toBeDisabled()
      await userEvent.click(screen.getByRole('button', { name: 'Précédent' }))
      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')
      expect(screen.getByRole('button', { name: 'Précédent' })).toBeDisabled()
    })

    test('the arrow keys move through the lines, but not while typing in a field', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      await userEvent.keyboard('{ArrowRight}')
      expect(shownLine()).toBe('CARREFOUR MARKET 1234')
      await userEvent.keyboard('{ArrowLeft}')
      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')

      await userEvent.click(sheet().getByRole('button', { name: 'Autre catégorie' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.keyboard('{ArrowRight}')
      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')
    })

    test('the arrow keys do nothing on the list', async () => {
      renderPage()
      await screen.findByRole('region', { name: 'Non reconnues' })

      await userEvent.keyboard('{ArrowRight}')

      expect(screen.queryByRole('region', { name: 'Fiche de la transaction' })).not.toBeInTheDocument()
    })

    test('back to the list keeps the position: the line left is marked and focused', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')
      await userEvent.click(screen.getByRole('button', { name: 'Suivant' }))

      await userEvent.click(screen.getByRole('button', { name: 'Retour à la liste' }))

      const current = await screen.findByRole('button', { name: 'CARREFOUR MARKET 1234' })
      expect(current).toHaveAttribute('aria-current', 'true')
      expect(current).toHaveFocus()
      expect(row('PAIEMENT PAR CARTE X')).not.toHaveAttribute('aria-current')
    })

    test('a wide window shows the list and the fiche side by side, on the first line from the start', async () => {
      setViewportWidth(DESKTOP_WIDTH)
      renderPage()

      expect(await screen.findByRole('region', { name: 'Non reconnues' })).toBeInTheDocument()
      await waitFor(() => expect(shownLine()).toBe('PAIEMENT PAR CARTE X'))
      expect(screen.queryByRole('button', { name: 'Retour à la liste' })).not.toBeInTheDocument()
      expect(row('PAIEMENT PAR CARTE X')).toHaveAttribute('aria-current', 'true')

      await userEvent.click(row('NETFLIX.COM'))
      expect(shownLine()).toBe('NETFLIX.COM')
      expect(row('NETFLIX.COM')).toHaveAttribute('aria-current', 'true')

      await userEvent.click(screen.getByRole('button', { name: 'Précédent' }))
      expect(shownLine()).toBe('VIREMENT SALAIRE')
      expect(screen.getByRole('region', { name: 'Reconnues' })).toBeInTheDocument()
    })
  })

  describe('a gesture on an unrecognised line', () => {
    test('« Valider » files the line under the suggested category: it becomes Reconnue, the fiche stays, the counts follow', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      await userEvent.click(sheet().getByRole('button', { name: 'Valider' }))

      await waitFor(() => expect(posts()).toHaveLength(1))
      expect(posts()[0]).toEqual({
        url: '/api/finance/transactions/T1/sort',
        body: { categoryId: 'COURSES', alwaysForMerchant: false },
      })
      expect(await sheet().findByTestId('review-handled')).toHaveTextContent('Qualifiée')
      expect(sheet().getByTestId('sheet-state')).toHaveTextContent('Reconnue')
      expect(shownLine()).toBe('CARREFOUR MARKET 1234')
      expect(screen.getByText('2 / 4')).toBeInTheDocument()
      // No second read: the list answers from what the owner did.
      expect(queueCalls()).toHaveLength(1)

      await userEvent.click(screen.getByRole('button', { name: 'Retour à la liste' }))
      expect(stateOf('CARREFOUR MARKET 1234')).toBe('Reconnue')
      expect(pill('À qualifier · 1')).toBeInTheDocument()
      expect(pill('Reconnues · 3')).toBeInTheDocument()
      expect(pill('Toutes · 4')).toBeInTheDocument()
    })

    test('the line stays in the À qualifier filter, and « Suivant » opens the next one', async () => {
      renderPage('/finance/review?state=unrecognised')
      await openLine('PAIEMENT PAR CARTE X')
      expect(screen.getByText('1 / 2')).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Suivant' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Valider' }))
      await sheet().findByTestId('review-handled')
      await userEvent.click(screen.getByRole('button', { name: 'Précédent' }))

      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')
      await userEvent.click(sheet().getByRole('button', { name: 'Autre catégorie' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Qualifier' }))
      await sheet().findByTestId('review-handled')

      // Both lines are Reconnue now, both still listed; the pills say so.
      expect(shownLine()).toBe('PAIEMENT PAR CARTE X')
      expect(screen.getByText('1 / 2')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Suivant' }))
      expect(shownLine()).toBe('CARREFOUR MARKET 1234')
      await userEvent.click(screen.getByRole('button', { name: 'Retour à la liste' }))
      expect(rows()).toEqual(['PAIEMENT PAR CARTE X', 'CARREFOUR MARKET 1234'])
      expect(pill('À qualifier · 0')).toBeInTheDocument()
    })

    test('« Autre catégorie » files the line under the chosen category', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      await userEvent.click(sheet().getByRole('button', { name: 'Autre catégorie' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Qualifier' }))

      await waitFor(() => expect(posts()).toHaveLength(1))
      expect(posts()[0]).toEqual({
        url: '/api/finance/transactions/T2/sort',
        body: { categoryId: 'LOISIRS', alwaysForMerchant: false },
      })
      expect(writes('PATCH')).toHaveLength(0)
      expect(await sheet().findByTestId('review-handled')).toHaveTextContent('Qualifiée · Loisirs')
    })

    test('a mandatory or a kind chosen with the category is written once the line is filed', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      await userEvent.click(sheet().getByRole('button', { name: 'Autre catégorie' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
      await userEvent.click(sheet().getByRole('switch', { name: 'Obligatoire' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Qualifier' }))

      await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
      expect(writes('PATCH')[0]).toEqual({
        url: '/api/transactions/T2',
        body: { qualification: { mandatory: true, kind: 'none' } },
        contentType: 'application/merge-patch+json',
      })
    })

    test('the category cannot be confirmed before it is chosen', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      await userEvent.click(sheet().getByRole('button', { name: 'Autre catégorie' }))

      expect(sheet().getByRole('button', { name: 'Qualifier' })).toBeDisabled()
    })

    test('« Toujours pour ce marchand » asks for a rule and says what it filed', async () => {
      serve(() => queueOf([CARREFOUR, MYSTERY]), {
        ...SORTED,
        rule: { id: 'R9', labelPattern: 'CARREFOUR MARKET' },
        ruleCreated: true,
        alsoSorted: 2,
      })
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      await userEvent.click(sheet().getByRole('switch', { name: 'Toujours pour ce marchand' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Valider' }))

      await waitFor(() => expect(posts()).toHaveLength(1))
      expect(posts()[0].body).toEqual({ categoryId: 'COURSES', alwaysForMerchant: true })
      expect(await screen.findByText('Règle créée · 2 autres lignes classées')).toBeInTheDocument()
      // The other lines the rule filed are read back, without moving what is on screen.
      await waitFor(() => expect(queueCalls()).toHaveLength(2))
      expect(shownLine()).toBe('CARREFOUR MARKET 1234')
    })

    test('« Virement interne » marks the line as a transfer between the owner\'s accounts', async () => {
      renderPage()
      await openLine('PAIEMENT PAR CARTE X')

      await userEvent.click(sheet().getByRole('button', { name: 'Virement interne' }))

      await waitFor(() => expect(posts()).toHaveLength(1))
      expect(posts()[0]).toEqual({ url: '/api/finance/transactions/T2/sort', body: { internalTransfer: {} } })
      expect(await sheet().findByTestId('review-handled')).toHaveTextContent('Virement interne')
      expect(sheet().getByTestId('sheet-state')).toHaveTextContent('Reconnue')
    })

    test('a refused answer shows its message and keeps the line unrecognised', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
        init?.method
          ? answer(false, { error: 'This transaction is not waiting to be sorted.' })
          : answer(true, queueOf([CARREFOUR])),
      )
      await userEvent.click(sheet().getByRole('button', { name: 'Valider' }))

      expect(await screen.findByText('This transaction is not waiting to be sorted.')).toBeInTheDocument()
      expect(sheet().getByTestId('sheet-state')).toHaveTextContent('À qualifier')
      expect(sheet().getByRole('button', { name: 'Valider' })).toBeEnabled()
    })

    test('a qualified line can be corrected right away', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')
      await userEvent.click(sheet().getByRole('button', { name: 'Valider' }))
      await sheet().findByTestId('review-handled')

      await userEvent.click(sheet().getByRole('button', { name: 'Corriger' }))
      await userEvent.click(sheet().getByLabelText('Catégorie'))
      await userEvent.click(await screen.findByRole('option', { name: 'Loisirs' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
      expect(writes('PATCH')[0].body).toEqual({ category: '/api/categories/LOISIRS', qualification: { mandatory: false, kind: 'none' } })
      expect(await sheet().findByTestId('review-handled')).toHaveTextContent('Qualifiée · Loisirs')
    })
  })

  describe('the satisfaction', () => {
    test('« Superflue » gives the spend its satisfaction, and pressing it again takes it back', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      await userEvent.click(sheet().getByRole('button', { name: 'Superflue' }))

      await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
      expect(writes('PATCH')[0]).toEqual({
        url: '/api/transactions/T1',
        body: { retrospect: 'avoidable' },
        contentType: 'application/merge-patch+json',
      })
      await userEvent.click(await sheet().findByRole('button', { name: 'Superflue', pressed: true }))
      await waitFor(() => expect(writes('PATCH')).toHaveLength(2))
      expect(writes('PATCH')[1].body).toEqual({ retrospect: 'unrated' })
    })

    test('« À conserver » says the spend was worth it, and the row shows it', async () => {
      renderPage()
      await openLine('NETFLIX.COM')

      await userEvent.click(sheet().getByRole('button', { name: 'À conserver' }))

      await waitFor(() => expect(writes('PATCH')[0]).toMatchObject({ url: '/api/transactions/T3', body: { retrospect: 'keep' } }))
      await userEvent.click(screen.getByRole('button', { name: 'Retour à la liste' }))
      expect(within(row('NETFLIX.COM')).getByText('À conserver')).toBeInTheDocument()
    })

    test('an income offers no satisfaction at all', async () => {
      renderPage()
      await openLine('VIREMENT SALAIRE')

      expect(shownLine()).toBe('VIREMENT SALAIRE')
      expect(sheet().queryByRole('group', { name: 'Satisfaction' })).not.toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'À conserver' })).not.toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Superflue' })).not.toBeInTheDocument()
    })

    test('a refused satisfaction shows its message', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
        init?.method === 'PATCH' ? answer(false, { detail: 'Only a spend can be judged.' }) : answer(true, queueOf([CARREFOUR])),
      )
      await userEvent.click(sheet().getByRole('button', { name: 'Superflue' }))

      expect(await screen.findByText('Only a spend can be judged.')).toBeInTheDocument()
    })
  })

  describe('« Ce virement est » (MAG-453)', () => {
    const FROM_TOM = line('T5', 'VIR. DE TOM MARTIN 12/10', '12', 40000, {
      contact: { id: '01TOM', name: 'Tom' },
      reason: 'peer_transfer',
      asksForPeerKind: true,
      counterpartyLabel: 'VIR. DE TOM MARTIN',
    })
    const ALLOCATION = {
      transactionId: 'T5',
      amountCents: 40000,
      direction: 'incoming',
      canBeAllocated: true,
      peerKind: null,
      contact: { id: '01TOM', name: 'Tom' },
      allocatedCents: 0,
      unallocatedCents: 40000,
      allocations: [],
      candidates: [
        {
          shareId: 'S-GITE',
          peerDebtId: 'D-GITE',
          object: 'Gîte de Crozon',
          contactId: '01TOM',
          contactName: 'Tom',
          amountCents: 20000,
          repaidCents: 0,
          remainingCents: 20000,
          allocatedCents: 0,
          availableCents: 20000,
          fromContact: true,
        },
      ],
      peerDebt: null,
    }

    const withPeerTransfer = (row: Record<string, unknown> = FROM_TOM) => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (url === '/api/finance/transactions/T5/allocation') {
          return init?.method === 'PUT' ? answer(true, { ...ALLOCATION, peerKind: 'repayment' }) : answer(true, ALLOCATION)
        }

        return answer(true, queueOf([row, CARREFOUR]))
      })
    }

    test('a filed transfer with a person asks only « Ce virement est », not its category', async () => {
      withPeerTransfer()
      await renderOpenTom()

      expect(await sheet().findByText('Ce virement est')).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Un remboursement' })).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Autre catégorie' })).not.toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Valider' })).not.toBeInTheDocument()
      expect(sheet().queryByText('Aucune suggestion pour cette ligne.')).not.toBeInTheDocument()
      expect(sheet().getByRole('link', { name: 'Voir la fiche complète' })).toBeInTheDocument()
    })

    test('a filed transfer offers neither « Virement interne » nor « Créer une opération »', async () => {
      withPeerTransfer()
      await renderOpenTom()

      expect(await sheet().findByText('Ce virement est')).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Virement interne' })).not.toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Créer une opération' })).not.toBeInTheDocument()
    })

    test('a transfer still without category asks both', async () => {
      withPeerTransfer({ ...FROM_TOM, reason: 'category' })
      await renderOpenTom()

      expect(await sheet().findByText('Ce virement est')).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Autre catégorie' })).toBeInTheDocument()
      expect(sheet().getByRole('button', { name: 'Virement interne' })).toBeInTheDocument()
    })

    test('a line that does not ask for it shows no « Ce virement est »', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      expect(screen.queryByText('Ce virement est')).not.toBeInTheDocument()
      expect(fetchMock.mock.calls.map(([url]) => url)).not.toContainEqual(expect.stringContaining('/allocation'))
    })

    test('once allocated, the line turns Reconnue and stays on the fiche', async () => {
      withPeerTransfer()
      await renderOpenTom()

      await userEvent.click(await sheet().findByRole('button', { name: 'Un remboursement' }))
      await userEvent.click(sheet().getByRole('checkbox', { name: 'Gîte de Crozon · Tom' }))
      await userEvent.click(sheet().getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(writes('PUT')).toEqual([
          {
            url: '/api/finance/transactions/T5/allocation',
            body: { kind: 'repayment', allocations: [{ shareId: 'S-GITE', amountCents: 20000 }] },
            contentType: 'application/json',
          },
        ]),
      )
      expect(await sheet().findByTestId('review-handled')).toHaveTextContent('Virement précisé')
      expect(shownLine()).toBe('VIR. DE TOM MARTIN 12/10')
      expect(sheet().getByTestId('sheet-state')).toHaveTextContent('Reconnue')
    })

    const renderOpenTom = async () => {
      renderPage()
      await openLine('VIR. DE TOM MARTIN 12/10')
    }
  })

  describe('« Associer à un contact ? »', () => {
    const CONTACTS = [{ id: '01ANNA', name: 'Anna', notes: null }]
    const FROM_TOM = line('T5', 'VIR. DE TOM MARTIN 12/10', '12', 40000, {
      asksForContact: true,
      counterpartyLabel: 'VIR. DE TOM MARTIN',
    })

    const withContacts = (linkRefusal: string | null = null) => {
      let linked: { id: string; name: string } | null = null

      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (url.startsWith('/api/contacts?')) return answer(true, { member: CONTACTS })
        if (url === '/api/contacts' && init?.method === 'POST') {
          return answer(true, { '@id': '/api/contacts/01TOM', id: '01TOM', name: 'Tom', notes: null })
        }
        if (url === '/api/contact_counterparties') {
          if (linkRefusal) return answer(false, { detail: linkRefusal })
          const body = JSON.parse(init?.body as string) as { contact: string }
          linked = { id: body.contact.split('/').pop() as string, name: body.contact.endsWith('01TOM') ? 'Tom' : 'Anna' }

          return answer(true, {})
        }

        // Once linked, the API knows the contact of the line: a second read says so.
        return answer(true, queueOf([linked ? { ...FROM_TOM, asksForContact: false, contact: linked } : FROM_TOM]))
      })
    }

    test('is only asked on a transfer nobody claims', async () => {
      renderPage()
      await openLine('CARREFOUR MARKET 1234')

      expect(screen.queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('a line already attached shows its contact instead', async () => {
      serve(() => queueOf([{ ...FROM_TOM, asksForContact: false, contact: { id: '01TOM', name: 'Tom' } }]))
      renderPage()
      await openLine('VIR. DE TOM MARTIN 12/10')

      expect(sheet().getByText('Tom')).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('« + Créer « Tom » » creates the contact, links the label to it, and the fiche shows Tom', async () => {
      withContacts()
      renderPage()
      await openLine('VIR. DE TOM MARTIN 12/10')

      await userEvent.click(sheet().getByRole('button', { name: 'Associer à un contact ?' }))
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
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(await sheet().findByText('Tom')).toBeInTheDocument()
      expect(sheet().queryByRole('button', { name: 'Associer à un contact ?' })).not.toBeInTheDocument()
    })

    test('picking an existing contact links the label to them', async () => {
      withContacts()
      renderPage()
      await openLine('VIR. DE TOM MARTIN 12/10')

      await userEvent.click(sheet().getByRole('button', { name: 'Associer à un contact ?' }))
      await userEvent.click(await screen.findByRole('button', { name: /Anna/ }))

      await waitFor(() =>
        expect(posts()).toEqual([
          { url: '/api/contact_counterparties', body: { contact: '/api/contacts/01ANNA', label: 'VIR. DE TOM MARTIN' } },
        ]),
      )
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(await sheet().findByText('Anna')).toBeInTheDocument()
    })

    test('a refused link shows the API\'s message and leaves the picker open', async () => {
      withContacts('« VIR. DE TOM MARTIN » is already linked to Anna.')
      renderPage()
      await openLine('VIR. DE TOM MARTIN 12/10')

      await userEvent.click(sheet().getByRole('button', { name: 'Associer à un contact ?' }))
      await userEvent.click(await screen.findByRole('button', { name: /Anna/ }))

      expect(await screen.findByText('« VIR. DE TOM MARTIN » is already linked to Anna.')).toBeInTheDocument()
      expect(screen.getByRole('dialog', { name: 'Associer à un contact' })).toBeInTheDocument()
    })
  })
})

describe('the three Revue pages of before', () => {
  test.each(Object.entries(LEGACY_REVIEW_REDIRECTS))('%s opens the list as that page was', async (from, to) => {
    render(
      <MemoryRouter initialEntries={[from]}>
        <Routes>
          {Object.entries(LEGACY_REVIEW_REDIRECTS).map(([path]) => (
            <Route key={path} path={path} element={<Navigate replace to={LEGACY_REVIEW_REDIRECTS[path as keyof typeof LEGACY_REVIEW_REDIRECTS]} />} />
          ))}
          <Route path="/finance/review" element={<Where />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByTestId('where')).toHaveTextContent(to)
  })

  test('the addresses are the ones the ticket names', () => {
    expect(LEGACY_REVIEW_REDIRECTS).toEqual({
      '/finance/to-sort': '/finance/review?state=unrecognised&period=all',
      '/finance/to-control': '/finance/review?state=recognised&period=since_review',
      '/finance/monthly-review': '/finance/review?state=all&period=month',
    })
  })
})
