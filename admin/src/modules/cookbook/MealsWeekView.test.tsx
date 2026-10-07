import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import type { DndContextProps } from '@dnd-kit/core'
import userEvent from '@testing-library/user-event'
import { MealsWeekView } from './MealsWeekView'
import { PHONE_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockNotify = vi.fn()
// One provider for every render, like react-admin's own: a new object each time
// would make the view refetch, and so reset what it shows, after every render.
const provider = {
  getList: (...args: unknown[]) => mockGetList(...args),
  create: (...args: unknown[]) => mockCreate(...args),
  delete: vi.fn(),
}
vi.mock('react-admin', () => ({
  useDataProvider: () => provider,
  useNotify: () => mockNotify,
  Title: () => null,
}))

// The drag itself needs a layout engine jsdom does not have, so the tests
// finish a drag the way dnd-kit would: by calling the `onDragEnd` the view
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

const mercure: { onMessage?: () => void } = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], onMessage: () => void) => {
    mercure.onMessage = onMessage
  },
}))

const agendas = [
  { id: '/api/agendas/01FAMILLE', '@id': '/api/agendas/01FAMILLE', name: 'Famille', default: false },
  { id: '/api/agendas/01PERSO', '@id': '/api/agendas/01PERSO', name: 'Perso', default: true },
]

const gratin = { id: '/api/recipes/01GRATIN', '@id': '/api/recipes/01GRATIN', name: 'Gratin de courgettes' }

const answer =
  (agendaRows: unknown[], mealRows: unknown[] = [], recipeRows: unknown[] = [gratin]) =>
  (resource: string) =>
    Promise.resolve({
      data: resource === 'agendas' ? agendaRows : resource === 'meals' ? mealRows : resource === 'recipes' ? recipeRows : [],
      total: 0,
    })

const setupUser = () =>
  // Only the day tests below mock the clock, and userEvent waits on real
  // timers for the rest.
  userEvent.setup({
    advanceTimers: (ms) => {
      if (vi.isFakeTimers()) vi.advanceTimersByTime(ms)
    },
  })

const openDialogOnCell = async (user: ReturnType<typeof userEvent.setup>, cell = 'meal-cell-lunch-0') => {
  render(<MealsWeekView />)
  await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.any(Object)))

  await user.click(screen.getByTestId(cell))
  return within(await screen.findByRole('dialog'))
}

const createEmptyMeal = async (cell = 'meal-cell-lunch-0') => {
  const user = setupUser()
  const dialog = await openDialogOnCell(user, cell)

  await user.click(dialog.getByLabelText('Recettes'))
  await user.click(await screen.findByRole('option', { name: gratin.name }))
  await user.click(dialog.getByRole('button', { name: 'Créer' }))
}

const createFirstEmptyMeal = () => createEmptyMeal()

describe('MealsWeekView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: {} })
    mockGetList.mockImplementation(answer(agendas))
  })

  test('sends no agenda: the API files the meal in the Repas module agenda', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('agenda')
    expect(mockGetList).not.toHaveBeenCalledWith('agendas', expect.anything())
  })

  test('creates the meal for a user who has no Repas agenda and no agenda at all', async () => {
    mockGetList.mockImplementation(answer([]))

    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockNotify).toHaveBeenCalledWith('Repas créé', { type: 'success' })
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('agenda')
  })

  test('plans the recipe that was picked', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.recipes).toEqual([gratin['@id']])
    expect(mockNotify).toHaveBeenCalledWith('Repas créé', { type: 'success' })
  })

  test('refuses a meal with no recipe and keeps the dialog open', async () => {
    const user = setupUser()
    const dialog = await openDialogOnCell(user)

    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/recette/i), { type: 'error' }))
    expect(mockNotify).not.toHaveBeenCalledWith('Repas créé', expect.anything())
    expect(mockCreate).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('tells the user when the recipes cannot be loaded', async () => {
    mockGetList.mockImplementation((resource: string) =>
      resource === 'recipes' ? Promise.reject(new Error('boom')) : answer(agendas)(resource),
    )

    const user = setupUser()
    await openDialogOnCell(user)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/recettes/i), { type: 'error' }))
  })
})

/**
 * A meal is a day and a slot — MAG-251.
 *
 * The owner added a meal in the week view and it came up on the day before.
 * The view turned a local `Date` into a day with `toISOString()`, which is UTC:
 * Wednesday at midnight in Paris is Tuesday at 22:00 there. So the cell he
 * clicked offered him the previous day, and the meal was written to it.
 *
 * Three weeks, deliberately: one under summer time (the one he reported), one
 * under winter time, and the one the clocks go back in. The offset is the bug,
 * so the test has to run where it is +2 and where it is +1 — the time zone is
 * pinned to Europe/Paris in `vite.config.ts` for that reason.
 */
