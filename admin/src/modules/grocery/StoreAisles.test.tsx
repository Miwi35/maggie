import { beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DndContextProps } from '@dnd-kit/core'
import { stubAislesApi } from '../../test/aislesApi'
import type { StubAisle } from '../../test/aislesApi'
import { STORES_PAGE_TOPICS } from './aislesApi'
import { StoreAisles } from './StoreAisles'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useNotify: () => mockNotify,
}))

// The drag itself needs a layout engine jsdom does not have, so the tests
// finish a drag the way dnd-kit would: by calling the `onDragEnd` the list
// gave the context. What a real drag does is the Playwright journey's.
const dnd: { props?: DndContextProps } = {}
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: DndContextProps) => {
      dnd.props = props
      return <actual.DndContext {...props} />
    },
  }
})

const mercure: { topics?: string[]; onMessage?: (data?: string) => void } = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: (data?: string) => void) => {
    mercure.topics = topics
    mercure.onMessage = onMessage
  },
}))

const CARREFOUR: StubAisle[] = [
  { id: 'fruits', store: 'carrefour', name: 'Fruits et légumes', position: 1 },
  { id: 'dairy', store: 'carrefour', name: 'Crèmerie', position: 2 },
  { id: 'bakery', store: 'carrefour', name: 'Boulangerie', position: 3 },
]
const OTHER_STORE: StubAisle = { id: 'elsewhere', store: 'biocoop', name: 'Vrac', position: 1 }

const shown = () => screen.getAllByTestId('store-aisle').map((row) => row.getAttribute('data-aisle'))

const drag = (active: string, over: string) =>
  act(async () => {
    await dnd.props?.onDragEnd?.({ active: { id: active }, over: { id: over } } as never)
  })

describe('StoreAisles', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockNotify.mockReset()
    localStorage.setItem('token', 'test-jwt')
  })

  test("lists the store's aisles, and only its own, in the order of the way through it", async () => {
    stubAislesApi([CARREFOUR[2], OTHER_STORE, CARREFOUR[0], CARREFOUR[1]])
    render(<StoreAisles storeId="carrefour" />)

    await waitFor(() => expect(shown()).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie']))
    expect(screen.getByText('Ordre du parcours')).toBeInTheDocument()
  })

  test('adds an aisle at the end with Enter', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await user.type(screen.getByLabelText('Nouveau rayon'), 'Surgelés{Enter}')

    await waitFor(() => expect(shown()).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie', 'Surgelés']))
    expect(writes).toEqual([
      { method: 'POST', url: expect.stringMatching(/\/aisles$/), body: { store: '/api/stores/carrefour', name: 'Surgelés' } },
    ])
    expect(screen.getByLabelText('Nouveau rayon')).toHaveValue('')
  })

  test('says why an aisle was refused, and keeps what was typed', async () => {
    const user = userEvent.setup()
    stubAislesApi(CARREFOUR, { failWrites: true })
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await user.type(screen.getByLabelText('Nouveau rayon'), 'crèmerie')
    await user.click(screen.getByRole('button', { name: '+ Rayon' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Ce magasin a déjà ce rayon.', { type: 'error' }))
    expect(shown()).toHaveLength(3)
    expect(screen.getByLabelText('Nouveau rayon')).toHaveValue('crèmerie')
  })

  test('renames an aisle in place', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await user.click(screen.getByRole('button', { name: 'Renommer Crèmerie' }))
    const input = screen.getByLabelText('Nom du rayon Crèmerie')
    await user.clear(input)
    await user.type(input, 'Produits frais{Enter}')

    await waitFor(() => expect(shown()).toEqual(['Fruits et légumes', 'Produits frais', 'Boulangerie']))
    expect(writes).toEqual([{ method: 'PATCH', url: expect.stringMatching(/\/aisles\/dairy$/), body: { name: 'Produits frais' } }])
  })

  test('Escape leaves the name as it was', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await user.click(screen.getByRole('button', { name: 'Renommer Crèmerie' }))
    await user.type(screen.getByLabelText('Nom du rayon Crèmerie'), ' bio{Escape}')

    expect(shown()).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie'])
    expect(writes).toEqual([])
  })

  test('deletes an aisle once confirmed', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await user.click(screen.getByRole('button', { name: 'Supprimer Crèmerie' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Supprimer le rayon « Crèmerie » ?')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(shown()).toEqual(['Fruits et légumes', 'Boulangerie']))
    expect(writes).toEqual([{ method: 'DELETE', url: expect.stringMatching(/\/aisles\/dairy$/), body: undefined }])
  })

  test('a drag moves the aisle at once and sends its new place, 1 being the first', async () => {
    const { writes } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await drag('bakery', 'dairy')

    expect(shown()).toEqual(['Fruits et légumes', 'Boulangerie', 'Crèmerie'])
    expect(writes).toEqual([{ method: 'PATCH', url: expect.stringMatching(/\/aisles\/bakery$/), body: { position: 2 } }])
  })

  test('a refused move says so and puts the aisles back as the API has them', async () => {
    stubAislesApi(CARREFOUR, { failWrites: true })
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))

    await drag('fruits', 'bakery')

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Ce magasin a déjà ce rayon.', { type: 'error' }))
    await waitFor(() => expect(shown()).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie']))
  })

  test('an aisle moved elsewhere (the phone, Maggie) is redrawn from its Mercure update', async () => {
    const { aisles } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))
    expect(mercure.topics).toEqual(STORES_PAGE_TOPICS)

    aisles.find((a) => a.id === 'fruits')!.position = 4
    await act(async () => mercure.onMessage?.(JSON.stringify({ name: 'Fruits et légumes', position: 4, storeId: 'carrefour' })))

    await waitFor(() => expect(shown()).toEqual(['Crèmerie', 'Boulangerie', 'Fruits et légumes']))
  })

  test("another store's aisle changing leaves this list alone", async () => {
    const { fetchMock } = stubAislesApi(CARREFOUR)
    render(<StoreAisles storeId="carrefour" />)
    await waitFor(() => expect(shown()).toHaveLength(3))
    const reads = fetchMock.mock.calls.length

    await act(async () => mercure.onMessage?.(JSON.stringify({ name: 'Vrac', position: 1, storeId: 'biocoop' })))

    expect(fetchMock.mock.calls.length).toBe(reads)
  })

  test('a list the API does not give says so', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) } as Response)))
    render(<StoreAisles storeId="carrefour" />)

    await waitFor(() =>
      expect(mockNotify).toHaveBeenCalledWith("Erreur : les rayons n'ont pas été chargés", { type: 'error' }),
    )
  })
})
