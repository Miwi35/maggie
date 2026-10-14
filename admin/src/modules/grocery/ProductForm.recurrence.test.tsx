import { describe, test, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Create, Edit, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ProductForm } from './ProductForm'
import { stubRecurringApi } from '../../test/recurringApi'
import type { StubMember } from '../../test/recurringApi'

// MAG-369 — « Récurrent » on the product sheet.

const mockNotify = vi.fn()
vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  useNotify: () => mockNotify,
}))

const eventSources: { url: string; onmessage: ((event: MessageEvent) => void) | null; close: () => void }[] = []
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    eventSources.push(this)
  }
}

const ULID = '01M3VA6TKHF1CB6GMPSRV8WE7R'
const product = { id: `/api/products/${ULID}`, name: 'Lait', category: 'dairy' }
const recurringLait: StubMember = { id: 'R1', product: `/api/products/${ULID}`, label: 'Lait', frequency: 'weekly', nextDueAt: '2099-01-08' }

const dataProvider = () =>
  testDataProvider({
    getOne: vi.fn().mockResolvedValue({ data: product }),
    getList: vi.fn().mockResolvedValue({ data: [], total: 0 }),
    getMany: vi.fn().mockResolvedValue({ data: [] }),
  })

const renderEdit = () =>
  render(
    <MemoryRouter initialEntries={['/products/' + encodeURIComponent(product.id)]}>
      <AdminContext dataProvider={dataProvider()}>
        <ResourceContextProvider value="products">
          <Routes>
            <Route path="/products/:id" element={<Edit mutationMode="pessimistic"><ProductForm /></Edit>} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

const setting = () => screen.findByRole('combobox', { name: 'Récurrent' })

describe('ProductForm — « Récurrent »', () => {
  beforeEach(() => {
    mockNotify.mockClear()
    eventSources.length = 0
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  })

  test('says « Non » for a product that is not recurring', async () => {
    stubRecurringApi([])
    renderEdit()

    await waitFor(async () => expect(await setting()).toHaveTextContent('Non'))
  })

  test('shows the frequency the product already has', async () => {
    stubRecurringApi([recurringLait])
    renderEdit()

    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les semaines'))
  })

  test('choosing a frequency creates the recurring item of this product', async () => {
    const user = userEvent.setup()
    const api = stubRecurringApi([])
    renderEdit()

    await user.click(await setting())
    await user.click(await screen.findByRole('option', { name: 'Tous les mois' }))

    await waitFor(() => expect(api.writes).toHaveLength(1))
    expect(api.writes[0]).toMatchObject({ method: 'POST', body: { product: `/api/products/${ULID}`, frequency: 'monthly' } })
    await waitFor(async () => expect(await setting()).toHaveTextContent('Tous les mois'))
  })

  test('choosing another frequency changes the existing item, never adds one', async () => {
    const user = userEvent.setup()
    const api = stubRecurringApi([recurringLait])
    renderEdit()
    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les semaines'))

    await user.click(await setting())
    await user.click(await screen.findByRole('option', { name: 'Toutes les 2 semaines' }))

    await waitFor(() => expect(api.writes).toHaveLength(1))
    expect(api.writes[0]).toMatchObject({ method: 'PATCH', url: expect.stringMatching(/\/R1$/), body: { frequency: 'biweekly' } })
    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les 2 semaines'))
  })

  test('choosing « Non » deletes the recurring item', async () => {
    const user = userEvent.setup()
    const api = stubRecurringApi([recurringLait])
    renderEdit()
    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les semaines'))

    await user.click(await setting())
    await user.click(await screen.findByRole('option', { name: 'Non' }))

    await waitFor(() => expect(api.writes).toEqual([expect.objectContaining({ method: 'DELETE', url: expect.stringMatching(/\/R1$/) })]))
    await waitFor(async () => expect(await setting()).toHaveTextContent('Non'))
  })

  test('an API refusal says so and the setting keeps its value', async () => {
    const user = userEvent.setup()
    stubRecurringApi([recurringLait], { failWrites: true })
    renderEdit()
    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les semaines'))

    await user.click(await setting())
    await user.click(await screen.findByRole('option', { name: 'Non' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
    expect(await setting()).toHaveTextContent('Toutes les semaines')
  })

  test('follows a change made elsewhere (Mercure)', async () => {
    const api = stubRecurringApi([])
    renderEdit()
    await waitFor(async () => expect(await setting()).toHaveTextContent('Non'))

    api.members.push(recurringLait)
    act(() => {
      eventSources.find((s) => s.url.includes('recurring_grocery_items'))!.onmessage?.({ data: '{}' } as MessageEvent)
    })

    await waitFor(async () => expect(await setting()).toHaveTextContent('Toutes les semaines'))
  })

  test('is not offered while creating a product: it has no id yet', async () => {
    const api = stubRecurringApi([])
    render(
      <MemoryRouter initialEntries={['/products/create']}>
        <AdminContext dataProvider={dataProvider()}>
          <ResourceContextProvider value="products">
            <Routes>
              <Route path="/products/create" element={<Create><ProductForm /></Create>} />
            </Routes>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    await screen.findByLabelText(/Nom/)
    expect(screen.queryByRole('combobox', { name: 'Récurrent' })).toBeNull()
    expect(api.fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('recurring_grocery_items'), expect.anything())
  })
})
