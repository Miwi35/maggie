import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { CalendarPage } from '../pages/CalendarPage.js'
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
 *
 * MAG-777 extends it: Given une séance prévue aujourd'hui à 18:30, When j'ouvre l'Agenda, Then
 * « Séance A » est à 18:30 dans l'agenda « Santé » ; When je la déplace à 19:00, Then Santé › Sport
 * la montre à 19:00.
 */

interface WorkoutRow {
  id: string
  startedAt: string
  event?: string | null
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

test.describe('A planned session in the « Santé » agenda — MAG-777', () => {
  // An empty account, made fresh for the test: today's 18:30 is this journey's alone, and
  // no seeded strength session sits within the 24 h the week's guard rails ask (MAG-776).
  test.use({ journeyAccount: 'empty' })

  test('« Séance A » is at 18:30 in the Santé agenda, and moved to 19:00 there it starts at 19:00 in Santé › Sport', async ({ page, api }) => {
    const day = parisDay(e2eNow())
    const ld = { Accept: 'application/ld+json' }

    // The empty account owns no agenda of its own, and module agendas stay out of the
    // sidebar: a « Perso » gives the Agenda page the line it waits for.
    const perso = await api.post('/api/agendas', {
      headers: { ...ld, 'Content-Type': 'application/ld+json' },
      data: { name: 'Perso', color: '#4285f4', timeZone: 'Europe/Paris' },
    })
    expect(perso.status(), `POST /api/agendas answered ${perso.status()}`).toBe(201)

    // Given a session planned today at 18:30 (written over HTTP: the seed's are history, filed nowhere).
    const created = await api.post('/api/workouts', {
      headers: { ...ld, 'Content-Type': 'application/ld+json' },
      data: { type: 'strength', title: 'Séance A', startedAt: parisTime(day, '18:30:00'), durationMinutes: 30, source: 'planned' },
    })
    expect(created.status(), `POST /api/workouts answered ${created.status()}`).toBe(201)
    const workout = (await created.json()) as WorkoutRow
    const id = workout.id.split('/').pop() ?? workout.id
    expect(workout.event, 'the session has its event in the agenda').toBeTruthy()
    const eventIri = String(workout.event)

    // The event is served from the search index: wait for it there.
    let event: { activity?: string; agenda?: string } = {}
    await expect
      .poll(async () => {
        const response = await api.get(eventIri, { headers: ld })
        if (!response.ok()) return response.status()
        event = (await response.json()) as typeof event

        return 200
      })
      .toBe(200)
    expect(event.activity).toBe('sport')
    const agenda = await api.get(String(event.agenda), { headers: ld })
    expect(((await agenda.json()) as { name: string }).name).toBe('Santé')

    // When I open the Agenda, Then « Séance A » is at 18:30 in « Santé »…
    const calendar = new CalendarPage(page)
    await calendar.openEvent(eventIri, 'Séance A')
    await expect(calendar.popover).toContainText('18:30')
    await expect(calendar.popover).toContainText('Santé')

    // …When I move it to 19:00…
    await calendar.editFromPopover()
    await calendar.submitEventEdit({ start: `${day}T19:00`, end: `${day}T19:30` })

    // …Then the session starts at 19:00, as Santé › Sport reads it.
    await expect
      .poll(async () => {
        const response = await api.get(`/api/workouts/${id}`, { headers: ld })
        const stored = (await response.json()) as WorkoutRow

        return new Date(stored.startedAt).getTime()
      })
      .toBe(new Date(parisTime(day, '19:00:00')).getTime())

    const sport = new HealthSportPage(page)
    await sport.open()
    await expect(sport.row(id)).toContainText('Séance A')
  })
})
