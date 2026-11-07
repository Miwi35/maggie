import { test, expect } from '../fixtures/index.js'
import { e2eNow } from '../fixtures/clock.js'
import { HealthNutritionPage } from '../pages/HealthNutritionPage.js'
import { HealthSuiviPage } from '../pages/HealthSuiviPage.js'

/**
 * Santé › Suivi, the weight card — MAG-706.
 *
 * Given le seed (ten weighings over 30 days, one excluded, none today),
 * When j'ouvre Santé › Suivi et j'ajoute une pesée de 71,8 kg aujourd'hui,
 * Then la carte Poids affiche 71,8 kg pour aujourd'hui.
 *
 * Nouveau parcours Santé › Suivi: Santé › Sport (MAG-705) is its neighbour, not
 * an earlier journey of the same page.
 */

interface MeasurementRow {
  weightKg: number | null
  source: string
  excluded: boolean
}

test('Santé › Suivi shows an added 71,8 kg weighing as today’s weight — MAG-706', async ({ page, api }) => {
  const suivi = new HealthSuiviPage(page)
  await suivi.goto('/')
  await suivi.expectLoaded()

  // When I open Santé › Suivi…
  await suivi.navigateTo('Suivi', 'Santé')
  await expect(suivi.weightCard).toBeVisible()
  await expect(suivi.periodChoice('30 j')).toHaveAttribute('aria-checked', 'true')
  await expect(suivi.selectedLabel).toHaveText('Aujourd’hui')
  // The seed's weighings are listed, the excluded one struck through.
  await expect(suivi.weighings.getByRole('button', { name: /^Réintégrer la pesée du / })).toHaveCount(1)

  // …and add a weighing of 71,8 kg today.
  const id = await suivi.addWeight('71,8')

  // Then the Poids card shows 71,8 kg for today…
  await expect(suivi.selectedLabel).toHaveText('Aujourd’hui')
  await expect(suivi.selectedWeight).toHaveText('71,8 kg')
  await expect(suivi.todayColumn).toHaveAccessibleName(/^Aujourd’hui : 71,8 kg, tendance \d+,\d kg$/)
  await expect(suivi.row(id)).toContainText('71,8 kg')

  // …and the API holds it as typed.
  const response = await api.get(`/api/body-measurements/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(response.status(), `GET /api/body-measurements/${id} answered ${response.status()}`).toBe(200)
  const stored = (await response.json()) as MeasurementRow
  expect(stored).toMatchObject({ weightKg: 71.8, source: 'manual', excluded: false })
})

/**
 * Santé › Suivi, the cards Pas and Énergie — MAG-746.
 *
 * Given un lot `POST /api/health/sync` avec un jour de 8 000 pas et 400 kcal actives,
 * When j'ouvre Santé › Suivi,
 * Then la carte Pas affiche 8 000 et la carte Énergie le détail base · pas · séances.
 */
test('Santé › Suivi shows the synced steps and the energy detail — MAG-746', async ({ page, api }) => {
  // The day the API reads is the Paris one.
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Paris', year: 'numeric', month: '2-digit', day: '2-digit' }).format(e2eNow())
  const synced = await api.post('/api/health/sync', { data: { days: [{ date: today, steps: 8000, activeKcal: 400 }] } })
  expect(synced.status(), `POST /api/health/sync answered ${synced.status()}`).toBe(200)

  const suivi = new HealthSuiviPage(page)
  await suivi.goto('/')
  await suivi.expectLoaded()
  await suivi.navigateTo('Suivi', 'Santé')

  await expect(suivi.stepsCard.getByTestId('steps-value')).toHaveText(/^8\s000$/)
  await expect(suivi.energyCard.getByTestId('energy-detail')).toHaveText(/^base .* · pas .* · séances .*$/)
})

/**
 * Santé › Suivi s'ouvre sur le bilan du jour — MAG-748.
 *
 * Given le seed, When j'ouvre Santé › Suivi, Then le bandeau « Bilan du jour » est à côté du titre,
 * And un clic dessus ouvre Santé › Nutrition.
 */
test('Santé › Suivi opens on today’s balance, which opens Santé › Nutrition — MAG-748', async ({ page }) => {
  const suivi = new HealthSuiviPage(page)
  await suivi.open()

  await expect(suivi.balanceBanner).toBeVisible()
  await suivi.balanceBanner.click()

  await expect(page).toHaveURL(/#\/health\/nutrition$/)
  await expect(new HealthNutritionPage(page).balanceCard).toBeVisible()
})
