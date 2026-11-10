import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { NutritionPage } from './NutritionPage'
import type { DailyBalance } from './nutritionBalance'
import type { FoodIntake } from './nutritionApi'
import type { FastingDay } from './fasting'
import type { FastingProtocol } from './healthProfileApi'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const TODAY = '2026-10-21'

const MEAL = { mealId: '01MEAL', slot: 'lunch' as const, label: 'Bol quinoa poulet', kcal: 515, proteinG: 38, carbsG: 53, fatG: 16 }

const APPLE: FoodIntake = {
  id: '01APPLE',
  date: TODAY,
  eatenAt: '2026-10-21T10:30:00+02:00',
  ciqualCode: '13039',
  label: 'Pomme',
  quantityGrams: 150,
  kcal: 78,
  proteinG: 0.4,
  carbsG: 18.5,
  fatG: 0.3,
  source: 'manual',
}

const balanceOf = (over: Partial<DailyBalance> = {}): DailyBalance => ({
  date: TODAY,
  eatenKcal: 78,
  plannedKcal: 515,
  expendedKcal: 1900,
  balanceKcal: -1307,
  proteinG: 38.4,
  carbsG: 71.5,
  fatG: 16.3,
  plannedMeals: [MEAL],
  ...over,
})

/** The profile's fast (MAG-770) and the window the API derives from it. */
interface Fast {
  fastingProtocol: FastingProtocol | null
  fastingWindowStart: string
}

const WINDOW_HOURS: Record<FastingProtocol, number> = { '14:10': 10, '16:8': 8, '18:6': 6, '20:4': 4 }

const fastingOf = (fast: Fast, over: Partial<FastingDay> = {}): FastingDay => {
  const [hours, minutes] = fast.fastingWindowStart.split(':')
  const end = fast.fastingProtocol === null ? 0 : (Number(hours) + WINDOW_HOURS[fast.fastingProtocol]) % 24

  return {
    date: TODAY,
    protocol: fast.fastingProtocol,
    windowStart: fast.fastingWindowStart,
    windowEnd: `${String(end).padStart(2, '0')}:${minutes}`,
    openedAt: '2026-10-21T12:10:00+02:00',
    lastIntakeAt: '2026-10-21T12:10:00+02:00',
    state: fast.fastingProtocol === null ? null : 'open',
    nightFastMinutes: 965,
    ...over,
  }
}

