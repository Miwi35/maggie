import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, memoryStore, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { CategoryList } from './CategoryList'
import { CategoryCreate } from './CategoryCreate'

const CATEGORIES = [
  { id: '/api/categories/1', name: 'Alimentation', obligation: 'mandatory', color: '#4CAF50', icon: 'shopping-cart' },
  {
    id: '/api/categories/2',
    name: 'Loyers perçus',
    obligation: 'income',
    passiveIncome: true,
  },
]

const TREE = [
  { id: '/api/categories/obsydian', name: 'Obsydian', path: 'Loisirs › Bars › Obsydian', depth: 3, parent: '/api/categories/bars', obligation: 'optional' },
  { id: '/api/categories/courses', name: 'Courses', path: 'Courses', depth: 1, parent: null, obligation: 'mandatory' },
  { id: '/api/categories/bars', name: 'Bars', path: 'Loisirs › Bars', depth: 2, parent: '/api/categories/loisirs', obligation: 'optional' },
  { id: '/api/categories/loisirs', name: 'Loisirs', path: 'Loisirs', depth: 1, parent: null, obligation: 'optional' },
]

const RULES = [
  {
    id: '/api/categorization_rules/1',
    labelPattern: 'CARREFOUR',
    matchType: 'contains',
    direction: 'debit',
    priority: 10,
    isActive: true,
    category: '/api/categories/1',
  },
]

const dataProvider = testDataProvider({
  getList: ((resource: string) =>
    Promise.resolve(
      resource === 'categorization_rules'
        ? { data: RULES, total: RULES.length }
        : { data: CATEGORIES, total: CATEGORIES.length },
    )) as unknown as DataProvider['getList'],
  getOne: (() => Promise.resolve({ data: CATEGORIES[0] })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
})

const renderPage = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="categories">
        <CategoryList />
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('CategoryList', () => {
  test('shows the categories and offers the rules alongside them', async () => {
    renderPage()

    expect(await screen.findByRole('tab', { name: 'Catégories' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Règles de catégorisation' })).toBeInTheDocument()
    expect(await screen.findByText('Alimentation')).toBeInTheDocument()
  })

  test('a row shows the colour and the icon the category was given, not a hex code', async () => {
    renderPage()

    await screen.findByText('Alimentation')
    const glyphs = screen.getAllByTestId('category-glyph')
    const colored = glyphs.find((glyph) => glyph.dataset.color === '#4CAF50')

    expect(colored).toBeDefined()
    expect(colored?.dataset.icon).toBe('shopping-cart')
    expect(colored).toHaveStyle({ backgroundColor: 'rgb(76, 175, 80)' })
    expect(screen.queryByText('#4CAF50')).not.toBeInTheDocument()
    expect(glyphs.some((glyph) => glyph.dataset.color === '' && glyph.dataset.icon === '')).toBe(true)
    expect(screen.getByRole('cell', { name: 'Alimentation' })).toBeInTheDocument()
  })

  /** Which categories feed the independence counter has to be readable here. */
  test('a category declared a rente is marked as one', async () => {
    renderPage()

    expect(await screen.findByRole('cell', { name: 'Rente' })).toBeInTheDocument()
    // MAG-479: no nature, no obligation column.
    expect(screen.queryByRole('columnheader', { name: 'Obligation' })).not.toBeInTheDocument()
    expect(screen.queryByText('Obligatoire')).not.toBeInTheDocument()
  })

  test('every tab opens onto something — an empty panel is a dead end', async () => {
    const user = userEvent.setup()
    // The suggestions panel asks the API for itself.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({ suggestions: [] }) } as Response),
    )

    renderPage()

    await user.click(await screen.findByRole('tab', { name: 'Suggestions' }))

    expect(await screen.findByText(/Rien à proposer/)).toBeInTheDocument()

    vi.unstubAllGlobals()
  })

  test('switching to the rules tab lists the rules', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('tab', { name: 'Règles de catégorisation' }))

    expect(await screen.findByText('CARREFOUR')).toBeInTheDocument()
    expect(screen.getByText('Appliquer les règles')).toBeInTheDocument()
  })
})

describe('CategoryList, three levels', () => {
  const treeProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: TREE, total: TREE.length })) as unknown as DataProvider['getList'],
    getMany: (() => Promise.resolve({ data: TREE })) as unknown as DataProvider['getMany'],
  })

  const renderTree = () =>
    render(
      <MemoryRouter>
        <AdminContext dataProvider={treeProvider}>
          <ResourceContextProvider value="categories">
            <Routes>
              <Route path="/" element={<CategoryList />} />
              <Route path="/categories/create" element={<CategoryCreate />} />
            </Routes>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

  const depthOf = (name: string) => screen.getByText(name).closest('[data-depth]')?.getAttribute('data-depth')

  test('shows Loisirs › Bars › Obsydian as a tree, each under its parent', async () => {
    renderTree()

    await screen.findByText('Obsydian')
    const names = screen.getAllByText(/^(Courses|Loisirs|Bars|Obsydian)$/).map((el) => el.textContent)

    expect(names).toEqual(['Courses', 'Loisirs', 'Bars', 'Obsydian'])
    expect([depthOf('Courses'), depthOf('Loisirs'), depthOf('Bars'), depthOf('Obsydian')]).toEqual(['1', '1', '2', '3'])
  })

  test('offers to create under levels 1 and 2, never under level 3', async () => {
    renderTree()

    await screen.findByText('Obsydian')

    expect(screen.getByRole('link', { name: 'Ajouter une sous-catégorie à Loisirs' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ajouter une sous-catégorie à Bars' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Ajouter une sous-catégorie à Obsydian' })).not.toBeInTheDocument()
  })

  test('creating under Bars opens the form with Loisirs › Bars as the parent', async () => {
    const user = userEvent.setup()
    renderTree()

    await user.click(await screen.findByRole('link', { name: 'Ajouter une sous-catégorie à Bars' }))

    expect(await screen.findByLabelText('Rattacher à une catégorie')).toHaveValue('Loisirs › Bars')
  })
})

