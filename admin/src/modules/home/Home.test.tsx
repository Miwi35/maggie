import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { Home } from './Home'
import { cardRegistry } from './cardRegistry'
import type { HomeCardEntry } from './cardRegistry'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => {
  const { pathname, search } = useLocation()

  return <output data-testid="where">{pathname + search}</output>
}

const renderHome = (entry = '/', registry: HomeCardEntry[] = []) =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <Home registry={registry} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const fake = (module: string, zone: HomeCardEntry['zone'], order: number): HomeCardEntry => ({
  module,
  zone,
  order,
  render: ({ day }) => <p data-testid={`card-${module}`}>{`${module}:${day}`}</p>,
})

const selector = () => screen.getByRole('group', { name: 'Jour' })

describe('Home', () => {
  test('with no card, shows the selector and nothing else', () => {
    const { container } = renderHome()

    expect(within(selector()).getAllByRole('button').map((b) => b.textContent)).toEqual(["Aujourd'hui", 'Demain'])
    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Demain' })).toHaveAttribute('aria-pressed', 'false')
    // No prose, no empty zone: the page holds the selector only.
    expect(container.firstElementChild?.textContent).toBe("Aujourd'huiDemain")
    expect(container.querySelectorAll('section')).toHaveLength(0)
    expect(container.firstElementChild?.children).toHaveLength(1)
  })

  test('hands the day to every card, whatever its zone', async () => {
    const user = userEvent.setup()
    renderHome('/?day=tomorrow', [fake('tasks', 'left', 0), fake('grocery', 'grid', 2), fake('finance', 'action', 0)])

    expect(screen.getByRole('button', { name: 'Demain' })).toHaveAttribute('aria-pressed', 'true')
    for (const module of ['tasks', 'grocery', 'finance']) {
      expect(screen.getByTestId(`card-${module}`)).toHaveTextContent(`${module}:tomorrow`)
    }

    await user.click(screen.getByRole('button', { name: "Aujourd'hui" }))

    expect(screen.getByTestId('where')).toHaveTextContent('/')
    expect(screen.getByTestId('where')).not.toHaveTextContent('day=')
    for (const module of ['tasks', 'grocery', 'finance']) {
      expect(screen.getByTestId(`card-${module}`)).toHaveTextContent(`${module}:today`)
    }

    await user.click(screen.getByRole('button', { name: 'Demain' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/?day=tomorrow')
    for (const module of ['tasks', 'grocery', 'finance']) {
      expect(screen.getByTestId(`card-${module}`)).toHaveTextContent(`${module}:tomorrow`)
    }
  })

  test('is keyboard operable and keeps the day when the selected button is pressed again', async () => {
    const user = userEvent.setup()
    renderHome()

    await user.tab()
    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toHaveAttribute('aria-pressed', 'true')

    await user.tab()
    await user.keyboard(' ')
    expect(screen.getByRole('button', { name: 'Demain' })).toHaveAttribute('aria-pressed', 'true')
  })

  test.each(['', '?day=yesterday', '?day='])('reads %j as today', (search) => {
    renderHome(`/${search}`)

    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toHaveAttribute('aria-pressed', 'true')
  })

  test('keeps the other search params when the day changes', async () => {
    const user = userEvent.setup()
    renderHome('/?foo=1')

    await user.click(screen.getByRole('button', { name: 'Demain' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/?foo=1&day=tomorrow')
  })

  test('orders the cards of a zone by their order, and draws only the zones that have cards', () => {
    const { container } = renderHome('/', [fake('grocery', 'grid', 2), fake('nutrition', 'grid', 1)])

    expect(screen.getAllByTestId(/^card-/).map((card) => card.getAttribute('data-testid'))).toEqual([
      'card-nutrition',
      'card-grocery',
    ])
    // The selector and the one zone with cards: no action strip, no left column.
    expect(container.firstElementChild?.children).toHaveLength(2)
  })

  describe('with the registered cards', () => {
    beforeEach(() => localStorage.setItem('token', 'test-jwt'))
    afterEach(() => vi.unstubAllGlobals())

    test('the Nutrition card asks for the day the selector is on', async () => {
      const user = userEvent.setup()
      const fetchMock = vi.fn((url: string) =>
        Promise.resolve({
          ok: true,
          json: () =>
            Promise.resolve(
              url.startsWith('/api/home/nutrition')
                ? {
                    slots: [
                      { slot: 'lunch', meals: [] },
                      { slot: 'dinner', meals: [] },
                    ],
                  }
                : { count: 0, forRecipes: [], stores: [] },
            ),
        }),
      )
      vi.stubGlobal('fetch', fetchMock)
      renderHome('/', cardRegistry)
      await screen.findByTestId('home-nutrition-lunch')
      expect(fetchMock).toHaveBeenCalledWith('/api/home/nutrition?day=today', expect.anything())

      await user.click(screen.getByRole('button', { name: 'Demain' }))

      await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/home/nutrition?day=tomorrow', expect.anything()))
    })
  })
})
