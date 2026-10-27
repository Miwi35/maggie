import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../../theme'
import { emitMercure } from '../../../test/mercure'
import { CookingActivityPage } from './CookingActivityPage'
import { fakeStatusApi } from './fakeStatusApi'
import { fakeTimersApi } from './fakeTimersApi'
import type { FakeTimersApi } from './fakeTimersApi'

vi.mock('../../../components/chat/ChatWidget', () => ({ ChatWidget: () => null }))
vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const TOPIC = '/api/grocery_lists/{id}'

const step = (position: number, text: string, extra: Record<string, unknown> = {}) => ({ id: `s${position}`, position, text, durationMinutes: null, background: false, ingredients: [], utensils: [], waitsFor: [], ...extra })

/** The recipe as the API serves it: 75 g of parmesan and 3 eggs per guest. */
const recipeBody = (guests: number | null) => ({
  id: 'REF',
  name: 'Gratin',
  servings: 4,
  ingredients: [
    { id: 'l1', ingredient: { id: 'PARMESAN', name: 'Parmesan' }, ingredientName: 'Parmesan', quantity: 75 * (guests ?? 4), unit: 'g' },
    { id: 'l2', ingredient: { id: 'OEUF', name: 'Oeuf' }, ingredientName: 'Oeuf', quantity: 1 * (guests ?? 4), unit: 'piece' },
  ],
  utensils: [],
  steps: [step(1, 'Faire gratiner', { durationMinutes: 10 }), step(2, 'Servir')],
})

const MEALS: Record<string, { guests: number }> = { M4: { guests: 4 }, M2: { guests: 2 } }

const carrefour = { id: 'S1', name: 'Carrefour', visitOrder: 2 }
const lidl = { id: 'S2', name: 'Lidl', visitOrder: 1 }

const line = (id: string, label: string, product: string | null, extra: Record<string, unknown> = {}) => ({
  id,
  label,
  position: 1,
  checked: false,
  product: product ? { '@id': `/api/products/${product}`, id: product } : null,
  store: carrefour,
  ...extra,
})

const LIST = () => [
  line('i-lait', 'Lait', 'LAIT'),
  line('i-parmesan', 'Parmesan', 'PARMESAN', { quantity: 200, unit: 'g', position: 2 }),
  line('i-pain', 'Pain', 'PAIN', { store: lidl }),
  line('i-oeuf', 'Oeufs', 'OEUF', { quantity: 12, unit: 'piece', position: 3 }),
  line('i-later', 'Vanille', 'VANILLE', { buyAfter: '2999-01-01T00:00:00+00:00' }),
  line('i-done', 'Sel', 'SEL', { checked: true }),
]

let items = LIST()
let listLoads: 'ok' | 'fail' = 'ok'
let patchOutcome: 'ok' | 'refused' | 'offline' = 'ok'
let timersApi: FakeTimersApi
const patches: { url: string; contentType: string | undefined; body: unknown }[] = []

const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }))

const fakeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input)
  const status = fakeStatusApi()
  const saved = status.handle(url, init)
  if (saved) return saved
  const running = timersApi.handle(url, init)
  if (running) return running
  if (url.startsWith('/api/grocery_lists?')) return listLoads === 'ok' ? respond({ member: [{ id: 'GL' }] }) : respond({}, 503)
  if (url === '/api/grocery_lists/GL') return respond({ id: 'GL', items })
  const patch = url.match(/^\/api\/grocery_items\/(.+)$/)
  if (patch && init?.method === 'PATCH') {
    patches.push({ url, contentType: (init.headers as Record<string, string>)['Content-Type'], body: JSON.parse(String(init.body)) })
    if (patchOutcome === 'offline') return Promise.reject(new TypeError('Failed to fetch'))
    if (patchOutcome === 'refused') return respond({}, 422)
    const checked = JSON.parse(String(init.body)).checked
    items = items.map((entry) => (entry.id === patch[1] ? { ...entry, checked } : entry))

    return respond({})
  }
  const meal = url.match(/^\/api\/meals\/(.+)$/)
  if (meal && MEALS[meal[1]]) return respond({ id: meal[1], guests: MEALS[meal[1]].guests, recipes: ['/api/recipes/REF'] })
  const recipe = url.match(/^\/api\/recipes\/REF(?:\?guests=(\d+))?$/)
  if (recipe) return respond(recipeBody(recipe[1] ? Number(recipe[1]) : null))

  return Promise.resolve(new Response('', { status: 404 }))
}

const renderActivity = (entry: string) =>
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/activites/cuisine" element={<CookingActivityPage />} />
          <Route path="*" element={<output data-testid="where" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )

