import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RecordContextProvider } from 'react-admin'
import { RecipeScaleSimulation } from './RecipeScaleSimulation'

const recipe = { id: '/api/recipes/01R', servings: 4 }

const scaled = (guests: number) => {
  const factor = guests / 4
  return {
    scaledFor: guests,
    ingredients: [
      { id: '01L1', ingredientName: 'Pâtes', quantity: 400 * factor, unit: 'g' },
      { id: '01L2', ingredientName: 'Sauce tomate', quantity: 2 * factor, unit: 'can' },
    ],
  }
}

function stubApi(handler?: (url: string) => Response | Promise<Response>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (handler) return handler(url)
    const guests = Number(new URL(url, 'http://x').searchParams.get('guests'))
    return new Response(JSON.stringify(scaled(guests)))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const renderSimulation = () =>
  render(
    <RecordContextProvider value={recipe}>
      <RecipeScaleSimulation />
    </RecordContextProvider>,
  )

const more = () => screen.getByRole('button', { name: /de plus/ })
const fewer = () => screen.getByRole('button', { name: /de moins/ })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('RecipeScaleSimulation', () => {
  test('starts at the recipe servings and asks the API for nothing', () => {
    const fetchMock = stubApi()
    renderSimulation()

    expect(screen.getByText('Pour 4 personnes')).toBeInTheDocument()
    expect(screen.getByTestId('guests-value')).toHaveTextContent('4')
    expect(screen.queryByRole('button', { name: /Parts de la recette/ })).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('shows the quantities the API scaled for 8 people', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    renderSimulation()

    for (let i = 0; i < 4; i++) await user.click(more())

    expect(await screen.findByText('800 g Pâtes')).toBeInTheDocument()
    expect(screen.getByText('4 boîtes Sauce tomate')).toBeInTheDocument()
    expect(screen.getByText('Pour 8 personnes')).toBeInTheDocument()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/recipes/01R?guests=8')
  })

  test('goes back to the recipe servings in one click, saving nothing', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    renderSimulation()

    for (let i = 0; i < 4; i++) await user.click(more())
    await screen.findByText('800 g Pâtes')
    await user.click(screen.getByRole('button', { name: 'Parts de la recette (4)' }))

    expect(screen.getByText('Pour 4 personnes')).toBeInTheDocument()
    expect(screen.queryByText('800 g Pâtes')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Parts de la recette/ })).not.toBeInTheDocument()
    expect((fetchMock.mock.calls as unknown[][]).every(([, init]) => (init as RequestInit | undefined)?.method === undefined)).toBe(true)
  })

  test('can simulate fewer people, never fewer than one', async () => {
    stubApi()
    const user = userEvent.setup()
    renderSimulation()

    for (let i = 0; i < 3; i++) await user.click(fewer())

    expect(await screen.findByText('100 g Pâtes')).toBeInTheDocument()
    expect(fewer()).toBeDisabled()
  })

  test('says so when the API cannot simulate, and keeps the recipe quantities', async () => {
    stubApi(() => new Response('boom', { status: 500 }))
    const user = userEvent.setup()
    renderSimulation()

    await user.click(more())

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de simuler les quantités pour 5 personnes')
    await user.click(screen.getByRole('button', { name: 'Parts de la recette (4)' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  test('shows only the last number asked when the buttons are pressed in a row', async () => {
    const resolvers: Record<string, (value: Response) => void> = {}
    stubApi((url) => new Promise((resolve) => (resolvers[url] = resolve)))
    const user = userEvent.setup()
    renderSimulation()

    await user.click(more())
    await user.click(more())
    resolvers['/api/recipes/01R?guests=6']?.(new Response(JSON.stringify(scaled(6))))
    resolvers['/api/recipes/01R?guests=5']?.(new Response(JSON.stringify(scaled(5))))

    expect(await screen.findByText('600 g Pâtes')).toBeInTheDocument()
    expect(screen.queryByText('500 g Pâtes')).not.toBeInTheDocument()
  })
})
