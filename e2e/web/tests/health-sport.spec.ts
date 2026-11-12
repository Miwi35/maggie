import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { HealthSportPage } from '../pages/HealthSportPage.js'
import { HomePage } from '../pages/HomePage.js'
import { openSubscribed, publishOnHub } from '../helpers/mercure.js'

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
 *
 * MAG-772 extends it: Given une séance prévue hier à 18:00 et non faite, et la proposition de
 * rattrapage de Maggie (seed `71-workout-makeup.yaml`), When Maggie interrompt, Then trois
 * choix empilés « 30 min, plus doux », « 20 min, même intensité », « Demain » puis « Plus tard » ;
 * When je clique « Demain », Then l'interruption se ferme et Santé › Sport montre une séance
 * prévue demain.
 *
 * MAG-778 extends it: Given le faux modèle et le scénario « Prépare ma semaine de sport », When je l'écris
 * dans le chat, Then Maggie répond avec les séances écrites, et Santé › Sport montre trois séances « Prévue »
 * la semaine suivante, dans l'agenda « Santé ».
 */

interface WorkoutRow {
  id: string
  startedAt: string
  event?: string | null
  type: string
  durationMinutes: number
  distanceMeters: number | null
  source: string
  title?: string
  variant?: string | null
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

test.describe('Maggie proposes to make up the missed session — MAG-772', () => {
  // Its own seeded account: answering settles the proposal and plans a session.
  test.describe.configure({ mode: 'serial', retries: 0 })

  test('the interruption stacks three choices, « Demain » plans the session tomorrow and closes it', async ({ makeupUser }) => {
    const { page, api, session } = makeupUser
    const dashboard = new HomePage(page)
    await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
    await new ChatPanel(page).ensureClosed()

    // Given the notification Maggie sent, delivered on the hub.
    const [stored] = (await getCollection<{ id: string; type: string; [key: string]: unknown }>(api, '/api/notifications?itemsPerPage=100')).filter(
      (n) => n.type === 'workout_makeup',
    )
    expect(stored, 'the seeded make-up notification').toBeDefined()
    await publishOnHub(page, `/users/${session.user.id}/api/notifications/${stored.id}`, { '@id': `/api/notifications/${stored.id}`, ...stored })

    // Then the three choices, worded with the minutes, then « Plus tard ».
    const interruption = page.getByRole('alertdialog')
    await expect(interruption).toBeVisible()
    await expect(interruption.getByRole('button', { name: '30 min, plus doux' })).toBeVisible()
    await expect(interruption.getByRole('button', { name: '20 min, même intensité' })).toBeVisible()
    await expect(interruption.getByRole('button', { name: 'Demain' })).toBeVisible()
    await expect(interruption.getByRole('button', { name: 'Plus tard' })).toBeVisible()

    // When I choose « Demain », Then it closes and the API planned a session tomorrow.
    await interruption.getByRole('button', { name: 'Demain' }).click()
    await expect(interruption).toBeHidden()

    // Tomorrow is the day after the miss (seeded yesterday): the next 18:00 still ahead.
    const now = e2eNow().getTime()
    await expect
      .poll(async () => {
        const rows = await getCollection<WorkoutRow>(api, '/api/workouts?itemsPerPage=100')

        return rows.filter((w) => w.source === 'planned' && new Date(w.startedAt).getTime() > now).length
      })
      .toBeGreaterThan(0)

    // …and Santé › Sport lists it.
    const sport = new HealthSportPage(page)
    await sport.open()
    await expect(page.getByText('Prévue').first()).toBeVisible()
  })
})

test.describe('The coach prepares the week — MAG-778', () => {
  test('« Prépare ma semaine de sport » writes three planned sessions next week, calibration first', async ({ page, api }) => {
    test.setTimeout(120_000)
    const ld = { Accept: 'application/ld+json' }

    // Given Santé › Sport open…
    const sport = new HealthSportPage(page)
    await sport.open()

    // When I ask Maggie to prepare my sports week…
    const events = await new ChatPanel(page).send('Prépare ma semaine de sport')

    // Then she handed it to the coach and wrote the three sessions herself…
    const tools = calledTools(events)
    expect(tools, 'the coach was never consulted — did the scenario stop matching?').toContain('delegate')
    expect(tools.filter((name) => name === 'manage_workouts')).toHaveLength(3)
    expect(toolResults(events).filter((r) => r.toolName === 'manage_workouts').map((r) => r.status)).toEqual(['success', 'success', 'success'])

    // …the API holds them as planned strength sessions with their exercises, the first one a calibration…
    let planned: WorkoutRow[] = []
    await expect
      .poll(
        async () => {
          planned = (await getCollection<WorkoutRow>(api, '/api/workouts')).filter((row) => row.source === 'planned')
          return planned.length
        },
        { message: 'the three planned sessions were not stored', timeout: 30_000 },
      )
      .toBe(3)
    planned.sort((a, b) => a.startedAt.localeCompare(b.startedAt))
    expect(planned.map((row) => row.variant)).toEqual(['calibration', 'normal', 'normal'])
    expect(planned.every((row) => row.type === 'strength' && row.event)).toBe(true)

    // …in the « Santé » agenda…
    const event = await api.get(String(planned[0].event), { headers: ld })
    const agendaIri = ((await event.json()) as { agenda: string }).agenda
    const agenda = await api.get(agendaIri, { headers: ld })
    expect(((await agenda.json()) as { name: string }).name).toBe('Santé')

    // …and Santé › Sport lists the three as « Prévue ».
    await sport.open()
    for (const row of planned) {
      const id = row.id.split('/').pop() ?? row.id
      await expect(sport.row(id)).toContainText('Prévue')
    }
  })
})
