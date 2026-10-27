import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { EnvelopeList, EnvelopeOutside } from './EnvelopeList'
import { CATEGORIES, STATUS, line } from './envelopeTreeFixtures'
import type { BudgetStatus } from './useBudgetStatus'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

let served: BudgetStatus = STATUS
const getList = vi.fn(() => Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length }))
const statusReads = () =>
  (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter(([url]) => String(url).includes('/api/finance/budget-status'))

const renderScreen = (Screen: () => React.JSX.Element = EnvelopeList) =>
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({
          getList: getList as unknown as DataProvider['getList'],
          getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
          getOne: (() => Promise.resolve({ data: { id: 'stub' } })) as unknown as DataProvider['getOne'],
        })}
      >
        <ResourceContextProvider value="envelopes">
          <Screen />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

describe('EnvelopeList — live updates from Mercure', () => {
  beforeEach(() => {
    served = STATUS
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve(
              String(url).includes('/to-sort')
                ? { total: 3, totalCents: 5799, transactions: [] }
                : String(url).includes('/envelope-adjustments')
                  ? { month: '2026-10', targetMonth: '2026-11', suggestionCount: 0, envelopes: [] }
                  : served,
            ),
        }),
      ),
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  const treeNode = async (name: string) =>
    (await screen.findAllByTestId('envelope-node')).find((n) => n.dataset.category === name) as HTMLElement

  test('draws the tree of the period, with one read of the status', async () => {
    renderScreen()

    expect(await treeNode('Obsydian')).toHaveAttribute('data-has-envelope', 'false')
    expect(statusReads()[0][0]).toMatch(/year=\d{4}&month=\d{1,2}/)
    expect(statusReads()).toHaveLength(1)
  })

  test('Mois repeats nothing of the dashboard: no « Budgets » title, no gauges above the tree, no total, no rings', async () => {
    renderScreen()
    await treeNode('Obsydian')

    expect(screen.queryByRole('heading', { name: 'Budgets' })).toBeNull()
    expect(screen.queryByTestId('budget-gauge')).toBeNull()
    expect(screen.queryByText(/^Total :/)).toBeNull()
    expect(screen.queryByTestId('outside-monthly-budget')).toBeNull()
    expect(screen.queryByTestId('scores-token')).toBeNull()
    expect(screen.queryByText('Hors budget mensuel')).toBeNull()
  })

  test('Mois keeps the compact toolbar: month selector, roll-over, « Ajuster » and the lines to qualify', async () => {
    renderScreen()
    await treeNode('Obsydian')

    expect(screen.getByLabelText('Mois')).toBeInTheDocument()
    expect(screen.getByLabelText('Année')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reconduire le mois précédent' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajuster' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /À qualifier/ })).toBeInTheDocument()
  })

  test('Hors budget lists the annual and one-off envelopes of the budget status, and no tree', async () => {
    renderScreen(EnvelopeOutside)

    const entries = await screen.findAllByTestId('envelope-outside-node')
    expect(entries.map((entry) => entry.dataset.category)).toEqual(['Voyages', 'Hellfest 2026'])
    expect(screen.queryByTestId('envelope-node')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Ajuster' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reconduire le mois précédent' })).toBeNull()
  })

  test('the budget links to the lines still waiting for a qualification', async () => {
    renderScreen()

    expect(await screen.findByRole('link', { name: /À qualifier · 3 · 57,99/ })).toHaveAttribute('href', '/finance/review?state=unrecognised&period=all')
  })

  test('« Ajuster » opens the adjustments of the period on screen, and only then', async () => {
    renderScreen()
    await treeNode('Obsydian')
    expect(screen.queryByTestId('envelope-adjustments')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Ajuster' }))

    expect(await screen.findByTestId('envelope-adjustments')).toBeInTheDocument()
    const now = new Date()
    const month = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
    await waitFor(() =>
      expect(
        (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.some(([url]) => String(url) === `/api/finance/envelope-adjustments?month=${month}`),
      ).toBe(true),
    )
  })

  test('an envelope created elsewhere appears in the tree', async () => {
    renderScreen()
    const obsydian = await treeNode('Obsydian')
    expect(within(obsydian).getByText('Sans enveloppe')).toBeInTheDocument()

    served = {
      ...STATUS,
      budgets: [
        ...STATUS.budgets,
        line({ id: 'e-obsydian', categoryId: 'obsydian', categoryName: 'Obsydian', categoryPath: 'Loisirs › Bars › Obsydian', parentEnvelopeId: 'e-bars', amountCents: 10000 }),
      ],
    }
    await emitMercure('/api/envelopes/{id}', { '@id': '/api/envelopes/e-obsydian' })

    await waitFor(async () => expect(await treeNode('Obsydian')).toHaveAttribute('data-has-envelope', 'true'))
    expect(within(await treeNode('Obsydian')).getByTestId('envelope-node-amount')).toHaveTextContent(/0,00\s€\s\/\s100,00\s€/)
  })

  test('a spend moves the consumption shown in the tree', async () => {
    renderScreen()
    expect(within(await treeNode('Loisirs')).getByTestId('envelope-node-amount')).toHaveTextContent(/40,00\s€\s\/\s300,00\s€/)

    served = {
      ...STATUS,
      budgets: STATUS.budgets.map((l) => (l.id === 'e-loisirs' ? { ...l, consumedCents: 9000 } : l)),
    }
    await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/t1' })

    await waitFor(async () =>
      expect(within(await treeNode('Loisirs')).getByTestId('envelope-node-amount')).toHaveTextContent(/90,00\s€\s\/\s300,00\s€/),
    )
  })

  test('a category created elsewhere is read again for the tree', async () => {
    renderScreen()
    await treeNode('Obsydian')
    const reads = getList.mock.calls.length

    await emitMercure('/api/categories/{id}', { '@id': '/api/categories/new' })

    await waitFor(() => expect(getList.mock.calls.length).toBeGreaterThan(reads))
  })

  test('an envelope deleted elsewhere leaves the tree', async () => {
    renderScreen()
    expect(await treeNode('Cinéma')).toHaveAttribute('data-has-envelope', 'true')

    served = { ...STATUS, budgets: STATUS.budgets.filter((l) => l.id !== 'e-cinema') }
    await emitMercure('/api/envelopes/{id}', { '@id': '/api/envelopes/e-cinema', deleted: true })

    await waitFor(async () => expect(await treeNode('Cinéma')).toHaveAttribute('data-has-envelope', 'false'))
  })
})
