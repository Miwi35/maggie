import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { SearchBar } from './SearchBar'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { TOKENS } from '../../design/tokens'
import { PHONE_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

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

/** The bar reads the Veilleuse palette, so it is always drawn under a theme. */
const renderBar = (theme = veilleuseDarkTheme) =>
  render(
    <ThemeProvider theme={theme}>
      <SearchBar />
    </ThemeProvider>,
  )

describe('SearchBar', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockNavigate.mockReset()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(emptyResponse))
    localStorage.setItem('token', 'test-jwt')
  })

  test('renders search input', () => {
    renderBar()
    expect(screen.getByPlaceholderText('Rechercher…')).toBeInTheDocument()
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ] as const)('draws its field from the %s theme, never in white', (name, theme) => {
    renderBar(theme)
    const field = screen.getByPlaceholderText('Rechercher…').closest('.MuiTextField-root') as HTMLElement
    const emotionClass = Array.from(field.classList).find((c) => c.startsWith('css-'))!
    // jsdom does not cascade nested selectors: read what the field's own rules say.
    const css = Array.from(document.querySelectorAll('style'))
      .map((style) => style.textContent ?? '')
      .join('\n')
      .split('}')
      .filter((rule) => rule.includes(`.${emotionClass}`))
      .join('}')

    expect(css.toLowerCase()).toContain('.muioutlinedinput-root')
    expect(css.toLowerCase()).toContain(TOKENS.surface[name].raised.toLowerCase())
    if (name === 'light') expect(css.replace(/\s/g, '')).not.toMatch(/rgba?\(255,255,255/)
  })

  test('renders Ctrl+K hint', () => {
    renderBar()
    expect(screen.getByText('Ctrl+K')).toBeInTheDocument()
  })

  test('Ctrl+K focuses the input', async () => {
    renderBar()
    const input = screen.getByPlaceholderText('Rechercher…')
    expect(document.activeElement).not.toBe(input)

    await userEvent.keyboard('{Control>}k{/Control}')
    expect(document.activeElement).toBe(input)
  })

  test('typing shows results dropdown', async () => {
    stubSearch([
      { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em> carbonara'] } },
    ])

    renderBar()
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

    renderBar()
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

    renderBar()
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

    renderBar()
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

    renderBar()
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

/**
 * The app bar already carries a burger, a title, the dictation, the bell, the
 * chat and the avatar. A 400px search field on top of that pushed half of them
 * off a 393px screen, so below `md` the field is a magnifier until it is asked
 * for (MAG-38).
 */
describe('SearchBar on a narrow window', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockNavigate.mockReset()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(emptyResponse))
    localStorage.setItem('token', 'test-jwt')
    setViewportWidth(PHONE_WIDTH)
  })

  afterEach(() => {
    resetViewport()
  })

  test('is a magnifier, not a field', () => {
    renderBar()

    expect(screen.getByRole('button', { name: 'Rechercher' })).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Rechercher…')).toBeNull()
  })

  test('opens the field when asked, and gives the bar back when dismissed', async () => {
    renderBar()

    await userEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    const field = screen.getByPlaceholderText('Rechercher…')
    expect(field).toBeVisible()
    expect(document.activeElement).toBe(field)
    // No keyboard, no shortcut to advertise — and 50px of hint is a tenth of
    // a phone's app bar.
    expect(screen.queryByText('Ctrl+K')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Fermer la recherche' }))
    expect(screen.queryByPlaceholderText('Rechercher…')).toBeNull()
    expect(screen.getByRole('button', { name: 'Rechercher' })).toBeInTheDocument()
  })

  test('folds back once a result has been opened', async () => {
    stubSearch([
      { index: 'recipes', id: 'abc123', score: 1.5, data: { name: 'Pâtes carbonara' }, highlights: { name: ['<em>Pâtes</em>'] } },
    ])

    renderBar()
    await userEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    await userEvent.type(screen.getByPlaceholderText('Rechercher…'), 'pates')

    await waitFor(() => expect(screen.getByText('Pâtes carbonara')).toBeInTheDocument())
    await userEvent.click(screen.getByText('Pâtes carbonara'))

    expect(mockNavigate).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Rechercher' })).toBeInTheDocument()
  })
})
