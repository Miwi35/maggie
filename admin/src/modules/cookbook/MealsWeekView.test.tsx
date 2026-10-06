import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MealsWeekView } from './MealsWeekView'
import { PHONE_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: mockGetList, create: mockCreate, delete: vi.fn() }),
  useNotify: () => mockNotify,
  Title: () => null,
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

  test('creates the meal in a real agenda, never in the agendas collection', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { agenda } = mockCreate.mock.calls[0][1].data
    expect(agenda).not.toBe('/api/agendas')
    expect(agenda).toMatch(/^\/api\/agendas\/\w+$/)
  })

  test('prefers the agenda named Repas', async () => {
    mockGetList.mockImplementation(
      answer([...agendas, { id: '/api/agendas/01REPAS', '@id': '/api/agendas/01REPAS', name: 'Repas', default: false }]),
    )

    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.agenda).toBe('/api/agendas/01REPAS')
  })

  test('falls back to the default agenda', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.agenda).toBe('/api/agendas/01PERSO')
  })

  test('tells the user instead of posting when there is no agenda', async () => {
    mockGetList.mockImplementation(answer([]))

    await createFirstEmptyMeal()

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/agenda/i), { type: 'error' }))
    expect(mockCreate).not.toHaveBeenCalled()
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