describe('MealsWeekView — the day is the day', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: {} })
    mockGetList.mockImplementation(answer(agendas))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const at = (day: string) => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date(`${day}T12:00:00Z`))
  }

  // [the day in the Wednesday column, the Monday of its week]
  const weeks: [string, string][] = [
    ['2026-04-15', '2026-04-13'], // summer time, in spring
    ['2026-10-07', '2026-10-05'], // summer time, the week the owner reported
    ['2026-12-09', '2026-12-07'], // winter time
    ['2026-10-21', '2026-10-19'], // the clocks go back on the Sunday of this one
  ]

  test.each(weeks)('plans the meal on the day of the cell the owner clicked (%s)', async (wednesday) => {
    at(wednesday)

    await createEmptyMeal('meal-cell-lunch-2')

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { data } = mockCreate.mock.calls[0][1]
    expect(data.date).toBe(wednesday)
    // No instant at all: the day and the slot are the meal.
    expect(data).not.toHaveProperty('startAt')
    expect(data).not.toHaveProperty('endAt')
  })

  /** The changeover day itself: the Sunday the week above ends on. */
  test('plans the meal on the day the clocks go back', async () => {
    at('2026-10-21')

    await createEmptyMeal('meal-cell-dinner-6')

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.date).toBe('2026-10-25')
  })

  test.each(weeks)('asks for the week by day, from its Monday (%s)', async (wednesday, monday) => {
    at(wednesday)

    render(<MealsWeekView />)

    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.any(Object)))
    const [, params] = mockGetList.mock.calls.find(([resource]) => resource === 'meals')!
    expect(params.filter['date[after]']).toBe(monday)
    expect(params.sort.field).toBe('date')
  })

  test.each(weeks)('shows a meal of that day in that day’s cell (%s)', async (wednesday) => {
    at(wednesday)
    mockGetList.mockImplementation(
      answer(agendas, [
        { id: '01MEAL', '@id': '/api/meals/01MEAL', date: wednesday, slot: 'lunch', summary: 'Déjeuner : Tartiflette', recipes: [] },
      ]),
    )

    render(<MealsWeekView />)

    const wednesdayLunch = await screen.findByTestId('meal-cell-lunch-2')
    await waitFor(() => expect(within(wednesdayLunch).getByText(/Tartiflette/)).toBeTruthy())
    // And nowhere else — the day before is the cell the bug used.
    expect(within(screen.getByTestId('meal-cell-lunch-1')).queryByText(/Tartiflette/)).toBeNull()
  })
})

/**
 * Seven columns across 393px give each day 42px, which is narrower than the
 * chip holding a recipe's name. Below `md` the week reads downwards instead —
 * one card per day, its two meals side by side (MAG-38).
 */
describe('MealsWeekView — the week on a phone', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: {} })
    mockGetList.mockImplementation(answer(agendas))
  })

  afterEach(() => {
    resetViewport()
  })

  test('keeps all fourteen cells, each addressable as before', async () => {
    setViewportWidth(PHONE_WIDTH)

    render(<MealsWeekView />)

    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.any(Object)))
    for (const slot of ['lunch', 'dinner']) {
      for (let day = 0; day < 7; day++) {
        expect(screen.getByTestId(`meal-cell-${slot}-${day}`)).toBeInTheDocument()
      }
    }
    // Each day names itself, since there is no header row to read across.
    expect(screen.getAllByText('Dimanche')).toHaveLength(1)
    expect(screen.getAllByText('Déjeuner')).toHaveLength(7)
  })

  test('still opens the dialog on the day that was tapped', async () => {
    setViewportWidth(PHONE_WIDTH)
    const user = setupUser()

    const dialog = await openDialogOnCell(user, 'meal-cell-dinner-3')

    expect(dialog.getByText('Dîner')).toBeInTheDocument()
  })
})

/**
 * Moving a meal by drag and drop — MAG-250.
 *
 * The week is Monday 2026-04-13 and Monday 2026-12-07: one under summer time
 * and one under winter time, because the old view wrote its days with a
 * hard-coded `+01:00` that was an hour short for eight months of the year. A
 * move now sends a day, so it has to land on the day of the cell in both.
 */
