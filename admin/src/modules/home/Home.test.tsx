import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { Home } from './Home'
import { cardRegistry } from './cardRegistry'
import type { HomeCardEntry } from './cardRegistry'
import { useReportHomeCard } from './homeCardStatus'
import type { HomeCardStatus } from './homeCardStatus'

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

  describe('with cards that have nothing to show', () => {
    const Reporting = ({ module, status }: { module: string; status: HomeCardStatus }) => {
      useReportHomeCard(module, status)

      return status === 'empty' ? null : <p data-testid={`card-${module}`}>{module}</p>
    }
    const reporting = (module: string, zone: HomeCardEntry['zone'], order: number, status: HomeCardStatus): HomeCardEntry => ({
      module,
      zone,
      order,
      render: () => <Reporting module={module} status={status} />,
    })

    test('an empty card takes no room: its box is closed, and so is a zone of empty cards', async () => {
      renderHome('/', [reporting('tasks', 'left', 0, 'empty'), reporting('grocery', 'grid', 2, 'filled'), reporting('nutrition', 'grid', 3, 'empty')])

      await waitFor(() => expect(screen.getByTestId('card-grocery')).toBeVisible())
      expect(screen.queryByTestId('card-tasks')).toBeNull()
      expect(screen.queryByTestId('card-nutrition')).toBeNull()
      // Neither the empty card's box nor the left column it was alone in is displayed: no hole in the grid.
      const boxes = Array.from(document.querySelectorAll('div')).filter((div) => getComputedStyle(div).display === 'none')
      expect(boxes).toHaveLength(3)
      expect(screen.queryByTestId('home-empty')).toBeNull()
    })

    test('a card loading or in error is not empty: it stays, and the page says nothing calm', async () => {
      renderHome('/', [reporting('tasks', 'left', 0, 'loading'), reporting('grocery', 'grid', 2, 'error'), reporting('nutrition', 'grid', 3, 'empty')])

      expect(await screen.findByTestId('card-tasks')).toBeVisible()
      expect(screen.getByTestId('card-grocery')).toBeVisible()
      expect(screen.queryByTestId('home-empty')).toBeNull()
    })

    test('every card empty: the page says one calm sentence, for the day it shows', async () => {
      const user = userEvent.setup()
      const { container } = renderHome('/', [reporting('tasks', 'left', 0, 'empty'), reporting('grocery', 'grid', 2, 'empty')])

      expect(await screen.findByTestId('home-empty')).toHaveTextContent("Rien de prévu aujourd'hui.")
      expect(container.firstElementChild?.textContent).toBe("Aujourd'huiDemainRien de prévu aujourd'hui.")

      await user.click(screen.getByRole('button', { name: 'Demain' }))

      expect(screen.getByTestId('home-empty')).toHaveTextContent('Rien de prévu demain.')
    })
  })

  describe('with the registered cards', () => {
    beforeEach(() => localStorage.setItem('token', 'test-jwt'))
    afterEach(() => vi.unstubAllGlobals())

    const emptyRing = { status: 'on_track', label: 'Dans le rythme', budgetCents: 0, remainingCents: 0, expectedRemainingCents: 0 }
    const finance = { week: emptyRing, month: emptyRing, toQualifyCount: 0, nextProject: null, accountCount: 0, envelopeCount: 0 }
    const meal = { id: 'm1', title: 'Soupe', guests: 2, recipes: [{ id: 'r1', name: 'Soupe' }], missing: [] }
    const task = { id: 't1', title: 'Appeler le plombier', done: false, overdueDays: null, parentId: null }
    const nothing: Record<string, unknown> = {
      nutrition: { slots: [{ slot: 'lunch', meals: [] }, { slot: 'dinner', meals: [] }] },
      tasks: { tasks: [], routines: [], done: 0, total: 0, more: 0, overdue: 0, saga: null, editable: true },
      finance,
      agenda: { day: 'today', now: null, events: [] },
      grocery: { count: 0, forRecipes: [], stores: [] },
    }

    /** Stubs fetch with `nothing`, each module's answer overridden by `answers[module][day]`. */
    const stubHome = (answers: Record<string, Partial<Record<'today' | 'tomorrow', unknown>>> = {}, failing: string[] = []) => {
      const fetchMock = vi.fn((url: string) => {
        const module = /\/api\/home\/(\w+)/.exec(url)?.[1] ?? ''
        const day = url.includes('day=tomorrow') ? 'tomorrow' : 'today'
        if (failing.includes(module)) return Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) })

        return Promise.resolve({ ok: true, json: () => Promise.resolve(answers[module]?.[day] ?? nothing[module]) })
      })
      vi.stubGlobal('fetch', fetchMock)

      return fetchMock
    }

    test('the Nutrition card asks for the day the selector is on', async () => {
      const user = userEvent.setup()
      const fetchMock = stubHome({
        nutrition: { today: { slots: [{ slot: 'lunch', meals: [meal] }, { slot: 'dinner', meals: [] }] } },
      })
      renderHome('/', cardRegistry)
      await screen.findByTestId('home-nutrition-lunch')
      expect(fetchMock).toHaveBeenCalledWith('/api/home/nutrition?day=today', expect.anything())

      await user.click(screen.getByRole('button', { name: 'Demain' }))

      await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/home/nutrition?day=tomorrow', expect.anything()))
    })

    test('a card empty today and full tomorrow appears on « Demain », and goes again on « Aujourd\'hui »', async () => {
      const user = userEvent.setup()
      stubHome({ tasks: { tomorrow: { ...(nothing.tasks as object), tasks: [task], editable: false } } })
      renderHome('/', cardRegistry)

      await waitFor(() => expect(screen.getByTestId('home-empty')).toBeInTheDocument())
      expect(screen.queryByTestId('home-card-tasks')).toBeNull()

      await user.click(screen.getByRole('button', { name: 'Demain' }))

      expect(await screen.findByTestId('home-card-tasks')).toBeVisible()
      expect(screen.queryByTestId('home-empty')).toBeNull()

      await user.click(screen.getByRole('button', { name: "Aujourd'hui" }))

      await waitFor(() => expect(screen.queryByTestId('home-card-tasks')).toBeNull())
      expect(screen.getByTestId('home-empty')).toBeInTheDocument()
    })

    test('a card full today and empty tomorrow goes on « Demain »', async () => {
      const user = userEvent.setup()
      stubHome({ nutrition: { today: { slots: [{ slot: 'lunch', meals: [meal] }, { slot: 'dinner', meals: [] }] } } })
      renderHome('/', cardRegistry)
      expect(await screen.findByTestId('home-card-nutrition')).toBeVisible()

      await user.click(screen.getByRole('button', { name: 'Demain' }))

      await waitFor(() => expect(screen.queryByTestId('home-card-nutrition')).toBeNull())
      expect(screen.getByTestId('home-empty')).toHaveTextContent('Rien de prévu demain.')
    })

    test('with nothing anywhere, only the calm sentence is left', async () => {
      stubHome()
      renderHome('/', cardRegistry)

      expect(await screen.findByTestId('home-empty')).toHaveTextContent("Rien de prévu aujourd'hui.")
      expect(document.querySelectorAll('section')).toHaveLength(0)
    })

    test('the cards with something stay, the others are gone', async () => {
      stubHome({ grocery: { today: { ...(nothing.grocery as object), count: 1, stores: [{ id: null, name: null, count: 1, more: 0, items: [{ id: 'i1', name: 'Piles', quantity: null, unit: null, needed: null }] }] } } })
      renderHome('/', cardRegistry)

      expect(await screen.findByTestId('home-card-grocery')).toBeVisible()
      await waitFor(() => expect(screen.queryByTestId('home-card-tasks')).toBeNull())
      expect(screen.queryByTestId('home-card-nutrition')).toBeNull()
      expect(screen.queryByTestId('home-card-finance')).toBeNull()
      expect(screen.queryByTestId('home-card-agenda')).toBeNull()
      expect(screen.queryByTestId('home-empty')).toBeNull()
    })

    test('a card whose read failed stays with its error, and the page is not said to be empty', async () => {
      stubHome({}, ['tasks'])
      renderHome('/', cardRegistry)

      expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
      expect(screen.getByTestId('home-card-tasks')).toBeVisible()
      expect(screen.queryByTestId('home-empty')).toBeNull()
    })
  })
})
