import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AdminContext, RecordContextProvider, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { EnvelopeForm } from './EnvelopeForm'
import type { BudgetLine } from './useBudgetStatus'

const save = vi.fn()
vi.mock('./useEnvelopeSplit', () => ({
  useEnvelopeSplit: () => ({ save, saving: false }),
}))

const line = (overrides: Partial<BudgetLine>): BudgetLine => ({
  id: 'x',
  categoryId: 'c',
  categoryName: 'X',
  categoryPath: 'X',
  parentEnvelopeId: null,
  mode: 'monthly',
  amountCents: 10000,
  currency: 'EUR',
  year: 2026,
  month: 10,
  spentCents: 0,
  committedCents: 0,
  plannedCents: 0,
  toArbitrateCents: 0,
  allocatedToChildrenCents: null,
  freeCents: null,
  consumedCents: 0,
  remainingCents: 0,
  availableCents: 0,
  isOverspent: false,
  isOvercommitted: false,
  ...overrides,
})

const STATUS = {
  year: 2026,
  month: 10,
  budgets: [
    line({ id: 'loisirs', categoryName: 'Loisirs', amountCents: 30000, allocatedToChildrenCents: 15000, freeCents: 15000 }),
    line({ id: 'bars', categoryName: 'Bars', parentEnvelopeId: 'loisirs', amountCents: 10000 }),
    line({ id: 'cinema', categoryName: 'Cinéma', parentEnvelopeId: 'loisirs', amountCents: 5000 }),
  ],
}

const dataProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
  getOne: (() => Promise.resolve({ data: {} })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
})

const renderEdit = (id: string, amountCents: number) =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="envelopes">
        <RecordContextProvider
          value={{ id: `/api/envelopes/${id}`, category: '/api/categories/1', mode: 'monthly', amountCents, currency: 'EUR', year: 2026, month: 10 }}
        >
          <EnvelopeForm />
        </RecordContextProvider>
      </ResourceContextProvider>
    </AdminContext>,
  )

const amountOf = (category: string) =>
  screen.getAllByTestId('split-amount').find((el) => el.getAttribute('data-category') === category)!

describe('EnvelopeForm — the ruler of a mother envelope', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    save.mockResolvedValue({ ok: true })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(STATUS) }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('shows the ruler under the amount, with a handle between the daughters', async () => {
    renderEdit('loisirs', 30000)

    expect(await screen.findByText('Répartition entre les sous-catégories')).toBeInTheDocument()
    expect(amountOf('Bars')).toHaveTextContent('Bars 100,00')
    expect(amountOf('Cinéma')).toHaveTextContent('Cinéma 50,00')
    expect(amountOf('libre')).toHaveTextContent('Libre 150,00')
    expect(screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' })).toBeInTheDocument()
  })

  test('moving the handle and saving shares the mother in one call', async () => {
    renderEdit('loisirs', 30000)
    const handle = await screen.findByRole('slider', { name: 'Frontière entre Bars et Cinéma' })

    for (let i = 0; i < 2; i++) {
      fireEvent.keyDown(handle, { key: 'ArrowRight', shiftKey: true })
    }
    expect(amountOf('Bars')).toHaveTextContent('Bars 120,00')
    expect(amountOf('Cinéma')).toHaveTextContent('Cinéma 30,00')

    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la répartition' }))

    await waitFor(() =>
      expect(save).toHaveBeenCalledWith('loisirs', [
        { envelopeId: 'bars', amountCents: 12000 },
        { envelopeId: 'cinema', amountCents: 3000 },
      ]),
    )
  })

  test('says nothing was changed when the API refuses the split', async () => {
    save.mockResolvedValue({ ok: false, refused: true })
    renderEdit('loisirs', 30000)
    const handle = await screen.findByRole('slider', { name: 'Frontière entre Bars et Cinéma' })

    fireEvent.keyDown(handle, { key: 'ArrowRight', shiftKey: true })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la répartition' }))

    expect(await screen.findByText(/rien n'a été modifié/)).toBeInTheDocument()
    expect(amountOf('Bars')).toHaveTextContent('Bars 100,00')
  })

  test('has no ruler for an envelope without daughters', async () => {
    renderEdit('bars', 10000)

    await screen.findByText('Ce que vous budgétez')
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByText('Répartition entre les sous-catégories')).not.toBeInTheDocument()
    expect(screen.queryAllByRole('slider')).toHaveLength(0)
  })
})
