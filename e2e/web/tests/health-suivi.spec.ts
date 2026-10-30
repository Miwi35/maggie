import { test, expect } from '../fixtures/index.js'
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
