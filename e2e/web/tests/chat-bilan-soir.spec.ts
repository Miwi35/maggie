import { test, expect, parisDay, e2eNow } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream, toolResults } from '../helpers/agui.js'
import { HealthJournalPage } from '../pages/HealthJournalPage.js'
import { BilanActivityPage } from '../pages/BilanActivityPage.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * « On fait le point » opens the evening review — MAG-749 (Bilan du soir, part 1, extends MAG-99).
 *
 * Given the fake model and the scenario `98-bilan-soir-on-demand.yaml`, When I write « on fait le point »,
 * Then Maggie loads the skill, reads the status, writes the step and the day, and opens the Journée step
 * — and `activity-status/bilan` reads back `journee`, through the real MCP and the real API.
 */

test.use({ journeyAccount: 'empty' })

test('« on fait le point » opens the review at the Journée step and writes the step', async ({ page, api, session }) => {
  await new HomePage(page).open()

  // Nothing in progress before.
  expect((await api.get('/api/activity-status/bilan')).status()).toBe(404)

  // The shipped skill is there before Maggie loads it: the e2e reseed must not empty the skills index.
  const skill = await api.get('/agent/skills/bilan-soir')
  expect(skill.status(), await skill.text()).toBe(200)

  // When I ask to take stock of the day.
  const body = await page.evaluate(
    async ({ token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ message: 'On fait le point', idempotency_key: `e2e-bilan-${Math.random().toString(36).slice(2)}` }),
      })
      return response.text()
    },
    { token: session.token },
  )
  const events = parseAgUiStream(body)

  // Then the review ran through its tools, every one successfully.
  const called = calledTools(events)
  expect(called).toContain('get_activity_status')
  expect(called).toContain('save_bilan_step')
  expect(called).toContain('get_tasks')
  expect(toolResults(events).every((result) => result.status === 'success')).toBe(true)
  expect(assistantText(events)).toContain('la journée')

  // And the status of the bilan is at the Journée step.
  const status = await api.get('/api/activity-status/bilan')
  expect(status.status(), await status.text()).toBe(200)
  const stored = (await status.json()) as { activity: string; origin: { kind: string }; state: { step: string } }
  expect(stored.activity).toBe('bilan')
  expect(stored.origin.kind).toBe('day')
  expect(stored.state.step).toBe('journee')
})

/**
 * The Consignes step — MAG-753 (Bilan du soir, part 5, extends MAG-99).
 *
 * Given the fake model and the scenario `98-bilan-soir-consignes.yaml`, When I dictate three consignes,
 * Then they are classed — a dated reminder, a task due tomorrow, a rule learned — each with the
 * `bilan-soir` provenance, and `activity-status/bilan` keeps the three kinds for the next digest.
 */
test('consignes dictated at the Consignes step are classed and kept for the digest', async ({ page, api, session }) => {
  await new HomePage(page).open()

  const body = await page.evaluate(
    async ({ token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          message: "Rappelle-moi d'appeler le garage à 9 h, ajoute acheter du pain, et le mardi je pars plus tôt",
          idempotency_key: `e2e-consignes-${Math.random().toString(36).slice(2)}`,
        }),
      })
      return response.text()
    },
    { token: session.token },
  )
  const events = parseAgUiStream(body)

  const called = calledTools(events)
  expect(called.filter((name) => name === 'add_bilan_consigne')).toHaveLength(3)
  expect(toolResults(events).every((result) => result.status === 'success')).toBe(true)

  const status = await api.get('/api/activity-status/bilan')
  expect(status.status(), await status.text()).toBe(200)
  const stored = (await status.json()) as { state: { step: string; instructions: { kind: string }[] } }
  expect(stored.state.step).toBe('consignes')
  expect(stored.state.instructions.map((instruction) => instruction.kind)).toEqual(['reminder', 'task', 'instruction'])
})

/**
 * The Santé step — MAG-754 (Bilan du soir, part 6, extends MAG-99).
 *
 * Given the fake model and the scenario `98-bilan-soir-sante.yaml`, When I answer moral 7 and fatigue 4,
 * Then today's entry of the health journal carries them, written by Maggie, the bilan's answers keep them
 * for « Reprendre », and Santé › Journal shows « avec Maggie ».
 */
