import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SearchBar } from './SearchBar'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
}))

const emptyResponse = { ok: true, json: () => Promise.resolve({ total: 0, page: 1, limit: 10, results: [] }) }

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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        total: 1,
        page: 1,
        limit: 10,
        results: [
          { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbonara'] } },
        ],
      }),
    }))

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
    })

    expect(screen.getByText('Voir tous les résultats (1)')).toBeInTheDocument()
  })

  test('clicking a result navigates', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        total: 1,
        page: 1,
        limit: 10,
        results: [
          { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbo'] } },
        ],
      }),
    }))

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pâtes')

    await waitFor(() => {
      expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Pâtes carbonara'))
    expect(mockNavigate).toHaveBeenCalledWith('/recipes/abc123/show')
  })

  test('clicking an event result navigates to calendar', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        total: 1,
        page: 1,
        limit: 10,
        results: [
          { index: 'events', id: 'evt123', score: 1.5, data: { summary: 'Réunion hebdo' }, highlights: { summary: ['<em>Réunion</em> hebdo'] } },
        ],
      }),
    }))

    render(<SearchBar />)
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'réunion')

    await waitFor(() => {
      expect(screen.getByText('Réunion hebdo')).toBeInTheDocument()
    })

    await userEvent.click(screen.getByText('Réunion hebdo'))
    expect(mockNavigate).toHaveBeenCalledWith('/calendar?eventId=evt123')
  })

  test('Escape closes dropdown', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        total: 1,
        page: 1,
        limit: 10,
        results: [
          { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Test result' }, highlights: { name: ['<em>Test</em> match'] } },
        ],
      }),
    }))

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
