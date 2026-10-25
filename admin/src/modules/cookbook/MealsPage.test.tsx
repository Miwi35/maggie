import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MealsPage } from './MealsPage'
import { PHONE_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockDelete = vi.fn()
const mockNotify = vi.fn()
// One provider for every render, like react-admin's own: a new object each time
// would make the page refetch, and so reset what it shows, after every render.
const provider = {
  getList: (...args: unknown[]) => mockGetList(...args),
  create: (...args: unknown[]) => mockCreate(...args),
  delete: (...args: unknown[]) => mockDelete(...args),
}
vi.mock('react-admin', () => ({
  useDataProvider: () => provider,
  useNotify: () => mockNotify,
  Title: () => null,
}))

const mercure: { onMessage?: () => void } = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], onMessage: () => void) => {
    mercure.onMessage = onMessage
  },
}))

// The week of Monday 13 April 2026, a Wednesday being « today ».
const TODAY = '2026-04-15T12:00:00Z'

const potato = { '@id': '/api/ingredients/01POTATO', id: '01POTATO', name: 'Pommes de terre' }
const cheese = { '@id': '/api/ingredients/01CHEESE', id: '01CHEESE', name: 'Reblochon' }
const lettuce = { '@id': '/api/ingredients/01LETTUCE', id: '01LETTUCE', name: 'Salade' }

const tartiflette = {
  id: '/api/recipes/01TARTIFLETTE',
  '@id': '/api/recipes/01TARTIFLETTE',
  name: 'Tartiflette',
  servings: 4,
  ingredients: [
    { ingredient: potato, ingredientName: 'Pommes de terre', quantity: 800, unit: 'g' },
    { ingredient: cheese, ingredientName: 'Reblochon', quantity: 1, unit: 'piece' },
  ],
}
const salade = {
  id: '/api/recipes/01SALADE',
  '@id': '/api/recipes/01SALADE',
  name: 'Salade verte',
  servings: 2,
  ingredients: [
    { ingredient: lettuce, ingredientName: 'Salade', quantity: 1, unit: 'piece' },
    { ingredient: potato, ingredientName: 'Pommes de terre', quantity: 100, unit: 'g' },
  ],
}
const gratin = {
  id: '/api/recipes/01GRATIN',
  '@id': '/api/recipes/01GRATIN',
  name: 'Gratin de courgettes',
  servings: 2,
  ingredients: [],
}
const recipes = [tartiflette, salade, gratin]

// What the API really lists: the recipes of a meal are IRIs, never objects.
const lunch = {
  id: '01LUNCH',
  '@id': '/api/meals/01LUNCH',
  date: '2026-04-14',
  slot: 'lunch',
  summary: 'Déjeuner : Tartiflette, Salade verte',
  recipes: [tartiflette['@id'], salade['@id']],
  guests: 2,
  guestsIsDefault: true,
  groceryChoiceMadeAt: '2026-04-13T08:00:00+00:00',
}
const dinner = {
  id: '01DINNER',
  '@id': '/api/meals/01DINNER',
  date: '2026-04-16',
  slot: 'dinner',
  summary: 'Dîner : Gratin de courgettes',
  recipes: [gratin['@id']],
  guests: 6,
  guestsIsDefault: false,
  groceryChoiceMadeAt: null,
}

const answer =
  (mealRows: unknown[] = [], recipeRows: unknown[] = recipes) =>
  (resource: string) =>
    Promise.resolve({ data: resource === 'meals' ? mealRows : resource === 'recipes' ? recipeRows : [], total: 0 })

const fetchMock = vi.fn()
const jsonOk = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
const jsonKo = (status = 500) => Promise.resolve({ ok: false, status, json: () => Promise.resolve({}) })

const patches = () =>
  fetchMock.mock.calls
    .filter(([, init]) => init?.method === 'PATCH')
    .map(([url, init]) => [url, JSON.parse(String(init.body))] as const)

