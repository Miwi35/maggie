import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
import { HealthNutritionPage } from '../pages/HealthNutritionPage.js'

/**
 * Santé › Nutrition, « Mangé » on a planned meal — MAG-748.
 *
 * Given un déjeuner prévu aujourd'hui avec la recette « Bol quinoa poulet »
 * (515 kcal la portion, 38 g de protéines, 53 g de glucides, 16 g de lipides),
 * When j'ouvre Santé › Nutrition et j'appuie sur « Mangé »,
 * Then le bilan passe du prévu au mangé : « Prévu » perd 515 kcal, « Mangé » les gagne,
 * à l'écran comme dans `GET /api/health/daily-balance`.
 *
 * Nouveau parcours Santé › Nutrition, voisin de Santé › Suivi (MAG-706). The
 * account is this journey's own; the meal and its intake are removed at the end
 * all the same, even on failure.
 *
 * Jeûne intermittent — MAG-770 (second test):
 * Given a 16:8 fast whose window opened at the last full hour, and an apple
 * eaten a minute ago (so the window is open whatever the hour),
 * When I change « Protocole » to « 14:10 » on Santé › Nutrition,
 * Then « Fenêtre du jour » ends 2 h later, on screen as in `GET /api/health/fasting`.
 * The profile is put back and the apple removed at the end, even on failure.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Balance {
  eatenKcal: number
  plannedKcal: number
  proteinG: number
  plannedMeals: { mealId: string; kcal: number | null }[]
}

const balanceOf = async (api: APIRequestContext, day: string): Promise<Balance> => {
  const response = await api.get(`/api/health/daily-balance?date=${day}`, { headers: { Accept: 'application/json' } })
  expect(response.status(), `GET /api/health/daily-balance answered ${response.status()}`).toBe(200)

  return (await response.json()) as Balance
}

/** The meal this journey planned and the intake « Mangé » made of it: no other journey may find them. */
async function removeMeal(api: APIRequestContext, day: string, mealId: string | null): Promise<void> {
  if (mealId === null) return
  const intakes = await getCollection<{ '@id': string; mealId?: string }>(api, `/api/health/food-intakes?date[after]=${day}&date[before]=${day}`)
  for (const iri of [...intakes.filter((row) => row.mealId === mealId).map((row) => row['@id']), `/api/meals/${mealId}`]) {
    const removed = await api.delete(iri)
    expect(removed.ok() || removed.status() === 404, `DELETE ${iri} answered ${removed.status()}`).toBeTruthy()
  }
}

/** « 1 030 », as the page writes it. */
const kcal = (value: number): string => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(value)

test.use({ journeyAccount: 'standard' })

test('Santé › Nutrition: « Mangé » moves the planned lunch into what was eaten — MAG-748', async ({ page, api, journeyUser }) => {
  const today = parisDay()
  let mealId: string | null = null

  try {
    // Given a lunch planned today with the seed's « Bol quinoa poulet »…
    const planned = await api.post('/api/meals', {
      headers: jsonLd,
      data: {
        date: today,
        slot: 'lunch',
        agenda: `/api/agendas/${journeyUser.ref('e2e_agenda_personal')}`,
        recipes: [`/api/recipes/${journeyUser.ref('e2e_health_recipe_bowl')}`],
      },
    })
    expect(planned.status(), `POST /api/meals answered ${planned.status()}: ${await planned.text()}`).toBe(201)
    mealId = ((await planned.json()) as { id: string }).id
    const meal = mealId

    // …which the day's balance counts as planned, one serving: 515 kcal.
    await expect
      .poll(async () => (await balanceOf(api, today)).plannedMeals.find((row) => row.mealId === meal)?.kcal, {
        message: 'the planned lunch never reached the day’s balance',
        timeout: 30_000,
      })
      .toBe(515)
    const before = await balanceOf(api, today)

    // When I open Santé › Nutrition…
    const nutrition = new HealthNutritionPage(page)
    await nutrition.goto('/')
    await nutrition.expectLoaded()
    await nutrition.navigateTo('Nutrition', 'Santé')
    await expect(nutrition.balanceCard).toBeVisible()
    await expect(nutrition.planned).toHaveText(`Prévu ${kcal(before.plannedKcal)} kcal`)
    await expect(nutrition.plannedRow(meal)).toContainText('515 kcal')

    // …and press « Mangé » on it,
    const intakeId = await nutrition.markEaten(meal)

    // Then the bilan moves from planned to eaten, on screen…
    const eaten = before.eatenKcal + 515
    const left = before.plannedKcal - 515
    await expect(nutrition.eaten).toHaveText(`Mangé ${kcal(eaten)} kcal`)
    await expect(nutrition.planned).toHaveText(`Prévu ${kcal(left)} kcal`)
    await expect(nutrition.plannedRow(meal)).toHaveCount(0)
    await expect(nutrition.intakeRow(intakeId).getByTestId('intake-source')).toHaveText('Repas')

    // …and in the API: the meal is no longer planned, it is eaten, its macros counted once.
    const after = await balanceOf(api, today)
    expect(after.plannedMeals.map((row) => row.mealId)).not.toContain(meal)
    expect(after).toMatchObject({ eatenKcal: eaten, plannedKcal: left })
    expect(after.proteinG).toBeCloseTo(before.proteinG, 1)
  } finally {
    await removeMeal(api, today, mealId)
  }
})

