import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { CategoryList } from './CategoryList'

const CATEGORIES = [
  { id: '/api/categories/1', name: 'Alimentation', obligation: 'mandatory' },
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
