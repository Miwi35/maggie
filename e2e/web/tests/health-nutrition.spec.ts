import { test, expect, parisDay } from '../fixtures/index.js'
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