interface Fast {
  fastingProtocol: string | null
  fastingWindowStart: string
}

const json = { 'Content-Type': 'application/json', Accept: 'application/json' }

const PARIS_CLOCK = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Paris', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

/** `14:05:33`, an instant's time in Paris. */
const parisClock = (instant: Date): string => PARIS_CLOCK.format(instant)

/** « 20 h 00 », as the « Jeûne » card writes a full hour. */
const hourLabel = (hour: number): string => `${hour % 24} h 00`

async function patchFast(api: APIRequestContext, fast: Partial<Fast>): Promise<Fast> {
  const response = await api.patch('/api/health/profile', { headers: json, data: fast })
  expect(response.status(), `PATCH /api/health/profile answered ${response.status()}: ${await response.text()}`).toBe(200)

  return (await response.json()) as Fast
}

test('Santé › Nutrition: a new fasting protocol moves the end of the day’s window — MAG-770', async ({ page, api }) => {
  const now = e2eNow()
  const today = parisDay(now)
  const start = (Number(parisClock(now).slice(0, 2)) + 23) % 24
  const { retry } = test.info()
  const read = await api.get('/api/health/profile', { headers: json })
  expect(read.status(), `GET /api/health/profile answered ${read.status()}`).toBe(200)
  const before = (await read.json()) as Fast
  let intakeIri: string | null = null

  try {
    // Given a 16:8 fast whose window opened at the last full hour…
    await patchFast(api, { fastingProtocol: '16:8', fastingWindowStart: `${String(start).padStart(2, '0')}:00` })
    // …and an apple eaten a minute ago: the window is open.
    const eaten = new Date(now.getTime() - 60_000)
    const intake = await api.post('/api/health/food-intakes', {
      headers: jsonLd,
      data: { eatenAt: parisTime(parisDay(eaten), parisClock(eaten)), label: retry === 0 ? 'Pomme' : `Pomme essai ${retry}`, kcal: 50 },
    })
    expect(intake.status(), `POST /api/health/food-intakes answered ${intake.status()}: ${await intake.text()}`).toBe(201)
    intakeIri = ((await intake.json()) as { '@id': string })['@id']

    // When I open Santé › Nutrition, the window ends 8 h after its start…
    const nutrition = new HealthNutritionPage(page)
    await nutrition.open()
    await expect(nutrition.fastingProtocol).toHaveValue('16:8')
    await expect(nutrition.fastingWindow).toContainText(`→ ${hourLabel(start + 8)}`)

    // …and I change « Protocole » to « 14:10 »,
    await nutrition.pickProtocol('14:10')

    // Then « Fenêtre du jour » ends 2 h later, on screen…
    await expect(nutrition.fastingWindow).toContainText(`→ ${hourLabel(start + 10)}`)
    await expect(nutrition.fastingProtocol).toHaveValue('14:10')

    // …and in the API.
    const stored = (await (await api.get('/api/health/profile', { headers: json })).json()) as Fast
    expect(stored.fastingProtocol).toBe('14:10')
    const fasting = await api.get(`/api/health/fasting?date=${today}`, { headers: json })
    expect(fasting.status(), `GET /api/health/fasting answered ${fasting.status()}`).toBe(200)
    expect(await fasting.json()).toMatchObject({ protocol: '14:10', windowEnd: `${String((start + 10) % 24).padStart(2, '0')}:00` })
  } finally {
    if (intakeIri !== null) {
      const removed = await api.delete(intakeIri)
      expect(removed.ok() || removed.status() === 404, `DELETE ${intakeIri} answered ${removed.status()}`).toBeTruthy()
    }
    await patchFast(api, { fastingProtocol: before.fastingProtocol, fastingWindowStart: before.fastingWindowStart })
  }
})
