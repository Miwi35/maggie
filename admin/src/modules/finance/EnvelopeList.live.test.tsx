import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { EnvelopeList } from './EnvelopeList'
import { CATEGORIES, STATUS, line } from './envelopeTreeFixtures'
import type { BudgetStatus } from './useBudgetStatus'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)
vi.mock('./FinanceScoresSection', () => ({
  FinanceScoresSection: ({ refreshToken }: { refreshToken?: number }) => <span data-testid="scores-token">{refreshToken}</span>,
}))

let served: BudgetStatus = STATUS
const getList = vi.fn(() => Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length }))
const statusReads = () =>
  (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter(([url]) => String(url).includes('/api/finance/budget-status'))

const renderScreen = () =>
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
          <EnvelopeList />
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
            Promise.resolve(String(url).includes('/to-sort') ? { total: 3, totalCents: 5799, transactions: [] } : served),
        }),
      ),
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  const treeNode = async (name: string) =>
    (await screen.findAllByTestId('envelope-node')).find((n) => n.dataset.category === name) as HTMLElement

  test('draws the gauges and the tree of the same period', async () => {
    renderScreen()

    expect(await screen.findAllByTestId('budget-gauge')).not.toHaveLength(0)
    expect(await treeNode('Obsydian')).toHaveAttribute('data-has-envelope', 'false')
    expect(statusReads()[0][0]).toMatch(/year=\d{4}&month=\d{1,2}/)
    // One read feeds both: the panel and the tree.
    expect(statusReads()).toHaveLength(1)
  })

  test('the budget links to the lines still waiting for a qualification', async () => {
    renderScreen()

    expect(await screen.findByRole('link', { name: /À qualifier · 3 · 57,99/ })).toHaveAttribute('href', '/finance/to-sort')
  })

  test('an envelope created elsewhere appears in the tree and in the gauges', async () => {
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
    expect(screen.getAllByTestId('budget-gauge').map((g) => g.dataset.category)).toContain('Obsydian')
  })

  test('a spend moves the consumption shown in the tree', async () => {
    renderScreen()
    expect(within(await treeNode('Loisirs')).getByTestId('envelope-node-amount')).toHaveTextContent(/40,00\s€\s\/\s300,00\s€/)

    served = {
      ...STATUS,
      budgets: STATUS.budgets.map((l) => (l.id === 'e-loisirs' ? { ...l, consumedCents: 9000 } : l)),
    }
    expect(screen.getByTestId('scores-token')).toHaveTextContent('0')
    await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/t1' })

    await waitFor(async () =>
      expect(within(await treeNode('Loisirs')).getByTestId('envelope-node-amount')).toHaveTextContent(/90,00\s€\s\/\s300,00\s€/),
    )
    expect(screen.getByTestId('scores-token')).toHaveTextContent('1')
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