const open = async (meal = 'M4') => {
  renderActivity(`/activites/cuisine?meal=${meal}`)
  await screen.findByTestId('cooking-preparation')
  await userEvent.click(screen.getByRole('button', { name: /^Courses/ }))

  return screen.findByRole('dialog', { name: 'Liste de courses' })
}

const rowOf = (dialog: HTMLElement, label: string) => within(dialog).getAllByTestId('cooking-grocery-item').find((row) => row.textContent?.includes(label))!

describe('The shopping list inside the Cuisine activity (MAG-583)', () => {
  const exitFullscreen = vi.fn(() => Promise.resolve())

  beforeEach(() => {
    sessionStorage.clear()
    items = LIST()
    listLoads = 'ok'
    patchOutcome = 'ok'
    patches.length = 0
    exitFullscreen.mockClear()
    timersApi = fakeTimersApi()
    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, writable: true, value: exitFullscreen })
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', {
      configurable: true,
      writable: true,
      value(this: HTMLElement) {
        Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: this })

        return Promise.resolve()
      },
    })
  })

  afterEach(() => {
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: null })
    vi.unstubAllGlobals()
  })

  test('the header button counts the lines left to buy, not the ticked or the put-off ones', async () => {
    renderActivity('/activites/cuisine?meal=M4')

    const button = await screen.findByRole('button', { name: /^Courses/ })
    await waitFor(() => expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('4'))
    expect(button).toHaveTextContent('Courses')
  })

  test('opens as a panel inside the frame of the activity, by store in visit order', async () => {
    const dialog = await open()

    expect(within(screen.getByTestId('activity-shell')).getByRole('dialog', { name: 'Liste de courses' })).toBe(dialog)
    const stores = within(dialog).getAllByTestId('cooking-grocery-store')
    expect(stores.map((store) => store.getAttribute('aria-label'))).toEqual(['Lidl', 'Carrefour'])
    expect(within(dialog).queryByText('Vanille')).not.toBeInTheDocument()
  })

  test('the lines of the meal open each store, marked « ce repas », with the quantity the meal needs', async () => {
    const dialog = await open()

    const carrefourRows = within(within(dialog).getAllByTestId('cooking-grocery-store')[1]).getAllByTestId('cooking-grocery-item')
    expect(carrefourRows.map((row) => row.dataset.meal)).toEqual(['true', 'true', 'false', 'false'])
    expect(carrefourRows[0]).toHaveTextContent('Parmesan')
    expect(carrefourRows[0]).toHaveTextContent('ce repas')
    expect(carrefourRows[0]).toHaveTextContent('300 g')
    expect(carrefourRows[1]).toHaveTextContent('Oeufs')
    expect(carrefourRows[1]).toHaveTextContent('4 pièces')
    expect(carrefourRows[2]).toHaveTextContent('Lait')
    expect(within(carrefourRows[2]).queryByText('ce repas')).not.toBeInTheDocument()
    expect(within(dialog.querySelectorAll<HTMLElement>('[data-testid="cooking-grocery-store"]')[0]).getAllByTestId('cooking-grocery-item')).toHaveLength(1)
  })

  test('says what the meal needs when the list plans less, and nothing when it plans enough', async () => {
    const dialog = await open()

    expect(within(rowOf(dialog, 'Parmesan')).getByTestId('cooking-grocery-missing')).toHaveTextContent('il en faut 300 g')
    expect(within(rowOf(dialog, 'Oeufs')).queryByTestId('cooking-grocery-missing')).not.toBeInTheDocument()
  })

  test('a meal launched for 2 instead of 4 needs half as much', async () => {
    const dialog = await open('M2')

    expect(rowOf(dialog, 'Parmesan')).toHaveTextContent('150 g')
    expect(within(rowOf(dialog, 'Parmesan')).queryByTestId('cooking-grocery-missing')).not.toBeInTheDocument()
    expect(rowOf(dialog, 'Oeufs')).toHaveTextContent('2 pièces')
  })

  test('ticking sends the PATCH, shows the tick at once and keeps it', async () => {
    const dialog = await open()

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Lait' }))

    expect(within(dialog).getByRole('checkbox', { name: 'Lait' })).toBeChecked()
    await waitFor(() => expect(patches).toHaveLength(1))
    expect(patches[0]).toEqual({ url: '/api/grocery_items/i-lait', contentType: 'application/merge-patch+json', body: { checked: true } })
    await waitFor(() => expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('3'))
    expect(within(dialog).getByRole('checkbox', { name: 'Lait' })).toBeChecked()
  })

  test('a tap on the row ticks too, and a ticked line can be unticked', async () => {
    const dialog = await open()

    await userEvent.click(rowOf(dialog, 'Sel'))

    await waitFor(() => expect(patches[0]?.body).toEqual({ checked: false }))
    await waitFor(() => expect(within(dialog).getByRole('checkbox', { name: 'Sel' })).not.toBeChecked())
  })

  test('the tick goes back with a message when the network fails', async () => {
    patchOutcome = 'offline'
    const dialog = await open()

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Lait' }))

    expect(await within(dialog).findByTestId('cooking-grocery-notice')).toHaveTextContent('Impossible de cocher « Lait »')
    expect(within(dialog).getByRole('checkbox', { name: 'Lait' })).not.toBeChecked()
    expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('4')
  })

  test('the tick goes back with a message when the API refuses it', async () => {
    patchOutcome = 'refused'
    const dialog = await open()

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Pain' }))

    expect(await within(dialog).findByTestId('cooking-grocery-notice')).toHaveTextContent('Impossible de cocher « Pain »')
    expect(within(dialog).getByRole('checkbox', { name: 'Pain' })).not.toBeChecked()
  })

  test('follows the list live: a tick made elsewhere, a new line, a removed line', async () => {
    const dialog = await open()

    emitMercure(TOPIC, { action: 'check', itemId: 'i-lait', checked: true })
    await waitFor(() => expect(within(dialog).getByRole('checkbox', { name: 'Lait' })).toBeChecked())
    expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('3')

    emitMercure(TOPIC, { action: 'remove', itemId: 'i-pain' })
    await waitFor(() => expect(within(dialog).queryByRole('checkbox', { name: 'Pain' })).not.toBeInTheDocument())

    emitMercure(TOPIC, { items: [...LIST(), line('i-miel', 'Miel', null)] })
    expect(await within(dialog).findByRole('checkbox', { name: 'Miel' })).toBeInTheDocument()
  })

  test('says « Rien à acheter » when the list has nothing for today', async () => {
    items = [line('i-later', 'Vanille', 'VANILLE', { buyAfter: '2999-01-01T00:00:00+00:00' })]
    const dialog = await open()

    expect(await within(dialog).findByText('Rien à acheter')).toBeInTheDocument()
    expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('0')
  })

  test('says it could not read the list, and « Réessayer » reads it again', async () => {
    listLoads = 'fail'
    const dialog = await open()

    expect(await within(dialog).findByText("La liste de courses n'a pas pu être chargée.")).toBeInTheDocument()
    expect(screen.queryByTestId('cooking-grocery-count')).not.toBeInTheDocument()

    listLoads = 'ok'
    await userEvent.click(within(dialog).getByRole('button', { name: 'Réessayer' }))

    expect(await within(dialog).findByRole('checkbox', { name: 'Lait' })).toBeInTheDocument()
    expect(screen.getByTestId('cooking-grocery-count')).toHaveTextContent('4')
  })

  test('shows a loading state while the list is on its way', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => (String(input).startsWith('/api/grocery_lists') ? new Promise<Response>(() => {}) : fakeFetch(input, init))),
    )
    const dialog = await open()

    expect(within(dialog).getByTestId('cooking-grocery-loading')).toBeInTheDocument()
  })

  test('opening and closing the list never leaves full screen, and the step and its timer are as they were', async () => {
    renderActivity('/activites/cuisine?meal=M4')
    await screen.findByTestId('cooking-preparation')
    await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
    await userEvent.click(screen.getByRole('button', { name: 'Démarrer' }))
    await waitFor(() => expect(screen.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'running'))
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))
    const clock = screen.getByTestId('cooking-main-timer-clock')

    await userEvent.click(screen.getByRole('button', { name: /^Courses/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Liste de courses' })
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Lait' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Fermer la liste de courses' }))
    await waitFor(() => expect(dialog).not.toBeInTheDocument())

    expect(exitFullscreen).not.toHaveBeenCalled()
    expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen')
    expect(screen.getByTestId('cooking-step-text')).toHaveTextContent('Faire gratiner')
    expect(screen.getByTestId('cooking-main-timer-clock')).toBe(clock)
    expect(screen.getByTestId('cooking-main-timer')).toHaveAttribute('data-status', 'running')
    expect(timersApi.requests.filter((request) => request.method !== 'GET')).toHaveLength(1)
  })

  test('Échap closes the list but not the activity', async () => {
    const dialog = await open()

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(dialog).not.toBeInTheDocument())
    expect(screen.getByTestId('activity-shell')).toBeInTheDocument()
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()
  })

  test('the arrow keys pressed in the list do not move the step underneath', async () => {
    const dialog = await open()

    await userEvent.keyboard('{ArrowRight}')

    expect(dialog).toBeInTheDocument()
    expect(screen.getByTestId('cooking-preparation')).toBeInTheDocument()
    expect(screen.queryByTestId('cooking-step-text')).not.toBeInTheDocument()
  })
})
