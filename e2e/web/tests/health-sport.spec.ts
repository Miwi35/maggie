import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { HealthSportPage } from '../pages/HealthSportPage.js'

/**
 * Santé › Sport, the first journey of the new Santé module — MAG-705.
 *
 * Given le seed, When j'ouvre Santé › Sport et j'ajoute « Vélo, 1 h 05, 22 km »,
 * Then la ligne apparaît dans le tableau et la carte de la semaine augmente de 65 min.
 *
 * Nouveau parcours Santé: no earlier journey to extend.
 */

interface WorkoutRow {
  id: string
  type: string
  durationMinutes: number
  distanceMeters: number | null
  source: string
}

// The account of this journey alone: the week it reads is the one it wrote.
test.use({ journeyAccount: 'empty' })

test('Santé › Sport lists an added bike ride and the week grows by its 65 minutes — MAG-705', async ({ page, api }) => {
  const { retry } = test.info()
  const title = `Vélo ${retry}`

  const sport = new HealthSportPage(page)
  await sport.goto('/')
  await sport.expectLoaded()

  // When I open Santé › Sport…
  await sport.navigateTo('Sport', 'Santé')
  await expect(sport.addButton).toBeVisible()
  await expect(sport.currentWeek).toBeVisible()
  const before = await sport.currentWeekMinutes()

  // …and add « Vélo, 1 h 05, 22 km » today.
  const id = await sport.add({
    type: 'Vélo',
    title,
    startedAt: `${parisDay(e2eNow())}T00:30`,
    minutes: '65',
    km: '22',
  })

  // Then the row is in the table…
  await expect(sport.row(id)).toBeVisible()
  await expect(sport.row(id)).toContainText(title)
  await expect(sport.row(id)).toContainText('1 h 05')
  await expect(sport.row(id)).toContainText('22,0 km')

  // …the API holds it as typed…
  const response = await api.get(`/api/workouts/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(response.status(), `GET /api/workouts/${id} answered ${response.status()}`).toBe(200)
  const stored = (await response.json()) as WorkoutRow
  expect(stored).toMatchObject({ type: 'bike', durationMinutes: 65, distanceMeters: 22000, source: 'manual' })

  // …and the card of the week grew by its 65 minutes.
  await expect.poll(() => sport.currentWeekMinutes()).toBe(before + 65)
})