test('mood and fatigue given at the Santé step are written in the health journal by Maggie', async ({ page, api, session }) => {
  const today = parisDay()
  await new HomePage(page).open()

  const body = await page.evaluate(
    async ({ token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          message: 'Mon moral à 7 et ma fatigue à 4',
          idempotency_key: `e2e-sante-${Math.random().toString(36).slice(2)}`,
        }),
      })
      return response.text()
    },
    { token: session.token },
  )
  const events = parseAgUiStream(body)

  expect(calledTools(events)).toContain('update_health_day')
  expect(toolResults(events).every((result) => result.status === 'success')).toBe(true)

  const day = await api.get(`/api/health/days/${today}`, { headers: { Accept: 'application/json' } })
  expect(day.status(), await day.text()).toBe(200)
  expect(await day.json()).toMatchObject({ mood: 7, fatigue: 4, notedBy: 'maggie' })

  const status = await api.get('/api/activity-status/bilan')
  expect(status.status(), await status.text()).toBe(200)
  const stored = (await status.json()) as { state: { step: string; answers: { mood: number; fatigue: number } } }
  expect(stored.state.step).toBe('sante')
  expect(stored.state.answers).toEqual({ mood: 7, fatigue: 4 })

  const journal = new HealthJournalPage(page)
  await journal.open()
  await expect(journal.score('Moral', 7)).toHaveAttribute('aria-checked', 'true')
  await expect(journal.score('Fatigue', 4)).toHaveAttribute('aria-checked', 'true')
  await expect(journal.entry.getByTestId('journal-noted-by')).toContainText('avec Maggie')
})

/**
 * The review as a full-screen activity — MAG-961 (Bilan du soir, Admin 1, extends MAG-99).
 *
 * Given a review at the Journée step, When I open `/activites/bilan`, go to Santé and quit,
 * Then the activity resumes where it was left and the status is kept until « Bonne nuit ».
 */
test('the evening review runs full screen and resumes where it was left', async ({ page, api }) => {
  const today = parisDay()
  const tomorrow = parisDay(new Date(e2eNow().getTime() + 86_400_000))
  const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  const json = { Accept: 'application/json' }

  // Given a debit today, two tasks and a bilan Maggie prepared (no step) with the first, the second in reserve
  const account = await api.post('/api/accounts', { headers: jsonLd, data: { name: 'Courant MAG-962', type: 'checking', currency: 'EUR', balanceCents: 0 } })
  expect(account.status(), await account.text()).toBe(201)
  const accountId = ((await account.json()) as { id: string }).id
  const debit = await api.post('/api/transactions', {
    headers: jsonLd,
    data: { account: `/api/accounts/${accountId}`, label: 'BOULANGERIE MAG962', amountCents: -4280, currency: 'EUR', bookedAt: today, status: 'spent' },
  })
  expect(debit.status(), await debit.text()).toBe(201)
  const chosen = ((await (await api.post('/api/tasks', { headers: jsonLd, data: { title: 'Trier les papiers 962' } })).json()) as { id: string }).id
  const reserve = ((await (await api.post('/api/tasks', { headers: jsonLd, data: { title: 'Ranger les photos 962' } })).json()) as { id: string }).id
  const put = await api.put('/api/activity-status/bilan', {
    data: {
      origin: { kind: 'day', id: today },
      state: { tomorrowTasks: [{ taskId: chosen, title: 'Trier les papiers 962' }], tomorrowSpares: [{ taskId: reserve, title: 'Ranger les photos 962' }] },
    },
  })
  expect(put.status(), await put.text()).toBeLessThan(300)

  const bilan = new BilanActivityPage(page)
  await bilan.open()
  await expect(bilan.heading).toHaveText('Votre journée')
  await expect(bilan.counter).toHaveText('Étape 1 sur 5')

  await bilan.step('Finance').click()
  await expect(bilan.heading).toHaveText('Vos comptes')
  await expect(bilan.dayTotal).toContainText('42,80')
  await expect(bilan.rhythm('Semaine')).toBeVisible()
  await expect(bilan.rhythm('Mois')).toBeVisible()

  await bilan.step('Santé').click()
  await expect(bilan.heading).toHaveText('Votre santé')
  await expect
    .poll(async () => ((await (await api.get('/api/activity-status/bilan')).json()) as { state: { step: string } }).state.step, { intervals: [1000], timeout: 15_000 })
    .toBe('sante')

  await bilan.quit()
  await bilan.confirmQuit()
  expect((await api.get('/api/activity-status/bilan')).status()).toBe(200)

  await bilan.open()
  await expect(bilan.heading).toHaveText('Votre santé')

  // The prepared lists survived the opening: swap the chosen task for the reserve one, then Bonne nuit plans it
  await bilan.step('Demain').click()
  await expect(bilan.plannedTask('Trier les papiers 962')).toBeVisible()
  await bilan.swap('Trier les papiers 962')
  await expect(bilan.plannedTask('Ranger les photos 962')).toBeVisible()
  await expect(bilan.plannedTask('Trier les papiers 962')).toBeHidden()

  await bilan.next()
  await expect(bilan.root).toBeHidden()
  await expect.poll(async () => (await api.get('/api/activity-status/bilan')).status(), { intervals: [1000], timeout: 15_000 }).toBe(404)
  const scheduled = async (id: string) => ((await (await api.get(`/api/tasks/${id}`, { headers: json })).json()) as { scheduledDate?: string | null; dueDate?: string | null })
  await expect.poll(async () => (await scheduled(reserve)).scheduledDate?.slice(0, 10), { intervals: [1000], timeout: 15_000 }).toBe(tomorrow)
  expect((await scheduled(reserve)).dueDate ?? null).toBeNull()
  expect((await scheduled(chosen)).scheduledDate ?? null).toBeNull()
})