const route = (overrides: Partial<Record<'patch' | 'settings' | 'scaled' | 'preview' | 'post', (url: string, init?: { body?: string }) => Promise<unknown>>> = {}) =>
  fetchMock.mockImplementation((url: string, init?: { method?: string; body?: string }) => {
    if (init?.method === 'PATCH') return (overrides.patch ?? ((_u, i) => jsonOk(JSON.parse(String(i?.body)))))(url, init)
    if (init?.method === 'POST') return (overrides.post ?? (() => jsonOk({})))(url, init)
    if (url === '/api/meal-settings/me') return (overrides.settings ?? (() => jsonOk({ defaultGuests: 4 })))(url)
    if (url.includes('grocery_preview'))
      return (overrides.preview ?? (() => jsonOk({ mealId: '01LUNCH', groceryChoiceMadeAt: null, ingredients: [riceLine] })))(url)
    if (url.includes('?guests='))
      return (
        overrides.scaled ??
        (() => jsonOk({ ingredients: [{ id: 'l1', ingredientName: 'Pommes de terre', quantity: 400, unit: 'g' }] }))
      )(url)
    return jsonOk({})
  })

const riceLine = {
  ingredientId: '01RICE',
  name: 'Riz',
  quantity: 300,
  unit: 'g',
  packaging: { unit: 'pack', size: 500, sizeUnit: 'g' },
  toBuy: { quantity: 1, unit: 'pack' },
  stockState: 'out',
  suggested: true,
}

const setupUser = () => userEvent.setup()

const renderPage = async (mealRows: unknown[] = [lunch, dinner]) => {
  mockGetList.mockImplementation(answer(mealRows))
  const user = setupUser()
  render(<MealsPage />)
  await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.any(Object)))
  if (mealRows.length > 0) await screen.findByTestId(`meal-${(mealRows[0] as { id: string }).id}`)

  return user
}

const card = (id: string) => within(screen.getByTestId(`meal-${id}`))
const day = (index: number) => within(screen.getByTestId(`meal-day-${index}`))

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(TODAY))
  mercure.onMessage = undefined
  mockCreate.mockResolvedValue({ data: { id: '01NEW', '@id': '/api/meals/01NEW' } })
  mockDelete.mockResolvedValue({ data: {} })
  route()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  resetViewport()
})

