import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { GroceryListView } from './GroceryListView'
import { stubRecurringApi } from '../../test/recurringApi'

// MAG-627 — Courses › Liste to the letter of the mockup: a full page of store blocks, one ⋮ menu.

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
const mockNotify = vi.fn()
let dataProviderMock: { getList: typeof mockGetList; getOne: typeof mockGetOne } | undefined
vi.mock('react-admin', () => ({
  useDataProvider: () => (dataProviderMock ??= { getList: mockGetList, getOne: mockGetOne }),
  useNotify: () => mockNotify,
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const CARREFOUR = { id: '01CARREFOUR', name: 'Carrefour', visitOrder: 1 }
const PRIMEUR = { id: '01PRIMEUR', name: 'Primeur', visitOrder: 2 }
const DAIRY = { id: 'a-dairy', name: 'Crèmerie', position: 1 }
const FRUITS = { id: 'a-fruits', name: 'Fruits et légumes', position: 1 }

const line = (id: string, label: string, extra: Record<string, unknown>) => ({
  id,
  '@id': `/api/grocery_items/${id}`,
  label,
  checked: false,
  source: 'manual',
  position: 0,
  ...extra,
})

const LIST = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    line('milk', 'Lait', { quantity: 2, unit: 'l', store: CARREFOUR, aisle: DAIRY }),
    line('apples', 'Pommes', { quantity: 6, unit: 'piece', store: PRIMEUR, aisle: FRUITS, checked: true }),
  ],
}

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const renderPage = () => {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/grocery']}>
      <Routes>
        <Route path="/grocery" element={<GroceryListView />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )

  return user
}

const openActions = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole('button', { name: "Plus d'actions" }))
}

describe('GroceryListView — the page of the mockup', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({}) } as Response)))
    localStorage.setItem('token', 'test-jwt')
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve(resource === 'grocery_lists' ? { data: [LIST], total: 1 } : { data: [], total: 0 }),
    )
    mockGetOne.mockResolvedValue({ data: LIST })
  })

  test('draws two stores as full-width blocks, each with its aisle, its lines and their quantities', async () => {
    renderPage()
    await screen.findByText('Lait')

    const page = screen.getByTestId('grocery-page')
    expect(getComputedStyle(page).maxWidth).not.toBe('800px')
    expect(page.querySelector('.MuiTabs-root')).toBeNull()
    expect(getComputedStyle(screen.getByTestId('grocery-stores')).display).toBe('grid')

    const [first, second] = screen.getAllByTestId('grocery-store-group')
    expect(first).toHaveAttribute('data-store', 'Carrefour')
    expect(within(first).getByText('0/1')).toBeInTheDocument()
    expect(within(within(first).getByTestId('grocery-aisle')).getByTestId('grocery-aisle-heading')).toHaveTextContent('Crèmerie')
    expect(within(first).getByText('Lait')).toBeInTheDocument()
    expect(within(first).getByTestId('quantity-hint')).toHaveTextContent('2 l')

    expect(second).toHaveAttribute('data-store', 'Primeur')
    expect(within(second).getByText('1/1')).toBeInTheDocument()
    expect(within(second).getByTestId('grocery-aisle-heading')).toHaveTextContent('Fruits et légumes')
    expect(within(second).getByText('Pommes')).toBeInTheDocument()
    expect(within(second).getByTestId('quantity-hint')).toHaveTextContent('6')
  })

  test('draws no title of its own: the page heading comes from the navigation', async () => {
    renderPage()
    await screen.findByText('Lait')

    expect(screen.queryByText('Ma liste de courses')).toBeNull()
    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Ajouter' })).toBeNull()
  })

  test('« + Article » opens the add dialog', async () => {
    const user = renderPage()

    await user.click(await screen.findByRole('button', { name: '+ Article' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Ajouter un article')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Article')).toBeInTheDocument()
  })

  test('the ⋮ menu offers « Produit récurrent », « Terminer les courses » and « Courses passées »', async () => {
    const user = renderPage()
    await screen.findByText('Lait')
    await openActions(user)

    const entries = screen.getAllByRole('menuitem').map((item) => item.textContent)
    expect(entries).toEqual(['Produit récurrent', 'Terminer les courses', 'Courses passées'])
    expect(screen.getByRole('menuitem', { name: 'Courses passées' })).toHaveAttribute('href', '/errands')
  })

  test('« Produit récurrent » opens the recurring products in a dialog', async () => {
    stubRecurringApi([])
    const user = renderPage()
    await screen.findByText('Lait')
    await openActions(user)

    await user.click(screen.getByRole('menuitem', { name: 'Produit récurrent' }))

    const dialog = await screen.findByRole('dialog', { name: 'Produits récurrents' })
    expect(within(dialog).getByTestId('recurring-tab')).toBeInTheDocument()
  })

  test('« Terminer les courses » ends the errand', async () => {
    const user = renderPage()
    await screen.findByText('Lait')
    await openActions(user)

    await user.click(screen.getByRole('menuitem', { name: 'Terminer les courses' }))

    await waitFor(() =>
      expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/grocery/end-errand'))).toBe(true),
    )
    expect(await screen.findByRole('dialog', { name: 'Articles restants' })).toBeInTheDocument()
  })

  test('« Terminer les courses » is disabled while nothing is ticked', async () => {
    const untouched = { ...LIST, items: LIST.items.map((item) => ({ ...item, checked: false })) }
    mockGetList.mockResolvedValue({ data: [untouched], total: 1 })
    mockGetOne.mockResolvedValue({ data: untouched })
    const user = renderPage()
    await screen.findByText('Lait')
    await openActions(user)

    expect(screen.getByRole('menuitem', { name: 'Terminer les courses' })).toHaveAttribute('aria-disabled', 'true')
  })

  test('« Courses passées » goes to the past errands', async () => {
    const user = renderPage()
    await screen.findByText('Lait')
    await openActions(user)

    await user.click(screen.getByRole('menuitem', { name: 'Courses passées' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/errands')
  })
})