/**
 * The Demain step takes up what Maggie chose at the creation of the bilan — MAG-958 (extends MAG-99).
 *
 * Given « Trier les papiers », a task without a date, and the bilan prepared with it (no step), When I write
 * « C'est tout pour les consignes », Then Maggie reads the bilan, proposes the task and writes nothing; When I
 * answer « Oui, je garde », Then the task is scheduled for tomorrow and its due date stays empty.
 */
test('the Demain step proposes the prepared task and schedules it for tomorrow on a yes — MAG-958', async ({ page, api, session }) => {
  const today = parisDay()
  const tomorrow = parisDay(new Date(e2eNow().getTime() + 86_400_000))
  const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
  await new HomePage(page).open()

  const created = await api.post('/api/tasks', { headers: jsonLd, data: { title: 'Trier les papiers' } })
  expect(created.status(), await created.text()).toBe(201)
  const taskId = ((await created.json()) as { id: string }).id

  const prepared = await api.put('/api/activity-status/bilan', {
    data: {
      origin: { kind: 'day', id: today },
      state: { answers: {}, instructions: [], tomorrowTasks: [{ taskId, title: 'Trier les papiers' }] },
    },
  })
  expect(prepared.status(), await prepared.text()).toBeLessThan(300)

  const say = (message: string) =>
    page.evaluate(
      async ({ token, message }) => {
        const response = await fetch('/agent/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({ message, idempotency_key: `e2e-demain-${Math.random().toString(36).slice(2)}` }),
        })
        return response.text()
      },
      { token: session.token, message },
    )

  const proposal = parseAgUiStream(await say("C'est tout pour les consignes"))
  expect(calledTools(proposal)).toContain('get_activity_status')
  expect(calledTools(proposal)).not.toContain('update_task')
  expect(assistantText(proposal)).toContain('papiers')

  await expect
    .poll(async () => ((await (await api.get('/api/activity-status/bilan')).json()) as { state: { step?: string } }).state.step, {
      intervals: [1_000],
      timeout: 15_000,
    })
    .toBe('demain')
  const status = (await (await api.get('/api/activity-status/bilan')).json()) as { state: { tomorrowTasks: { taskId: string }[] } }
  expect(status.state.tomorrowTasks.map((task) => task.taskId)).toEqual([taskId])
  const untouched = (await (await api.get(`/api/tasks/${taskId}`, { headers: { Accept: 'application/json' } })).json()) as { scheduledDate?: string | null }
  expect(untouched.scheduledDate ?? null).toBeNull()

  const kept = parseAgUiStream(await say('Oui, je garde'))
  expect(calledTools(kept)).toEqual(['update_task'])

  await expect
    .poll(async () => ((await (await api.get(`/api/tasks/${taskId}`, { headers: { Accept: 'application/json' } })).json()) as { scheduledDate?: string | null }).scheduledDate?.slice(0, 10), {
      intervals: [1_000],
      timeout: 15_000,
    })
    .toBe(tomorrow)
  const task = (await (await api.get(`/api/tasks/${taskId}`, { headers: { Accept: 'application/json' } })).json()) as { dueDate?: string | null }
  expect(task.dueDate ?? null).toBeNull()
})