describe('MealsPage — one page, two views of the same week', () => {
  test('opens on the Menu view: the week day by day, today marked', async () => {
    await renderPage()

    expect(screen.getByRole('button', { name: 'Menu' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Liste' })).toHaveAttribute('aria-pressed', 'false')
    for (const [index, name] of ['Lundi 13', 'Mardi 14', 'Mercredi 15', 'Jeudi 16', 'Vendredi 17', 'Samedi 18', 'Dimanche 19'].entries()) {
      expect(day(index).getByRole('heading', { name })).toBeInTheDocument()
    }
    expect(screen.getByTestId('meal-day-2')).toHaveAttribute('aria-current', 'date')
    expect(screen.getByTestId('meal-day-1')).not.toHaveAttribute('aria-current')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  test('writes the week « 13 → 19 avril », with the year only when it is not this one', async () => {
    const user = await renderPage()
    expect(screen.getByText('13 → 19 avril')).toBeInTheDocument()

    for (let i = 0; i < 4; i++) await user.click(screen.getByRole('button', { name: 'Semaine suivante' }))
    // 11 May: still this year.
    expect(screen.getByText('11 → 17 mai')).toBeInTheDocument()

    for (let i = 0; i < 33; i++) await user.click(screen.getByRole('button', { name: 'Semaine suivante' }))
    expect(screen.getByText(/→ 3 janv\. 2027$/)).toBeInTheDocument()
  })

  test('writes both months when the week straddles two', async () => {
    const user = await renderPage()

    for (let i = 0; i < 2; i++) await user.click(screen.getByRole('button', { name: 'Semaine suivante' }))

    expect(screen.getByText('27 avr. → 3 mai')).toBeInTheDocument()
  })

  test('has one row of pills and no explanatory text', async () => {
    await renderPage()

    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toBeInTheDocument()
    expect(screen.queryByText(/glisser|déplacer|cliquez/i)).not.toBeInTheDocument()
  })

  test('shows the Menu cards: each meal with its recipes and its convives', async () => {
    await renderPage()

    const meal = card('01LUNCH')
    expect(meal.getByText('Déjeuner')).toBeInTheDocument()
    expect(meal.getByText('Tartiflette')).toBeInTheDocument()
    expect(meal.getByText('Salade verte')).toBeInTheDocument()
    expect(meal.getByRole('group', { name: 'Convives du repas Déjeuner : Tartiflette, Salade verte' })).toBeInTheDocument()
    expect(meal.getByTestId('guests-value')).toHaveTextContent('2')
    expect(meal.getByText('par défaut')).toBeInTheDocument()
    expect(day(1).getByText('Tartiflette')).toBeInTheDocument()
    expect(day(3).queryByText('Tartiflette')).not.toBeInTheDocument()
    expect(day(3).getByText('Gratin de courgettes')).toBeInTheDocument()
  })

  test('toggles to the Liste view: the same week as chronological rows', async () => {
    const user = await renderPage()

    await user.click(screen.getByRole('button', { name: 'Liste' }))

    expect(screen.getByRole('button', { name: 'Liste' })).toHaveAttribute('aria-pressed', 'true')
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText(/mardi 14/i)).toBeInTheDocument()
    expect(within(rows[0]).getByText('Déjeuner')).toBeInTheDocument()
    expect(within(rows[0]).getByText(/Tartiflette, Salade verte/)).toBeInTheDocument()
    expect(within(rows[0]).getByTestId('guests-value')).toHaveTextContent('2')
    expect(within(rows[1]).getByText(/jeudi 16/i)).toBeInTheDocument()
    expect(within(rows[1]).getByText('Dîner')).toBeInTheDocument()
    expect(screen.queryByTestId('meal-day-2')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Menu' }))
    expect(screen.getByTestId('meal-day-2')).toBeInTheDocument()
  })

  test('the Liste view changes the convives and deletes a meal like the card does', async () => {
    const user = await renderPage()
    await user.click(screen.getByRole('button', { name: 'Liste' }))
    const row = within(screen.getByTestId('meal-row-01DINNER'))

    await user.click(row.getByRole('button', { name: /Un convive de plus/ }))
    await waitFor(() => expect(patches()).toEqual([['/api/meals/01DINNER', { guests: 7 }]]))

    await user.click(row.getByRole('button', { name: 'Supprimer le repas Dîner : Gratin de courgettes' }))
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('meals', expect.objectContaining({ id: '01DINNER' })))
    expect(screen.queryByTestId('meal-row-01DINNER')).not.toBeInTheDocument()
  })

  test('says so when the week has no meal, in the Liste view', async () => {
    const user = await renderPage([])

    await user.click(screen.getByRole('button', { name: 'Liste' }))

    expect(screen.getByText('Aucun repas cette semaine')).toBeInTheDocument()
  })

  test('reads the week on a phone too: one column of days', async () => {
    setViewportWidth(PHONE_WIDTH)

    await renderPage()

    for (let i = 0; i < 7; i++) expect(screen.getByTestId(`meal-day-${i}`)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '+ Ajouter un repas' })).toHaveLength(7)
  })
})

describe('MealsPage — the ingredients of the week', () => {
  test('counts the meals and the distinct products of their recipes', async () => {
    await renderPage()

    const band = within(screen.getByTestId('week-ingredients'))
    expect(band.getByText('Ingrédients de la semaine')).toBeInTheDocument()
    expect(band.getByText('2 repas')).toBeInTheDocument()
    // Pommes de terre (twice), Reblochon, Salade: three products.
    expect(band.getByText('3 produits')).toBeInTheDocument()
  })

  test('is hidden when the week has no meal', async () => {
    await renderPage([])

    expect(screen.queryByTestId('week-ingredients')).not.toBeInTheDocument()
  })

  test('« Choisir » opens the choice of the first meal whose choice is not made yet', async () => {
    const user = await renderPage()

    await user.click(within(screen.getByTestId('week-ingredients')).getByRole('button', { name: 'Choisir' }))

    expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
    expect(fetchMock).toHaveBeenCalledWith('/api/meals/01DINNER/grocery_preview', expect.anything())
  })

  test('« Choisir » opens the first meal when the API does not tell which choices are made', async () => {
    const bare = [lunch, dinner].map((meal) => ({ ...meal, groceryChoiceMadeAt: undefined }))
    const user = await renderPage(bare)

    await user.click(within(screen.getByTestId('week-ingredients')).getByRole('button', { name: 'Choisir' }))

    await screen.findByRole('checkbox', { name: 'Riz' })
    expect(fetchMock).toHaveBeenCalledWith('/api/meals/01LUNCH/grocery_preview', expect.anything())
  })

  test('« Plus tard » closes the choice and adds nothing', async () => {
    const user = await renderPage()
    await user.click(within(screen.getByTestId('week-ingredients')).getByRole('button', { name: 'Choisir' }))

    await user.click(await screen.findByRole('button', { name: 'Plus tard' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
})

describe('MealsPage — adding a meal on a day', () => {
  const addOn = async (user: ReturnType<typeof userEvent.setup>, index: number) => {
    await user.click(day(index).getByRole('button', { name: '+ Ajouter un repas' }))

    return within(await screen.findByRole('dialog'))
  }

  const pick = async (dialog: ReturnType<typeof within>, user: ReturnType<typeof userEvent.setup>, name: string) => {
    await user.click(dialog.getByLabelText('Menu'))
    await user.click(await screen.findByRole('option', { name }))
  }

  test('opens the dialog on that day, and creates a real meal with the body the API expects', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 2)

    expect(dialog.getByLabelText('Date')).toHaveValue('2026-04-15')
    await pick(dialog, user, 'Gratin de courgettes')
    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalledTimes(1))
    expect(mockCreate).toHaveBeenCalledWith('meals', { data: { date: '2026-04-15', slot: 'lunch', recipes: [gratin['@id']] } })
    expect(mockNotify).toHaveBeenCalledWith('Repas créé', { type: 'success' })
  })

  test('offers the slot still free on that day', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 1)

    expect(dialog.getByRole('combobox', { name: /Créneau/ })).toHaveTextContent('Dîner')
  })

  test('sends the slot, the convives and every recipe that was chosen', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 4)

    await user.click(dialog.getByRole('combobox', { name: /Créneau/ }))
    await user.click(await screen.findByRole('option', { name: 'Dîner' }))
    await waitFor(() => expect(dialog.getByTestId('guests-value')).toHaveTextContent('4'))
    await user.click(dialog.getByRole('button', { name: /Un convive de plus/ }))
    await pick(dialog, user, 'Tartiflette')
    await pick(dialog, user, 'Salade verte')
    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).toEqual({
      date: '2026-04-17',
      slot: 'dinner',
      recipes: [tartiflette['@id'], salade['@id']],
      guests: 5,
    })
  })

  test('refuses a meal with no recipe and keeps the dialog open', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 2)

    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/recette/i), { type: 'error' })
    expect(mockCreate).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('says so when the API refuses the meal', async () => {
    mockCreate.mockRejectedValue(new Error('boom'))
    const user = await renderPage()
    const dialog = await addOn(user, 2)
    await pick(dialog, user, 'Gratin de courgettes')

    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur lors de la création', { type: 'error' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('goes on to the choice of the ingredients once the meal exists', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 2)
    await pick(dialog, user, 'Gratin de courgettes')
    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
    expect(fetchMock).toHaveBeenCalledWith('/api/meals/01NEW/grocery_preview', expect.anything())
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
  })

  test('starts the convives from the default of Réglages › Repas, marked « par défaut »', async () => {
    const user = await renderPage()
    const dialog = await addOn(user, 2)

    await waitFor(() => expect(dialog.getByTestId('guests-value')).toHaveTextContent('4'))
    expect(dialog.getByText('par défaut')).toBeInTheDocument()
  })

  test.each([
    ['2026-04-15', '2026-04-13'],
    ['2026-10-07', '2026-10-05'],
    ['2026-12-09', '2026-12-07'],
    ['2026-10-21', '2026-10-19'],
  ])('plans on the day of the day that was chosen, not the day before (%s) — MAG-251', async (wednesday, monday) => {
    vi.setSystemTime(new Date(`${wednesday}T12:00:00Z`))
    const user = await renderPage([])
    const [, params] = mockGetList.mock.calls.find(([resource]) => resource === 'meals')!
    expect(params.filter['date[after]']).toBe(monday)
    expect(params.sort.field).toBe('date')

    const dialog = await addOn(user, 2)
    await pick(dialog, user, 'Gratin de courgettes')
    await user.click(dialog.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { data } = mockCreate.mock.calls[0][1]
    expect(data.date).toBe(wednesday)
    expect(data).not.toHaveProperty('startAt')
  })

  test('the Liste view adds a meal too, on today', async () => {
    const user = await renderPage()
    await user.click(screen.getByRole('button', { name: 'Liste' }))

    await user.click(screen.getByRole('button', { name: '+ Ajouter un repas' }))
    const dialog = within(await screen.findByRole('dialog'))

    expect(dialog.getByLabelText('Date')).toHaveValue('2026-04-15')
  })
})

