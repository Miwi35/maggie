import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SearchBar } from './SearchBar'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
}))

const emptyResponse = { ok: true, json: () => Promise.resolve({ total: 0, page: 1, limit: 10, results: [] }) }

/** Serves `corpus` the way `/api/search` does: filtered by `types`, cut at `limit`. */
function stubSearch(corpus: Array<{ index: string; id: string; score: number; data: Record<string, unknown>; highlights: Record<string, string[]> }>) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((url: string) => {
      const params = new URL(url, 'http://localhost').searchParams
      const types = params.get('types')?.split(',')
      const limit = Number(params.get('limit') ?? 10)
      const matching = corpus.filter((r) => !types || types.includes(r.index))
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ total: matching.length, page: 1, limit, results: matching.slice(0, limit) }),
      })
    }),
  )
}

describe('SearchBar', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockNavigate.mockReset()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(emptyResponse))
    localStorage.setItem('token', 'test-jwt')
  })

  test('renders search input', () => {
    render(<SearchBar />)
    expect(screen.getByPlaceholderText('Rechercher…')).toBeInTheDocument()
  })

  test('renders Ctrl+K hint', () => {
    render(<SearchBar />)
    expect(screen.getByText('Ctrl+K')).toBeInTheDocument()
  })

  test('Ctrl+K focuses the input', async () => {
    render(<SearchBar />)
    const input = screen.getByPlaceholderText('Rechercher…')
    expect(document.activeElement).not.toBe(input)

    await userEvent.keyboard('{Control>}k{/Control}')
    expect(document.activeElement).toBe(input)
  })

  test('typing shows results dropdown', async () => {
    stubSearch([
      { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbonara'] } },
    ])

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
    })

    expect(screen.getByText('Voir tous les résultats (1)')).toBeInTheDocument()
  })

  test('clicking a result navigates', async () => {
    stubSearch([
      { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbo'] } },
    ])

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Pâtes carbonara'))
    expect(mockNavigate).toHaveBeenCalledWith(`/recipes/${encodeURIComponent('/api/recipes/abc123')}/show`)
  })

  test('clicking an event result navigates to calendar', async () => {
    stubSearch([
      { index: 'events', id: 'evt123', score: 1.5, data: { summary: 'Réunion hebdo' }, highlights: { summary: ['<em>Réunion</em> hebdo'] } },
    ])

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'réunion')

    await waitFor(() => {
      expect(screen.getByText('Réunion hebdo')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Réunion hebdo'))
    expect(mockNavigate).toHaveBeenCalledWith(`/calendar?eventId=${encodeURIComponent('/api/events/evt123')}`)
  })

  test('lists every type that matches, even when one type fills the first page of hits', async () => {
    // The API ranks all indexes together and cuts at `limit`: a dozen products
    // named "Pâtes …" outrank the recipe and the meal, which then never reach the screen.
    const hit = (index: string, id: string, label: string, score: number) => ({
      index,
      id,
      score,
      data: { name: label, summary: label },
      highlights: {},
    })
    const corpus = [
      ...Array.from({ length: 12 }, (_, i) => hit('products', `p${i}`, `Pâtes ${i}`, 9 - i * 0.1)),
      hit('meals', 'm1', 'Dîner : Pâtes carbonara', 3),
      hit('recipes', 'r1', 'Pâtes carbonara', 2),
    ]
    stubSearch(corpus)

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    expect(await screen.findByText('Recettes')).toBeInTheDocument()
    expect(screen.getByText('Repas')).toBeInTheDocument()
    expect(screen.getAllByText('Dîner : Pâtes carbonara').length).toBeGreaterThan(0)
    expect(screen.getByText('Voir tous les résultats (14)')).toBeInTheDocument()
  })

  test('Escape closes dropdown', async () => {
    stubSearch([
      { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Test result' }, highlights: { name: ['<em>Test</em> match'] } },
    ])

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'test')

    await waitFor(() => {
      expect(screen.getByText('Test result')).toBeInTheDocument()
    })

    await userEvent.keyboard('{Escape}')

    await waitFor(() => {
      expect(screen.queryByText('Test result')).not.toBeInTheDocument()
    })
  })
})
