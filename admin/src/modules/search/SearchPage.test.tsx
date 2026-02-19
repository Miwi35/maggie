import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SearchPage } from './SearchPage'

const mockNavigate = vi.fn()
let mockSearchParams = new URLSearchParams()
const mockSetSearchParams = vi.fn()

vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams, mockSetSearchParams],
}))

const emptyResponse = { total: 0, page: 1, limit: 10, results: [] }

const sampleResults = {
  total: 2,
  page: 1,
  limit: 10,
  results: [
    { index: 'recipes', id: 'r1', score: 2.0, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbonara'] } },
    { index: 'tasks', id: 't1', score: 1.5, data: { title: 'Acheter des pâtes' }, highlights: { title: ['Acheter des <em>pâtes</em>'] } },
  ],
}

describe('SearchPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockNavigate.mockReset()
    mockSearchParams = new URLSearchParams()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(emptyResponse),
    }))
    localStorage.setItem('token', 'test-jwt')
  })

  test('renders search input and type chips', () => {
    render(<SearchPage />)
    expect(screen.getByPlaceholderText('Rechercher…')).toBeInTheDocument()
    expect(screen.getByText('Tous')).toBeInTheDocument()
    expect(screen.getByText('Recettes')).toBeInTheDocument()
    expect(screen.getByText('Tâches')).toBeInTheDocument()
  })

  test('reads query from URL on mount', () => {
    mockSearchParams = new URLSearchParams('q=pâtes')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleResults),
    }))

    render(<SearchPage />)
    const input = screen.getByPlaceholderText('Rechercher…') as HTMLInputElement
    expect(input.value).toBe('pâtes')
  })

  test('shows results after typing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleResults),
    }))

    render(<SearchPage />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
      expect(screen.getByText('Acheter des pâtes')).toBeInTheDocument()
    })

    expect(screen.getByText('2 résultats')).toBeInTheDocument()
  })

  test('type filter chips toggle selection', async () => {
    render(<SearchPage />)
    const recipesChip = screen.getByText('Recettes')
    await userEvent.click(recipesChip)

    // After clicking, the chip should be filled (selected)
    expect(recipesChip.closest('.MuiChip-root')).toHaveClass('MuiChip-filled')
  })

  test('empty state shows message', async () => {
    render(<SearchPage />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'xyz')

    await waitFor(() => {
      expect(screen.getByText(/Aucun résultat pour/)).toBeInTheDocument()
    })
  })

  test('clicking a result navigates to entity page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(sampleResults),
    }))

    render(<SearchPage />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Pâtes carbonara'))
    expect(mockNavigate).toHaveBeenCalledWith('/recipes/r1/show')
  })

  test('shows pagination when multiple pages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ total: 25, page: 1, limit: 10, results: sampleResults.results }),
    }))

    render(<SearchPage />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'test')

    await waitFor(() => {
      expect(screen.getByRole('navigation')).toBeInTheDocument()
    })
  })
})