describe('MealsPage — the convives of a meal', () => {
  test('changes the number with a PATCH carrying the guests only, and shows it', async () => {
    const user = await renderPage()
    route({ patch: (_u, init) => jsonOk({ guests: JSON.parse(String(init?.body)).guests, guestsIsDefault: false }) })

    await user.click(card('01LUNCH').getByRole('button', { name: /Un convive de plus/ }))

    await waitFor(() => expect(patches()).toEqual([['/api/meals/01LUNCH', { guests: 3 }]]))
    await waitFor(() => expect(card('01LUNCH').getByTestId('guests-value')).toHaveTextContent('3'))
    expect(card('01LUNCH').queryByText('par défaut')).not.toBeInTheDocument()
  })

  test('never goes below one guest', async () => {
    await renderPage([{ ...lunch, guests: 1 }])

    expect(screen.getByRole('button', { name: /Un convive de moins/ })).toBeDisabled()
  })

  test('gives a meal with its own number back to the default', async () => {
    const user = await renderPage()
    route({ patch: () => jsonOk({ guests: 2, guestsIsDefault: true }) })

    await user.click(card('01DINNER').getByRole('button', { name: 'Revenir au défaut' }))

    await waitFor(() => expect(patches()).toEqual([['/api/meals/01DINNER', { guests: null }]]))
    await waitFor(() => expect(card('01DINNER').getByTestId('guests-value')).toHaveTextContent('2'))
    expect(card('01DINNER').getByText('par défaut')).toBeInTheDocument()
  })

  test('offers no way back to the default for a meal that follows it', async () => {
    await renderPage()

    expect(card('01LUNCH').queryByRole('button', { name: 'Revenir au défaut' })).not.toBeInTheDocument()
  })

  test('keeps the number and says so when the API refuses it', async () => {
    const user = await renderPage()
    route({ patch: () => jsonKo(400) })

    await user.click(card('01LUNCH').getByRole('button', { name: /Un convive de plus/ }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Le nombre de convives n’a pas pu être enregistré', { type: 'error' }))
    expect(card('01LUNCH').getByTestId('guests-value')).toHaveTextContent('2')
  })
})

describe('MealsPage — the portions of a recipe, a simulation', () => {
  const scaledReads = () => fetchMock.mock.calls.filter(([url]) => String(url).includes('?guests=')).map(([url]) => url)

  test('starts at the guests of the meal and changes the displayed number only', async () => {
    const user = await renderPage()
    const line = within(card('01LUNCH').getByTestId('recipe-01TARTIFLETTE'))
    expect(line.getByTestId('portions-value')).toHaveTextContent('2')

    await user.click(line.getByRole('button', { name: 'Une portion de plus — Tartiflette' }))
    await user.click(line.getByRole('button', { name: 'Une portion de plus — Tartiflette' }))

    expect(line.getByTestId('portions-value')).toHaveTextContent('4')
    expect(patches()).toEqual([])
    expect(card('01LUNCH').getByTestId('guests-value')).toHaveTextContent('2')

    await user.click(line.getByRole('button', { name: 'Une portion de moins — Tartiflette' }))
    expect(line.getByTestId('portions-value')).toHaveTextContent('3')
  })

  test('never goes below one portion', async () => {
    await renderPage([{ ...lunch, guests: 1 }])

    expect(screen.getByRole('button', { name: 'Une portion de moins — Tartiflette' })).toBeDisabled()
  })

  test('shows the quantities the API computes for N people when N is not the recipe’s own servings', async () => {
    const user = await renderPage()
    const line = within(card('01LUNCH').getByTestId('recipe-01TARTIFLETTE'))

    // Tartiflette is for 4, the meal for 2: the quantities are one tap away, and read from the API.
    await user.click(line.getByRole('button', { name: 'Quantités pour 2 personnes' }))

    expect(await line.findByText('400 g Pommes de terre')).toBeInTheDocument()
    expect(scaledReads()).toEqual(['/api/recipes/01TARTIFLETTE?guests=2'])
    expect(patches()).toEqual([])
  })

  test('offers no quantities when the portions are the recipe’s own servings', async () => {
    const user = await renderPage()
    const line = within(card('01LUNCH').getByTestId('recipe-01SALADE'))

    expect(line.queryByRole('button', { name: /Quantités pour/ })).not.toBeInTheDocument()

    const tart = within(card('01LUNCH').getByTestId('recipe-01TARTIFLETTE'))
    await user.click(tart.getByRole('button', { name: 'Une portion de plus — Tartiflette' }))
    await user.click(tart.getByRole('button', { name: 'Une portion de plus — Tartiflette' }))
    expect(tart.queryByRole('button', { name: /Quantités pour/ })).not.toBeInTheDocument()
  })

  test('says so when the quantities cannot be computed', async () => {
    const user = await renderPage()
    route({ scaled: () => jsonKo(500) })
    const line = within(card('01LUNCH').getByTestId('recipe-01TARTIFLETTE'))

    await user.click(line.getByRole('button', { name: 'Quantités pour 2 personnes' }))

    expect(await line.findByRole('alert')).toHaveTextContent('Les quantités n’ont pas pu être calculées')
  })
})

describe('MealsPage — the recipes of a meal', () => {
  test('removes one recipe with a PATCH carrying the ones that remain', async () => {
    const user = await renderPage()

    await user.click(card('01LUNCH').getByRole('button', { name: 'Retirer Tartiflette du repas' }))

    await waitFor(() => expect(patches()).toEqual([['/api/meals/01LUNCH', { recipes: [salade['@id']] }]]))
    await waitFor(() => expect(card('01LUNCH').queryByText('Tartiflette')).not.toBeInTheDocument())
    expect(mockDelete).not.toHaveBeenCalled()
  })

  test('removing the last recipe deletes the meal: a meal keeps at least one', async () => {
    const user = await renderPage()

    await user.click(card('01DINNER').getByRole('button', { name: 'Retirer Gratin de courgettes du repas' }))

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('meals', expect.objectContaining({ id: '01DINNER' })))
    expect(patches()).toEqual([])
    expect(screen.queryByTestId('meal-01DINNER')).not.toBeInTheDocument()
    expect(mockNotify).toHaveBeenCalledWith('Repas supprimé', { type: 'success' })
  })

  test('adds a recipe with a PATCH carrying the recipes it has and the new one', async () => {
    const user = await renderPage()

    await user.click(card('01DINNER').getByRole('button', { name: '+ Recette' }))
    await user.click(await screen.findByRole('option', { name: 'Salade verte' }))

    await waitFor(() => expect(patches()).toEqual([['/api/meals/01DINNER', { recipes: [gratin['@id'], salade['@id']] }]]))
    expect(await card('01DINNER').findByText('Salade verte')).toBeInTheDocument()
  })

  test('does not offer a recipe the meal already has', async () => {
    const user = await renderPage()

    await user.click(card('01LUNCH').getByRole('button', { name: '+ Recette' }))

    expect(await screen.findByRole('option', { name: 'Gratin de courgettes' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Tartiflette' })).not.toBeInTheDocument()
  })

  test('keeps the recipes and says so when the API refuses the change', async () => {
    const user = await renderPage()
    route({ patch: () => jsonKo(422) })

    await user.click(card('01LUNCH').getByRole('button', { name: 'Retirer Tartiflette du repas' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Les recettes du repas n’ont pas pu être enregistrées', { type: 'error' }))
    expect(card('01LUNCH').getByText('Tartiflette')).toBeInTheDocument()
  })

  test('names a recipe it does not know by the summary of a one-recipe meal', async () => {
    mockGetList.mockImplementation(answer([dinner], []))
    render(<MealsPage />)

    expect(await screen.findByText('Dîner : Gratin de courgettes')).toBeInTheDocument()
  })
})

describe('MealsPage — a planned meal', () => {
  test('deletes the meal and says so', async () => {
    const user = await renderPage()

    await user.click(card('01LUNCH').getByRole('button', { name: 'Supprimer le repas Déjeuner : Tartiflette, Salade verte' }))

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('meals', expect.objectContaining({ id: '01LUNCH' })))
    expect(mockNotify).toHaveBeenCalledWith('Repas supprimé', { type: 'success' })
    expect(screen.queryByTestId('meal-01LUNCH')).not.toBeInTheDocument()
  })

  test('says so when the meal cannot be deleted', async () => {
    mockDelete.mockRejectedValue(new Error('boom'))
    const user = await renderPage()

    await user.click(card('01LUNCH').getByRole('button', { name: /Supprimer le repas/ }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur lors de la suppression', { type: 'error' }))
    expect(screen.getByTestId('meal-01LUNCH')).toBeInTheDocument()
  })

  test('reopens the choice of its ingredients, without creating anything', async () => {
    const user = await renderPage()

    await user.click(card('01LUNCH').getByRole('button', { name: 'Revoir les ingrédients à acheter pour Déjeuner : Tartiflette, Salade verte' }))

    expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
    expect(fetchMock).toHaveBeenCalledWith('/api/meals/01LUNCH/grocery_preview', expect.anything())
    expect(mockCreate).not.toHaveBeenCalled()
  })

  test('« Ajouter aux courses » sends the choice for that meal', async () => {
    const user = await renderPage()
    await user.click(card('01LUNCH').getByRole('button', { name: /Revoir les ingrédients/ }))

    await user.click(await screen.findByRole('button', { name: 'Ajouter aux courses' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(post[0]).toBe('/api/meals/01LUNCH/grocery_items')
    expect(JSON.parse(post[1].body)).toEqual({ ingredients: [{ ingredientId: '01RICE' }] })
  })
})

describe('MealsPage — the week', () => {
  test('asks for the week by day, Monday to Sunday, and changes week', async () => {
    const user = await renderPage()
    const asked = () => mockGetList.mock.calls.filter(([resource]) => resource === 'meals').map(([, params]) => params.filter)
    expect(asked()[0]).toEqual({ 'date[after]': '2026-04-13', 'date[before]': '2026-04-19' })

    await user.click(screen.getByRole('button', { name: 'Semaine suivante' }))
    await waitFor(() => expect(asked().at(-1)).toEqual({ 'date[after]': '2026-04-20', 'date[before]': '2026-04-26' }))

    await user.click(screen.getByRole('button', { name: 'Semaine précédente' }))
    await user.click(screen.getByRole('button', { name: 'Semaine précédente' }))
    await waitFor(() => expect(asked().at(-1)).toEqual({ 'date[after]': '2026-04-06', 'date[before]': '2026-04-12' }))

    await user.click(screen.getByRole('button', { name: "Aujourd'hui" }))
    await waitFor(() => expect(asked().at(-1)).toEqual({ 'date[after]': '2026-04-13', 'date[before]': '2026-04-19' }))
  })

  test('reads the week again when the hub publishes a meal', async () => {
    await renderPage()
    const reads = () => mockGetList.mock.calls.filter(([resource]) => resource === 'meals').length
    const before = reads()

    await act(async () => {
      mercure.onMessage?.()
    })

    expect(reads()).toBe(before + 1)
  })

  test('tells the user when the meals cannot be loaded', async () => {
    mockGetList.mockImplementation((resource: string) =>
      resource === 'meals' ? Promise.reject(new Error('boom')) : answer()(resource),
    )

    render(<MealsPage />)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur lors du chargement des repas', { type: 'error' }))
  })

  test('tells the user when the recipes cannot be loaded', async () => {
    mockGetList.mockImplementation((resource: string) =>
      resource === 'recipes' ? Promise.reject(new Error('boom')) : answer()(resource),
    )

    render(<MealsPage />)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur lors du chargement des recettes', { type: 'error' }))
  })
})