interface Server {
  balance: DailyBalance | 'fail'
  intakes: FoodIntake[]
  /** What a POST answers; the default records it. */
  post?: (body: Record<string, unknown>) => Response
  fast?: Fast
  /** What a PATCH of the profile answers; the default applies it. */
  patch?: (body: Record<string, unknown>) => Response
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The API of the page: the day's balance, its 7 days, the journal and its writes. */
function stubApi(server: Server) {
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    const { pathname, searchParams } = new URL(url, 'http://localhost')
    const method = init.method ?? 'GET'
    if (pathname === '/api/health/daily-balance') {
      if (server.balance === 'fail') return json({}, 500)
      if (searchParams.has('date')) return json(server.balance)
      const days = []
      for (let d = new Date(`${searchParams.get('from')}T12:00:00Z`); d <= new Date(`${searchParams.get('to')}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + 1)) {
        const date = d.toISOString().slice(0, 10)
        const day = { ...server.balance, plannedMeals: undefined }
        days.push(date === TODAY ? day : { ...day, date, balanceKcal: 200 })
      }

      return json({ days })
    }
    if (pathname === '/api/health/food-intakes' && method === 'GET') return json({ member: server.intakes })
    if (pathname === '/api/health/food-intakes' && method === 'POST') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      if (server.post) return server.post(body)

      return json({ id: '01NEW', ...body }, 201)
    }
    if (pathname.startsWith('/api/health/food-intakes/') && method === 'DELETE') {
      server.intakes = server.intakes.filter((intake) => !pathname.endsWith(intake.id))

      return new Response(null, { status: 204 })
    }
    const fast = (server.fast ??= { fastingProtocol: '16:8', fastingWindowStart: '12:00' })
    if (pathname === '/api/health/profile' && method === 'GET') return json({ id: '01PROFILE', ...fast })
    if (pathname === '/api/health/profile' && method === 'PATCH') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      if (server.patch) return server.patch(body)
      server.fast = { ...fast, ...body }

      return json({ id: '01PROFILE', ...server.fast })
    }
    if (pathname === '/api/health/fasting') return json(fastingOf(fast))
    if (pathname === '/api/health/days') {
      return json({ days: [{ date: TODAY, nightFastMinutes: 965 }, { date: '2026-10-20', nightFastMinutes: 1000 }] })
    }
    if (pathname === '/ciqual/foods') {
      return json([
        { alim_code: '13039', alim_name_fr: 'Pomme, crue', alim_group_name_fr: 'Fruits', kcal_per100g: 52, protein_per100g: 0.3, carbs_per100g: 12.3, fat_per100g: 0.2 },
      ])
    }

    return json({}, 404)
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

const calls = (fetcher: ReturnType<typeof stubApi>, method: string, path: string) =>
  fetcher.mock.calls.filter(([url, init]) => (init?.method ?? 'GET') === method && new URL(url, 'http://localhost').pathname === path)

const bodyOf = (fetcher: ReturnType<typeof stubApi>, method: string, path: string) =>
  JSON.parse(String(calls(fetcher, method, path).at(-1)?.[1]?.body)) as Record<string, unknown>

function renderPage() {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <NutritionPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )
}

const bilan = () => screen.getByRole('region', { name: 'Bilan du jour' })
const journal = () => screen.getByRole('region', { name: 'Journal' })

describe('NutritionPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 21, 15, 0))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('shows the bilan first, then the 7 days, the macros and the journal of today', async () => {
    const fetcher = stubApi({ balance: balanceOf(), intakes: [APPLE] })
    renderPage()

    expect(await screen.findByTestId('balance-value')).toHaveTextContent(/^−1\s307 kcal$/)
    expect(within(bilan()).getByTestId('balance-parts')).toHaveTextContent(/^Mangé 78 kcal · Prévu 515 kcal · Dépensé 1\s900 kcal$/)
    expect(within(bilan()).queryByTestId('balance-word')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getAllByRole('region').map((region) => region.getAttribute('aria-label'))).toEqual([
        'Bilan du jour',
        '7 jours',
        'Macros',
        'Jeûne',
        'Jeûnes, 14 jours',
        'Journal',
        'Ajouter',
      ]),
    )
    expect(screen.getByTestId('nutrition-day')).toHaveTextContent('Aujourd’hui')

    await waitFor(() => expect(screen.getAllByTestId('week-day')).toHaveLength(7))
    expect(screen.getByRole('img', { name: /^Aujourd’hui : −1\s307 kcal$/ })).toBeInTheDocument()
    expect(calls(fetcher, 'GET', '/api/health/daily-balance').map(([url]) => url)).toEqual(
      expect.arrayContaining(['/api/health/daily-balance?date=2026-10-21', '/api/health/daily-balance?from=2026-10-15&to=2026-10-21']),
    )

    expect(screen.getByTestId('macro-protein')).toHaveTextContent('38,4 g')
    expect(screen.getByTestId('macro-carbs')).toHaveTextContent('71,5 g')
    expect(screen.getByTestId('macro-fat')).toHaveTextContent('16,3 g')

    const apple = await within(journal()).findByTestId('intake-row-01APPLE')
    expect(apple).toHaveTextContent('Pomme')
    expect(apple).toHaveTextContent('10:30 · 150 g')
    expect(apple).toHaveTextContent('78 kcal')
    expect(within(apple).getByTestId('intake-source')).toHaveTextContent('Saisie')
    expect(calls(fetcher, 'GET', '/api/health/food-intakes')[0][0]).toBe('/api/health/food-intakes?date[after]=2026-10-21&date[before]=2026-10-21')

    const planned = within(journal()).getByTestId('planned-row-01MEAL')
    expect(planned).toHaveTextContent('Bol quinoa poulet')
    expect(planned).toHaveTextContent('Prévu · Déjeuner')
    expect(within(planned).getByRole('button', { name: 'Mangé : Bol quinoa poulet' })).toBeInTheDocument()
  })

  test('+100 kcal reads « Équilibre », in a neutral ink, never an alert colour', async () => {
    stubApi({ balance: balanceOf({ balanceKcal: 100 }), intakes: [] })
    renderPage()

    const value = await screen.findByTestId('balance-value')
    expect(value).toHaveTextContent('+100 kcal')
    expect(value).toHaveAttribute('data-zone', 'balanced')
    expect(within(bilan()).getByTestId('balance-word')).toHaveTextContent('Équilibre')
    const fill = within(bilan()).getByTestId('balance-fill')
    expect(fill).toHaveStyle({ backgroundColor: veilleuseLightTheme.palette.text.secondary })
    expect(fill).not.toHaveStyle({ backgroundColor: veilleuseLightTheme.palette.error.main })
    expect(value).not.toHaveStyle({ color: veilleuseLightTheme.palette.error.main })
    expect(value).not.toHaveStyle({ color: veilleuseLightTheme.palette.warning.main })
  })

  test('without an expenditure, it shows what was eaten and planned and points to Configuration', async () => {
    stubApi({ balance: balanceOf({ expendedKcal: null, balanceKcal: null }), intakes: [] })
    renderPage()

    const hint = await screen.findByTestId('balance-incomplete')
    expect(hint).toHaveTextContent('Complétez votre profil (Configuration) pour voir le bilan')
    expect(within(hint).getByRole('link', { name: 'Configuration' })).toHaveAttribute('href', '/health/configuration')
    expect(within(bilan()).getByTestId('balance-parts')).toHaveTextContent(/^Mangé 78 kcal · Prévu 515 kcal$/)
    expect(screen.queryByTestId('balance-value')).not.toBeInTheDocument()
  })

  test('« Mangé » posts the meal alone, then the bilan moves from planned to eaten', async () => {
    const user = userEvent.setup()
    const server: Server = { balance: balanceOf(), intakes: [] }
    server.post = (body) => {
      server.balance = balanceOf({ eatenKcal: 515, plannedKcal: 0, balanceKcal: -1385, plannedMeals: [] })
      server.intakes = [{ ...APPLE, id: '01EATEN', mealId: MEAL.mealId, label: MEAL.label, kcal: 515, source: 'meal', quantityGrams: undefined }]

      return json({ id: '01EATEN', ...body }, 201)
    }
    const fetcher = stubApi(server)
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Mangé : Bol quinoa poulet' }))

    expect(bodyOf(fetcher, 'POST', '/api/health/food-intakes')).toEqual({ mealId: '01MEAL' })
    await waitFor(() => expect(within(bilan()).getByTestId('balance-parts')).toHaveTextContent(/^Mangé 515 kcal · Prévu 0 kcal/))
    const eaten = await within(journal()).findByTestId('intake-row-01EATEN')
    expect(within(eaten).getByTestId('intake-source')).toHaveTextContent('Repas')
    expect(within(journal()).queryByTestId('planned-row-01MEAL')).not.toBeInTheDocument()
  })

  test('a meal already eaten shows the API’s message and no second row', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({
      balance: balanceOf(),
      intakes: [APPLE],
      post: () => json({ title: 'An error occurred', detail: 'This meal is already eaten.' }, 400),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Mangé : Bol quinoa poulet' }))

    expect(await screen.findByText('This meal is already eaten.')).toBeInTheDocument()
    expect(calls(fetcher, 'POST', '/api/health/food-intakes')).toHaveLength(1)
    expect(within(journal()).getAllByRole('listitem')).toHaveLength(2)
  })

  test('the bin deletes the intake and the journal reads the day again', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ balance: balanceOf(), intakes: [APPLE] })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Supprimer Pomme' }))

    expect(calls(fetcher, 'DELETE', '/api/health/food-intakes/01APPLE')).toHaveLength(1)
    await waitFor(() => expect(within(journal()).queryByTestId('intake-row-01APPLE')).not.toBeInTheDocument())
  })

  test('a Ciqual food and its grams are posted for today at the time typed', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
    renderPage()
    const form = await screen.findByRole('region', { name: 'Ajouter' })

    await user.type(within(form).getByRole('combobox', { name: 'Aliment Ciqual' }), 'pomme')
    await user.click(await screen.findByRole('option', { name: /Pomme, crue/ }))
    await user.type(within(form).getByRole('textbox', { name: 'Grammes' }), '150')
    const time = within(form).getByLabelText('Heure')
    expect(time).toHaveValue('15:00')
    await user.clear(time)
    await user.type(time, '12:30')
    await user.click(within(form).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(calls(fetcher, 'POST', '/api/health/food-intakes')).toHaveLength(1))
    expect(bodyOf(fetcher, 'POST', '/api/health/food-intakes')).toEqual({
      eatenAt: '2026-10-21T12:30:00+02:00',
      ciqualCode: '13039',
      quantityGrams: 150,
    })
  })

  test('a dish typed by hand is posted with its label, kcal and the macros given', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
    renderPage()
    const form = await screen.findByRole('region', { name: 'Ajouter' })

    await user.click(within(form).getByRole('button', { name: 'Saisie libre' }))
    await user.type(within(form).getByRole('textbox', { name: 'Libellé' }), 'Salade César')
    await user.type(within(form).getByRole('textbox', { name: 'kcal' }), '450')
    await user.type(within(form).getByRole('textbox', { name: 'Protéines (g)' }), '25,5')
    await user.click(within(form).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(calls(fetcher, 'POST', '/api/health/food-intakes')).toHaveLength(1))
    expect(bodyOf(fetcher, 'POST', '/api/health/food-intakes')).toEqual({
      eatenAt: '2026-10-21T15:00:00+02:00',
      label: 'Salade César',
      kcal: 450,
      proteinG: 25.5,
    })
  })

  test('an incomplete form says what is missing and sends nothing', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
    renderPage()
    const form = await screen.findByRole('region', { name: 'Ajouter' })

    await user.click(within(form).getByRole('button', { name: 'Ajouter' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('Choisissez un aliment Ciqual.')
    expect(calls(fetcher, 'POST', '/api/health/food-intakes')).toHaveLength(0)
  })

  test('an addition the API refuses shows its message', async () => {
    const user = userEvent.setup()
    stubApi({
      balance: balanceOf(),
      intakes: [],
      post: () => json({ detail: 'kcal: This value should be between 0 and 10000.' }, 422),
    })
    renderPage()
    const form = await screen.findByRole('region', { name: 'Ajouter' })

    await user.click(within(form).getByRole('button', { name: 'Saisie libre' }))
    await user.type(within(form).getByRole('textbox', { name: 'Libellé' }), 'Gâteau')
    await user.type(within(form).getByRole('textbox', { name: 'kcal' }), '9000')
    await user.click(within(form).getByRole('button', { name: 'Ajouter' }))

    expect(await screen.findByText('kcal: This value should be between 0 and 10000.')).toBeInTheDocument()
    // The form keeps what was typed.
    expect(within(form).getByRole('textbox', { name: 'Libellé' })).toHaveValue('Gâteau')
  })

  test('the day before is one click away, and the next day never goes past today', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
    renderPage()

    expect(await screen.findByRole('button', { name: 'Jour suivant' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Jour précédent' }))

    expect(screen.getByTestId('nutrition-day')).toHaveTextContent('mar. 20 oct.')
    await waitFor(() =>
      expect(calls(fetcher, 'GET', '/api/health/daily-balance').map(([url]) => url)).toContain('/api/health/daily-balance?date=2026-10-20'),
    )
  })

  test('says in French when the bilan cannot be read', async () => {
    stubApi({ balance: 'fail', intakes: [] })
    renderPage()

    expect(await screen.findByText('Erreur : le bilan du jour n’a pas pu être chargé.')).toBeInTheDocument()
  })

  describe('Jeûne (MAG-770)', () => {
    const card = () => screen.getByTestId('fasting-card')

    test('an open window counts down to its end, with the day’s window and the night’s fast', async () => {
      vi.setSystemTime(new Date(2026, 9, 21, 17, 40))
      const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
      renderPage()

      expect(await screen.findByTestId('fasting-status')).toHaveTextContent('fenêtre ouverte · 2 h 20 restantes')
      expect(within(card()).getByRole('combobox', { name: 'Protocole' })).toHaveValue('16:8')
      expect(screen.getByTestId('fasting-protocol')).toHaveValue('16:8')
      expect(screen.getByTestId('fasting-window-start')).toHaveValue('12:00')
      expect(screen.getByTestId('fasting-window')).toHaveTextContent('12 h 10 → 20 h 00')
      expect(screen.getByTestId('fasting-night')).toHaveTextContent('16 h 05')
      expect(calls(fetcher, 'GET', '/api/health/fasting')[0][0]).toBe('/api/health/fasting?date=2026-10-21')
    })

    test('« Jeûnes · 14 jours » draws 14 nights, the day in coral and a line at the protocol’s hours', async () => {
      const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
      renderPage()

      const chart = await screen.findByRole('region', { name: 'Jeûnes, 14 jours' })
      await waitFor(() => expect(within(chart).getAllByTestId('fasting-bar')).toHaveLength(14))
      const bars = within(chart).getAllByTestId('fasting-bar')
      expect(bars[13]).toHaveAttribute('data-current', 'true')
      expect(bars[13]).toHaveAccessibleName('mer. 21 oct. : 16 h 05')
      expect(bars[13]).toHaveStyle({ backgroundColor: veilleuseLightTheme.palette.primary.main })
      expect(bars[12]).toHaveAccessibleName('mar. 20 oct. : 16 h 40')
      expect(bars[0]).toHaveAccessibleName('jeu. 8 oct. : pas de jeûne mesuré')
      expect(within(chart).getByTestId('fasting-goal')).toHaveStyle({ bottom: '80.0%' })
      expect(within(chart).getByTestId('fasting-chart-protocol')).toHaveTextContent('16:8')
      expect(calls(fetcher, 'GET', '/api/health/days')[0][0]).toBe('/api/health/days?from=2026-10-08&to=2026-10-21')
    })

    test('a new protocol is PATCHed alone, then the day’s window is read again', async () => {
      const user = userEvent.setup()
      const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
      renderPage()
      await waitFor(() => expect(screen.getByTestId('fasting-window')).toHaveTextContent('12 h 10 → 20 h 00'))

      await user.selectOptions(screen.getByTestId('fasting-protocol'), '14:10')

      await waitFor(() => expect(calls(fetcher, 'PATCH', '/api/health/profile')).toHaveLength(1))
      expect(bodyOf(fetcher, 'PATCH', '/api/health/profile')).toEqual({ fastingProtocol: '14:10' })
      await waitFor(() => expect(screen.getByTestId('fasting-window')).toHaveTextContent('12 h 10 → 22 h 00'))
      expect(screen.getByTestId('fasting-protocol')).toHaveValue('14:10')
    })

    test('a new start of the window is PATCHed when the field is left', async () => {
      const user = userEvent.setup()
      const fetcher = stubApi({ balance: balanceOf(), intakes: [] })
      renderPage()
      const start = await screen.findByTestId('fasting-window-start')

      await user.clear(start)
      await user.type(start, '13:00')
      await user.tab()

      await waitFor(() => expect(calls(fetcher, 'PATCH', '/api/health/profile')).toHaveLength(1))
      expect(bodyOf(fetcher, 'PATCH', '/api/health/profile')).toEqual({ fastingWindowStart: '13:00' })
      await waitFor(() => expect(screen.getByTestId('fasting-window')).toHaveTextContent('12 h 10 → 21 h 00'))
    })

    test('a refused protocol says so and keeps the previous one', async () => {
      const user = userEvent.setup()
      const fetcher = stubApi({ balance: balanceOf(), intakes: [], patch: () => json({ error: 'Invalid fasting protocol.' }, 400) })
      renderPage()
      await screen.findByTestId('fasting-window')

      await user.selectOptions(screen.getByTestId('fasting-protocol'), '20:4')

      expect(await screen.findByText('Valeur refusée : rien n’a été enregistré.')).toBeInTheDocument()
      await waitFor(() => expect(screen.getByTestId('fasting-protocol')).toHaveValue('16:8'))
      expect(screen.getByTestId('fasting-window')).toHaveTextContent('12 h 10 → 20 h 00')
      expect(calls(fetcher, 'PATCH', '/api/health/profile')).toHaveLength(1)
    })

    test('« Aucun » stops fasting: only the protocol is left, to take it up again', async () => {
      const user = userEvent.setup()
      const fetcher = stubApi({ balance: balanceOf(), intakes: [], fast: { fastingProtocol: '16:8', fastingWindowStart: '12:00' } })
      renderPage()
      await screen.findByTestId('fasting-window')

      await user.selectOptions(screen.getByTestId('fasting-protocol'), 'Aucun')

      await waitFor(() => expect(calls(fetcher, 'PATCH', '/api/health/profile')).toHaveLength(1))
      expect(bodyOf(fetcher, 'PATCH', '/api/health/profile')).toEqual({ fastingProtocol: null })
      await waitFor(() => expect(screen.queryByTestId('fasting-window')).not.toBeInTheDocument())
      expect(screen.queryByTestId('fasting-window-start')).not.toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Jeûnes, 14 jours' })).not.toBeInTheDocument()
      expect(screen.queryByTestId('fasting-status')).not.toBeInTheDocument()
      expect(screen.getByTestId('fasting-protocol')).toHaveValue('none')
    })
  })
})