describe('MealsWeekView — moving a meal', () => {
  const fetchMock = vi.fn()

  const weeks: [string, string, string][] = [
    // [today, the Tuesday of its week, the Thursday]
    ['2026-04-15', '2026-04-14', '2026-04-16'],
    ['2026-12-09', '2026-12-08', '2026-12-10'],
  ]

  const meal = (tuesday: string, extra: object = {}) => ({
    id: '01MEAL',
    '@id': '/api/meals/01MEAL',
    date: tuesday,
    slot: 'lunch',
    summary: 'Déjeuner : Tartiflette',
    recipes: [{ id: '01TARTIFLETTE', name: 'Tartiflette' }],
    ...extra,
  })

  const dropOn = (id: string, cell: string) =>
    act(() => {
      dnd.props?.onDragEnd?.({ active: { id }, over: { id: cell } } as never)
    })

  const inCell = (cell: string, text: string | RegExp) => within(screen.getByTestId(cell)).queryByText(text)

  const renderWeek = async (today: string, rows: unknown[]) => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date(`${today}T12:00:00Z`))
    mockGetList.mockImplementation(answer(agendas, rows))
    render(<MealsWeekView />)
    await waitFor(() => expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeTruthy())
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mercure.onMessage = undefined
    fetchMock.mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('gives every meal a handle to grab it by', async () => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])

    expect(screen.getByRole('button', { name: /Déplacer le repas Déjeuner : Tartiflette/ })).toBeInTheDocument()
  })

  test.each(weeks)('moves Tuesday lunch to Thursday dinner, sending the day and the slot only (%s)', async (today, tuesday, thursday) => {
    let answerPatch: (value: unknown) => void = () => {}
    fetchMock.mockReturnValue(new Promise((resolve) => (answerPatch = resolve)))
    await renderWeek(today, [meal(tuesday)])

    dropOn('01MEAL', 'dinner:3')

    // Optimistic: drawn in its new cell before the API has answered.
    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeTruthy()
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeNull()

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/meals/01MEAL')
    expect(init.method).toBe('PATCH')
    expect(init.headers['Content-Type']).toBe('application/merge-patch+json')
    // The recipes stay where they are, and no instant travels: a day, a slot.
    expect(JSON.parse(init.body)).toEqual({ date: thursday, slot: 'dinner' })

    await act(async () => answerPatch({ ok: true, status: 200 }))
    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeTruthy()
    expect(mockNotify).not.toHaveBeenCalled()
  })

  test('keeps the meal where it was dropped when the list answers with the old cell', async () => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])

    dropOn('01MEAL', 'dinner:3')
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    // Mercure says "a meal changed" and the list is read at once, from an
    // index that has not caught up.
    await act(async () => mercure.onMessage?.())

    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeTruthy()
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeNull()
  })

  test('follows the list once it agrees, or once the grace has run out', async () => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])

    dropOn('01MEAL', 'dinner:3')
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    // Another window moves it again, long after.
    act(() => vi.advanceTimersByTime(10_000))
    mockGetList.mockImplementation(answer(agendas, [meal('2026-04-15', { slot: 'lunch' })]))
    await act(async () => mercure.onMessage?.())

    expect(inCell('meal-cell-lunch-2', /Tartiflette/)).toBeTruthy()
    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeNull()
  })

  test.each([
    ['refuses', () => fetchMock.mockResolvedValue({ ok: false, status: 422 })],
    ['cannot be reached', () => fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))],
  ])('puts the meal back in its cell and says so when the API %s', async (_, failWith) => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])
    failWith()

    dropOn('01MEAL', 'dinner:3')

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/pas pu être déplacé/), { type: 'error' }))
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeTruthy()
    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeNull()

    // And a later read of the list cannot bring the failed move back.
    await act(async () => mercure.onMessage?.())
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeTruthy()
  })

  test('puts the meal beside the one already in an occupied cell', async () => {
    const soup = meal('2026-04-16', { id: '01SOUP', '@id': '/api/meals/01SOUP', slot: 'dinner', summary: 'Dîner : Soupe', recipes: [{ id: '01SOUP', name: 'Soupe' }] })
    await renderWeek('2026-04-15', [meal('2026-04-14'), soup])

    dropOn('01MEAL', 'dinner:3')

    expect(inCell('meal-cell-dinner-3', /Tartiflette/)).toBeTruthy()
    expect(inCell('meal-cell-dinner-3', /Soupe/)).toBeTruthy()
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ date: '2026-04-16', slot: 'dinner' })
  })

  test('sends nothing when the meal is dropped on its own cell, or outside any cell', async () => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])

    dropOn('01MEAL', 'lunch:1')
    act(() => {
      dnd.props?.onDragEnd?.({ active: { id: '01MEAL' }, over: null } as never)
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeTruthy()
  })

  test('can be picked up and cancelled from the keyboard, with the instructions read aloud', async () => {
    await renderWeek('2026-04-15', [meal('2026-04-14')])
    const user = setupUser()
    const handle = screen.getByRole('button', { name: /Déplacer le repas/ })

    expect(handle).toHaveAttribute('aria-roledescription', 'repas déplaçable')
    expect(document.body).toHaveTextContent(/Espace pour le saisir/)

    handle.focus()
    await user.keyboard(' ')
    // Picked up, and at once over a cell — jsdom has no layout, so which one
    // is arbitrary; that a cell is announced is the point.
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Au-dessus de la case/))

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Déplacement annulé/))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(inCell('meal-cell-lunch-1', /Tartiflette/)).toBeTruthy()
  })
})