/**
 * The list screen of a category used to be paginated: react-admin kept the
 * page size and the sort the owner left it with, and `perPage`/`sort` on the
 * tree list are only defaults. With the pager gone, the rows past the stored
 * page size were hidden for good — the newest categories first (MAG-503).
 */
describe('CategoryList, parameters left by the paginated list', () => {
  const owned = Array.from({ length: 30 }, (_, index) => ({
    id: `/api/categories/${String(index).padStart(2, '0')}`,
    name: `Catégorie ${String(index).padStart(2, '0')}`,
    obligation: 'optional',
    parent: null,
  }))

  const pagedProvider = testDataProvider({
    getList: ((_resource: string, params: { pagination: { page: number; perPage: number }; sort: { field: string; order: string } }) => {
      const sign = params.sort.order === 'DESC' ? -1 : 1
      const sorted = [...owned].sort((a, b) => sign * String(a[params.sort.field as 'id']).localeCompare(String(b[params.sort.field as 'id'])))
      const start = (params.pagination.page - 1) * params.pagination.perPage

      return Promise.resolve({ data: sorted.slice(start, start + params.pagination.perPage), total: owned.length })
    }) as unknown as DataProvider['getList'],
  })

  test('shows every category whatever page size and sort the old list stored', async () => {
    render(
      <MemoryRouter>
        <AdminContext
          dataProvider={pagedProvider}
          store={memoryStore({ 'categories.listParams': { page: 1, perPage: 25, sort: 'id', order: 'DESC', filter: {} } })}
        >
          <ResourceContextProvider value="categories">
            <CategoryList />
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Catégorie 29')).toBeInTheDocument()
    expect(screen.getByText('Catégorie 00')).toBeInTheDocument()
    expect(screen.getAllByText(/^Catégorie \d\d$/)).toHaveLength(30)
  })
})
