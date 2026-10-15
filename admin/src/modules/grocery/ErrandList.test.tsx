import { afterEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { ErrandList } from './ErrandList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const STORES = [
  { id: '/api/stores/01LIDL', name: 'Lidl' },
  { id: '/api/stores/01CARREFOUR', name: 'Carrefour' },
]

const ERRANDS = [
  {
    id: '/api/errands/01A',
    store: '/api/stores/01CARREFOUR',
    storeName: 'Carrefour',
    endedAt: '2026-10-14T18:05:00+02:00',
    itemCount: 2,
    items: [
      { id: '1', label: 'Pâtes', quantity: 2, unit: 'pack', packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g' },
      { id: '2', label: 'Tomates', quantity: 3, unit: 'piece' },
    ],
  },
  { id: '/api/errands/01B', store: null, storeName: null, endedAt: '2026-09-02T09:00:00+02:00', itemCount: 1, items: [] },
]

const renderPage = (getList: DataProvider['getList']) =>
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({
          getList,
          getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
        })}
      >
        <ResourceContextProvider value="errands">
          <ErrandList />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

const listing = () =>
  vi.fn((resource: string) =>
    Promise.resolve(resource === 'stores' ? { data: STORES, total: STORES.length } : { data: ERRANDS, total: ERRANDS.length }),
  )

describe('ErrandList', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('lists the past errands with their date, store and number of articles', async () => {
    renderPage(listing() as unknown as DataProvider['getList'])

    expect(await screen.findByText('Carrefour')).toBeInTheDocument()
    expect(screen.getByText('Sans magasin')).toBeInTheDocument()
    expect(screen.getByText(/14 octobre 2026/)).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  test('clicking a row shows the articles bought', async () => {
    renderPage(listing() as unknown as DataProvider['getList'])

    await userEvent.click(await screen.findByText('Carrefour'))

    const items = await screen.findByRole('table', { name: 'Articles achetés' })
    expect(within(items).getByText('Pâtes')).toBeInTheDocument()
    expect(within(items).getByText('2 paquets · paquet de 500 g')).toBeInTheDocument()
    expect(within(items).getByText('3 pièces')).toBeInTheDocument()
  })

  test('filtering by store and month asks the API for them', async () => {
    const getList = listing()
    renderPage(getList as unknown as DataProvider['getList'])
    await screen.findByText('Carrefour')

    await userEvent.click(screen.getByLabelText('Magasin'))
    await userEvent.click(await screen.findByRole('option', { name: 'Lidl' }))
    await userEvent.type(screen.getByLabelText('Mois'), '2026-10')

    await waitFor(() =>
      expect(getList).toHaveBeenLastCalledWith(
        'errands',
        expect.objectContaining({
          filter: {
            store: '/api/stores/01LIDL',
            'endedAt[after]': '2026-10-01',
            'endedAt[strictly_before]': '2026-11-01',
          },
        }),
      ),
    )
  })

  test('an empty history says how to fill it', async () => {
    renderPage(
      vi.fn((resource: string) =>
        Promise.resolve(resource === 'stores' ? { data: STORES, total: 2 } : { data: [], total: 0 }),
      ) as unknown as DataProvider['getList'],
    )

    expect(await screen.findByText('Aucune course passée pour l’instant')).toBeInTheDocument()
  })

  test('a failed read does not show an empty history', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    renderPage(
      vi.fn((resource: string) =>
        resource === 'stores' ? Promise.resolve({ data: STORES, total: 2 }) : Promise.reject(new Error('boom')),
      ) as unknown as DataProvider['getList'],
    )

    await waitFor(() => expect(screen.queryByText('Carrefour')).not.toBeInTheDocument())
    expect(screen.queryByText('Aucune course passée pour l’instant')).not.toBeInTheDocument()
  })
})
