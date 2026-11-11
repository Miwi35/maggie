import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import { HealthSportPage } from '../pages/HealthSportPage.js'

/**
 * Santé › Sport, the first journey of the new Santé module — MAG-705.
 *
 * Given le seed, When j'ouvre Santé › Sport et j'ajoute « Vélo, 1 h 05, 22 km »,
 * Then la ligne apparaît dans le tableau et la carte de la semaine augmente de 65 min.
 *
 * Nouveau parcours Santé: no earlier journey to extend.
 *
 * MAG-775 extends it: Given la séance prévue « Séance A » et ses 6 exercices (seed
 * `70-health.yaml`), When j'ouvre sa fiche, Then le bloc « Exercices » montre
 * « Squat gobelet au sac » et « 3 × 10 · 10 kg ».
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

test.describe('The sheet of a detailed session — MAG-775', () => {
  // The seed's data, sliced to this journey's account: « Séance A » is its own.
  test.use({ journeyAccount: 'standard' })

  test('Santé › Sport shows the exercises of « Séance A » on its sheet', async ({ page, journeyUser }) => {
    const id = journeyUser.ref('e2e_workout_session_a')

    const sport = new HealthSportPage(page)
    await sport.open()

    // When I open the sheet of « Séance A »…
    await sport.select(id)

    // Then the « Exercices » block lists its six exercises, the squat with its load.
    const exercises = sport.exercises(id)
    await expect(exercises).toBeVisible()
    await expect(exercises.getByTestId('workout-exercise')).toHaveCount(6)
    const squat = exercises.getByTestId('workout-exercise').filter({ hasText: 'Squat gobelet au sac' })
    await expect(squat).toContainText('3 × 10 · 10 kg')
    await expect(exercises).toContainText('Tractions négatives')
  })
})
