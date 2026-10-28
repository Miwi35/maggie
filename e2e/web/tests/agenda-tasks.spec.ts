import { test, expect, e2eNow, parisDay, parisTime, seedDate, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * Tasks, from the agenda (MAG-100).
 *
 * The regression this file is built around is `33f0506`: the `ExistsFilter` on
 * `Task` only declared `doneDate`, so `exists[dueDate]=false` was accepted and
 * silently ignored, and the dashboard's "undated tasks" query came back with every
 * undone task — dated ones leaking into "Aujourd'hui". A parameter the API does not
 * declare is dropped without a word, which is why the first test asks the API
 * directly rather than counting lines on a screen that was patched to filter again
 * on its own side.
 *
 * `b576cc6` and `751124a` renamed `name` → `title` and `doneDate` → `completedAt`
 * and had to be carried to the mobile app by hand; `api/contract/` holds that side
 * now (MAG-104), so nothing here repeats it.
 */

interface StoredTask {
  id?: string
  title?: string
  dueDate?: string | null
  completedAt?: string | null
}

/** A task as the day sends it (MAG-497). */
interface DayTask {
  id: string
  title: string
  completedAt: string | null
  postponedSince: string | null
  urgency: string | null
}

interface DayPlan {
  confirmedAt: string | null
  slots: { position: number; task: DayTask }[]
  now: { position: number; task: DayTask } | null
  reserve: DayTask[]
  done: number
  total: number
}

const DAY = seedDate()

async function tasks(api: APIRequestContext, query = ''): Promise<StoredTask[]> {
  return getCollection<StoredTask>(api, `/api/tasks?itemsPerPage=100${query}`)
}

/** `day` (YYYY-MM-DD) moved by `offset` days, by calendar arithmetic. */
function daysFrom(day: string, offset: number): string {
  const [year, month, date] = day.split('-').map(Number)

  return new Date(Date.UTC(year, month - 1, date + offset)).toISOString().slice(0, 10)
}

/** A title the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} (essai ${retry})`
}

/**
 * The undated filter answers the question it was asked — `33f0506`.
 *
 * Both directions, because one alone proves nothing: a filter that is ignored
 * returns the undated task too, and a filter that matched nothing would return an
 * empty list and look just as correct.
 */
test('exists[dueDate]=false returns the undated task and nothing dated', async ({ api }) => {
  const undated = await tasks(api, '&exists%5BdueDate%5D=false')
  const titles = undated.map((task) => task.title)

  expect(titles).toContain('Trier les photos de vacances')
  expect(titles).not.toContain('Appeler le garage')
  expect(
    undated.filter((task) => task.dueDate),
    'a task with a due date came back from the undated filter',
  ).toEqual([])
})

test('a task created from the agenda lands in the database and on the grid', async ({ page, api }) => {
  const title = perAttempt('Déposer le colis')
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createTask({ title, dueDate: DAY, criticality: 'Haute' })

  const stored = await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
    what: `The task "${title}"`,
  })
  expect(stored.completedAt ?? null).toBeNull()

  // Tasks are drawn as all-day chips. The day view, where the all-day row holds
  // only what is due that day — month view folds a busy day behind "+N autres".
  await calendar.chooseView('Jour')
  await expect(calendar.chip(title)).toHaveCount(1)
})

/**
 * A task due and not done stays in « Aujourd'hui », marked late — MAG-405.
 *
 * The seed has one task due today and one due two days ago. The lateness comes
 * from the API (`overdueDays`), counted in the user's timezone: the page only draws it.
 *
 * On the « Tâches » page the day holds five tasks the owner chose (MAG-497): the
 * late one waits in the reserve, listed under « En retard » (MAG-644) with « reportée depuis … »
 * since its due day, never a word of reproach.
 */
test('a late task is put off, not reproached: the API counts its lateness and its row says « reportée depuis »', async ({ page, api }) => {
  const title = 'Renvoyer le formulaire de la mutuelle'
  const taskId = seedId('e2e_task_late')

  const item = await api.get(`/api/tasks/${taskId}`, { headers: { Accept: 'application/ld+json' } })
  expect(item.ok(), `GET /api/tasks/${taskId} answered ${item.status()}`).toBe(true)
  const stored = (await item.json()) as { overdueDays?: number | null; postponedSince?: string | null }
  expect(stored.overdueDays, 'the item should carry its lateness').toBe(2)
  expect(stored.postponedSince, 'put off since its due day').toBe(seedDate(-2))

  // The « Tâches » page: in the day's list, put off rather than late.
  const board = new TasksPage(page)
  await board.open()
  await expect(board.slot(title)).toHaveCount(1)
  await expect(board.slot(title)).toContainText(/reportée depuis \S+/)
  await expect(board.slot(title)).not.toContainText(/retard/i)
})

/**
 * « Aujourd'hui » goes by the order score, each task with its urgency — MAG-406.
 *
 * The seed's three witnesses, with the default weights: « Payer la cantine »
 * (high criticality, medium priority, due today) 78, « Rendre le livre » (low,
 * low, a day late) 67, « Arroser » (low, low, due today) 55 — urgencies haute,
 * haute, normale. The API computes both; the list of the « Tâches » page keeps the
 * reserve's order (MAG-497) — « Rendre le livre », a day late, sits under « En retard » and reads
 * « reportée depuis hier ». Other journeys add tasks to the day, so the three are compared with
 * each other, not with the row numbers.
 */
test('« Aujourd\'hui » goes by score, each task with its urgency in words', async ({ page, api }) => {
  const witnesses = ['Payer la cantine', 'Rendre le livre', 'Arroser']

  const response = await api.get('/api/tasks/view/today', { headers: { Accept: 'application/ld+json' } })
  expect(response.ok(), `GET /api/tasks/view/today answered ${response.status()}`).toBe(true)
  const view = ((await response.json()) as { member: { title: string; orderScore: number; urgency: string }[] }).member
  const scored = witnesses.map((title) => view.find((task) => task.title === title))
  expect(scored.map((task) => task?.orderScore)).toEqual([78, 67, 55])
  expect(scored.map((task) => task?.urgency)).toEqual(['high', 'high', 'normal'])

  // The reserve of the day, by the same score.
  const day = await dayPlan(api)
  const reserved = witnesses.map((title) => day.reserve.findIndex((task) => task.title === title))
  expect(reserved, 'the three wait in the reserve, by score').toEqual([...reserved].sort((a, b) => a - b))
  expect(Math.min(...reserved), 'a witness is missing from the reserve').toBeGreaterThanOrEqual(0)

  const board = new TasksPage(page)
  await board.open()
  const drawn = await board.dayRows.allTextContents()
  const order = witnesses.map((title) => drawn.findIndex((row) => row.includes(title)))
  expect(Math.min(...order), `a witness is missing from the list: ${drawn.join(' | ')}`).toBeGreaterThanOrEqual(0)
  // The two due today keep their score's order; the late one is listed apart, first.
  expect(order[0], `the order of the list: ${drawn.join(' | ')}`).toBeLessThan(order[2])
  expect(order[1]).toBeLessThan(order[0])
  await expect(board.slot('Rendre le livre')).toContainText('reportée depuis hier')
})

/**
 * The « Tâches » page sorts every task into one of four views — MAG-408 — each a route and a menu entry (MAG-644).
 *
 * Five tasks of this attempt: a late one, a late critical one, one due in five
 * days, one without a date and a done one. The API sorts each into its view; on
 * the page « Aujourd'hui » is the day the owner chose (MAG-497), so the late ones
 * wait in its list under « En retard » — the ordinary one « reportée depuis … », the critical one
 * with its own mark — and the three others land in their own view. Ticking the
 * « À venir » one moves it to « Faites ». The seed and the other journeys add
 * tasks of their own, so the menu's numbers are checked to be there and non-zero, and
 * to follow the view's own total, not to equal one.
 */
test('« Tâches » puts a late, an upcoming, an undated and a done task in their own views, and ticking moves it to « Faites »', async ({ page, api }) => {
  const late = perAttempt('Relancer le notaire')
  const critical = perAttempt('Payer la taxe foncière')
  const upcoming = perAttempt('Réserver le train')
  const someday = perAttempt('Apprendre le piano')
  const finished = perAttempt('Envoyer les voeux')

  await createTask(api, { title: late, dueDate: seedDate(-3) })
  await createTask(api, { title: critical, dueDate: seedDate(-3), criticality: 'critical' })
  await createTask(api, { title: upcoming, dueDate: seedDate(5) })
  await createTask(api, { title: someday })
  const doneId = await createTask(api, { title: finished })
  await completeTask(api, doneId)

  // Each in the view the API sorts it into, before the page is asked to show it.
  for (const [view, title] of [
    ['today', late],
    ['today', critical],
    ['upcoming', upcoming],
    ['someday', someday],
    ['done', finished],
  ] as const) {
    await expect
      .poll(async () => (await viewTitles(api, view)).includes(title), {
        timeout: 30_000,
        message: `"${title}" should be in the ${view} view`,
      })
      .toBe(true)
  }

  const board = new TasksPage(page)
  await board.open()

  // Given a late task: in « Aujourd'hui », put off, never a word of reproach.
  await expect(board.slot(late)).toHaveCount(1)
  await expect(board.slot(late)).toContainText(/reportée depuis \S+/)
  await expect(board.slot(late)).not.toContainText(/retard/i)
  await expect(board.content.getByRole('list', { name: 'En retard' })).toContainText(late)
  // A critical one keeps its word and its icon instead.
  const criticalBadge = board.slot(critical).getByTestId('urgency-badge')
  await expect(criticalBadge).toHaveText('critique')
  await expect(criticalBadge.locator('svg')).toHaveCount(1)
  await expect(board.slot(critical)).not.toContainText('reportée')
  // Done, it is nowhere in the day.
  await expect(board.slot(finished)).toHaveCount(0)

  await board.select('À venir')
  await expect(board.row(upcoming)).toHaveCount(1)
  await expect(board.row(late)).toHaveCount(0)

  await board.select('Un jour')
  await expect(board.row(someday)).toHaveCount(1)
  await expect(board.row(upcoming)).toHaveCount(0)

  await board.select('Faites')
  await expect(board.row(finished)).toHaveCount(1)
  await expect(board.row(someday)).toHaveCount(0)

  // « Aujourd'hui » counts what is left of the day, which may be nothing.
  expect(await board.count("Aujourd'hui"), "the « Aujourd'hui » number").toBeGreaterThanOrEqual(0)
  for (const view of ['À venir', 'Un jour', 'Faites'] as const) {
    expect(await board.count(view), `the « ${view} » number`).toBeGreaterThanOrEqual(1)
  }

  // When I tick the « À venir » task, it leaves its view and shows in « Faites ».
  await board.select('À venir')
  const before = await board.count('Faites')
  await board.checkbox(upcoming).click()

  await expect(board.row(upcoming)).toHaveCount(0)
  await expect
    .poll(() => board.count('Faites'), { message: 'the « Faites » number should follow the tick' })
    .toBeGreaterThan(before)
  await board.select('Faites')
  await expect(board.row(upcoming)).toHaveCount(1)
  await expect(board.checkbox(upcoming)).toBeChecked()

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.title === upcoming)?.completedAt != null, {
      timeout: 30_000,
      message: 'the ticked task should be stored as completed',
    })
    .toBe(true)
})

/**
 * « Tâches » as the mockup draws it — MAG-644, extends MAG-100.
 *
 * The views are sub-entries of the menu with their numbers and a route each; the page is titled with
 * the day, lists its tasks in the full width with an Urgence and an Échéance column, opens a task in a
 * panel beside the list rather than a dialog over it, and adds a task from its header: a date word
 * at the end of the title (« demain ») sets the due day. « À venir » is used for the writes, so the
 * day of the other journeys is left alone.
 */
test('the views are menu entries with their numbers, a task opens in a panel, and a quick add with « demain » lands in « À venir »', async ({ page, api }) => {
  const title = perAttempt('Rappeler le plombier')
  const tomorrow = daysFrom(parisDay(), 1)
  const board = new TasksPage(page)
  let createdId: string | undefined

  try {
    // Given the page on « Aujourd'hui »: titled with the day, the routines one click away…
    await board.open()
    await expect(board.title).toBeVisible()
    await expect(board.heading).toHaveText("Aujourd'hui")
    await expect(board.routinesLink).toBeVisible()
    await expect(page.getByRole('navigation', { name: "Fil d'Ariane" })).toContainText("Aujourd'hui")

    // …and the menu lists the four views under « Tâches », each with its number.
    await board.openMenu()
    for (const view of ["Aujourd'hui", 'À venir', 'Un jour', 'Faites'] as const) {
      await expect(board.menuEntry(view), view).toBeVisible()
    }
    await expect.poll(() => board.count('Faites'), { message: 'the « Faites » number' }).toBeGreaterThanOrEqual(1)

    // When I go to « À venir » from the menu, the list has its Urgence and Échéance columns.
    await board.select('À venir')
    await expect(page.getByRole('navigation', { name: "Fil d'Ariane" })).toContainText('À venir')
    await expect(board.content.getByText('Urgence', { exact: true })).toBeVisible()
    await expect(board.content.getByText('Échéance', { exact: true })).toBeVisible()
    const before = await board.count('À venir')

    // When I type « … demain » in the header and validate…
    await board.quickAdd.fill(`${title} demain`)
    await board.quickAdd.press('Enter')

    // …then the task is listed, without the date word in its title, due tomorrow…
    await expect(board.row(title)).toHaveCount(1)
    await expect(board.quickAdd).toHaveValue('')
    await expect(board.row(title).getByTestId('urgency-badge')).toBeVisible()
    const stored = await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
      what: `The task "${title}"`,
    })
    createdId = stored.id
    expect(stored.dueDate?.slice(0, 10)).toBe(tomorrow)
    // …and the menu's number follows.
    await expect.poll(() => board.count('À venir'), { message: 'the « À venir » number should follow the add' }).toBeGreaterThan(before)

    // When I open it, the sheet is a panel beside the list, not a dialog over it.
    await board.row(title).getByRole('button', { name: `Ouvrir ${title}`, exact: true }).click()
    await expect(board.sheet).toBeVisible()
    await expect(board.sheet.getByRole('heading', { name: 'Modifier la tâche' })).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(board.row(title)).toBeVisible()
    await board.sheet.getByRole('button', { name: 'Fermer la fiche' }).click()
    await expect(board.sheet).toBeHidden()

    // When I follow « Routines » from the page, it has its own route and title.
    await board.select('Routines')
    await expect(board.newRoutine).toBeVisible()
    await expect(page.getByRole('navigation', { name: "Fil d'Ariane" })).toContainText('Routines')
  } finally {
    if (createdId) await api.delete(`/api/tasks/${createdId}`)
  }
})

/**
 * « Tâches » as the mockup draws it — MAG-644, extends MAG-100.
 *
 * The views are sub-entries of the menu with their numbers and a route each; the page is titled with
 * the day, lists its tasks in the full width with an Urgence and an Échéance column, opens a task in a
 * panel beside the list rather than a dialog over it, and adds a task from its header: a date word
 * at the end of the title (« demain ») sets the due day. « À venir » is used for the writes, so the
 * day of the other journeys is left alone.
 */
test('the views are menu entries with their numbers, a task opens in a panel, and a quick add with « demain » lands in « À venir »', async ({ page, api }) => {
  const title = perAttempt('Rappeler le plombier')
  const tomorrow = daysFrom(parisDay(), 1)
  const board = new TasksPage(page)
  const trail = page.getByRole('navigation', { name: "Fil d'Ariane" })
  let createdId: string | undefined

  try {
    // Given the page on « Aujourd'hui »: titled with the day, the routines one click away…
    await board.open()
    await expect(board.title).toBeVisible()
    await expect(board.heading).toHaveText("Aujourd'hui")
    await expect(board.routinesLink).toBeVisible()
    await expect(trail).toContainText("Aujourd'hui")

    // …and the menu lists the four views under « Tâches », each with its number.
    await board.openMenu()
    for (const view of ["Aujourd'hui", 'À venir', 'Un jour', 'Faites'] as const) {
      await expect(board.menuEntry(view), view).toBeVisible()
    }
    await expect.poll(() => board.count('Faites'), { message: 'the « Faites » number' }).toBeGreaterThanOrEqual(1)

    // When I go to « À venir » from the menu, the list has its Urgence and Échéance columns.
    await board.select('À venir')
    await expect(trail).toContainText('À venir')
    await expect(board.content.getByText('Urgence', { exact: true })).toBeVisible()
    await expect(board.content.getByText('Échéance', { exact: true })).toBeVisible()
    const before = await board.count('À venir')

    // When I type « … demain » in the header and validate…
    await board.quickAdd.fill(`${title} demain`)
    await board.quickAdd.press('Enter')

    // …then the task is listed, without the date word in its title, due tomorrow…
    await expect(board.row(title)).toHaveCount(1)
    await expect(board.quickAdd).toHaveValue('')
    await expect(board.row(title).getByTestId('urgency-badge')).toBeVisible()
    const stored = await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
      what: `The task "${title}"`,
    })
    createdId = stored.id
    expect(stored.dueDate?.slice(0, 10)).toBe(tomorrow)
    // …and the menu's number follows.
    await expect.poll(() => board.count('À venir'), { message: 'the « À venir » number should follow the add' }).toBeGreaterThan(before)

    // When I open it, the sheet is a panel beside the list, not a dialog over it.
    await board.row(title).getByRole('button', { name: `Ouvrir ${title}`, exact: true }).click()
    await expect(board.sheet).toBeVisible()
    await expect(board.sheet.getByRole('heading', { name: 'Modifier la tâche' })).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(board.row(title)).toBeVisible()
    await board.sheet.getByRole('button', { name: 'Fermer la fiche' }).click()
    await expect(board.sheet).toBeHidden()

    // When I follow « Routines » from the page, it has its own route and title.
    await board.select('Routines')
    await expect(board.newRoutine).toBeVisible()
    await expect(trail).toContainText('Routines')
  } finally {
    if (createdId) await api.delete(`/api/tasks/${createdId}`)
  }
})

/**
 * The day holds five tasks at most, « Maintenant » on top and the reserve listed
 * after them — MAG-497, extends MAG-100 (one full-width list since MAG-644).
 *
 * Every journey that writes today's day belongs in this block, serial: the day
 * is one per user and date, and two journeys replacing it at once would each
 * read the other's list. The one here puts its own back to empty at the end.
 */
test.describe('the day of five', () => {
  test.describe.configure({ mode: 'serial' })

  test('ticking the first task of the day moves « Maintenant » to the second, and the reserve follows the five in the same list', async ({ page, api }) => {
    const today = parisDay()
    const titles = Array.from({ length: 7 }, (_, i) => perAttempt(`Journée ${i + 1} — ranger le bureau`))
    const ids: string[] = []

    try {
      // Given seven open tasks of this attempt, the second with its first step and its minutes…
      for (const [i, title] of titles.entries()) {
        ids.push(await createTask(api, i === 1 ? { title, firstStep: 'Vider le premier tiroir', estimatedMinutes: 10 } : { title }))
      }
      // …five of them in today's day, through the API.
      const planned = await setDayPlan(api, today, ids.slice(0, 5))
      expect(planned.slots.map((slot) => slot.task.id)).toEqual(ids.slice(0, 5))
      expect(planned.now?.task.id).toBe(ids[0])
      expect([planned.done, planned.total]).toEqual([0, 5])
      expect(planned.reserve.map((task) => task.id)).toEqual(expect.arrayContaining(ids.slice(5)))

      const board = new TasksPage(page)
      await board.open()
      await expect(board.now).toContainText(titles[0])
      await expect(board.ring).toHaveAccessibleName('0 sur 5')
      // The five come first, in order, and the two others follow them in the same list.
      await expect(board.dayRows).toContainText(titles.slice(0, 5))
      for (const title of titles) await expect(board.slot(title)).toHaveCount(1)
      await expect(board.dayRows).toContainText([titles[4], titles[5]])
      await expect.poll(() => board.count("Aujourd'hui"), { message: 'the menu counts the five' }).toBe(5)

      // When I tick the first in the admin, and take the pause its bravo offers (MAG-508)…
      await board.slot(titles[0]).getByRole('checkbox', { name: `Terminer ${titles[0]}` }).click()
      await expect(board.bravo).toHaveAccessibleName(`${titles[0]} · faite`)
      await board.pause.click()
      await expect(board.bravo).toBeHidden()

      // …then « Maintenant » shows the second, with its first step and its minutes…
      await expect(board.now).toContainText(titles[1])
      await expect(board.now).toContainText('Vider le premier tiroir')
      await expect(board.now).toContainText('10 min')
      // …the ring says one out of five…
      await expect(board.ring).toHaveAccessibleName('1 sur 5')
      await expect(board.ring).toHaveText('1 sur 5')
      await expect(board.slot(titles[0]).getByRole('checkbox', { name: `Rouvrir ${titles[0]}` })).toBeChecked()
      // …the menu counts four left…
      await expect.poll(() => board.count("Aujourd'hui"), { message: 'the menu counts what is left' }).toBe(4)
      // …and the two others are still listed after the five.
      for (const title of titles.slice(5)) await expect(board.slot(title)).toHaveCount(1)

      // The data agrees: the tick is the first slot's, and « now » the second.
      await expect
        .poll(async () => {
          const day = await dayPlan(api, today)

          return [day.done, day.total, day.now?.task.id, day.slots[0]?.task.completedAt != null]
        }, { timeout: 30_000, message: 'the day should hold the tick' })
        .toEqual([1, 5, ids[1], true])
    } finally {
      // Leave the day as the other journeys expect it: empty, and none of these tasks around.
      await setDayPlan(api, today, [])
      for (const id of ids) await api.delete(`/api/tasks/${id}`)
    }
  })

  /**
   * The Focus (MAG-499, extends MAG-497): one task, its first step, ten minutes
   * on a ring and its petits pas — the task's subtasks. « Plus tard, sans souci »
   * sends the task after the others not done, without a word of reproach.
   */
  test('the Focus of « Maintenant », then « Plus tard, sans souci », moves the task last and « Maintenant » shows the next one', async ({ page, api }) => {
    const today = parisDay()
    const titles = ['Focus 1 — appeler la banque', 'Focus 2 — ranger le bureau', 'Focus 3 — arroser'].map(perAttempt)
    const ids: string[] = []
    const stepIds: string[] = []

    try {
      // Given three open tasks, the first with a first step and two petits pas (subtasks)…
      ids.push(await createTask(api, { title: titles[0], firstStep: 'Trouver le RIB' }))
      ids.push(await createTask(api, { title: titles[1] }))
      ids.push(await createTask(api, { title: titles[2] }))
      const steps = [perAttempt('Chercher le numéro'), perAttempt('Appeler le conseiller')]
      for (const title of steps) stepIds.push(await createTask(api, { title, parent: `/api/tasks/${ids[0]}` }))
      for (const title of steps) {
        await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
          what: `The petit pas "${title}"`,
        })
      }
      // …all three in today's day.
      await setDayPlan(api, today, ids)

      const board = new TasksPage(page)
      await board.open()
      await expect(board.now).toContainText(titles[0])

      // When I open the Focus from « Maintenant »…
      await board.now.getByRole('button', { name: 'Juste 10 minutes' }).click()

      // …it holds the task, its first step marked « 2 min », the ring at 10:00 and the petits pas.
      const focus = board.focus(titles[0])
      await expect(focus).toBeVisible()
      await expect(focus).toContainText('Trouver le RIB')
      await expect(focus).toContainText('2 min')
      await expect(board.timer).toHaveText('10:00')
      await expect(focus.getByRole('button', { name: /Maggie/ })).toHaveCount(0)
      for (const title of steps) await expect(focus.getByRole('checkbox', { name: title })).not.toBeChecked()

      // Ticking a petit pas writes the subtask alone.
      await focus.getByRole('checkbox', { name: steps[0] }).check()
      await expect
        .poll(async () => (await tasks(api)).find((task) => task.id === stepIds[0])?.completedAt != null, {
          timeout: 30_000,
          message: 'the ticked petit pas should be stored as completed',
        })
        .toBe(true)
      expect((await tasks(api)).find((task) => task.id === ids[0])?.completedAt ?? null, 'the task was completed too').toBeNull()

      // And the timer runs.
      await focus.getByRole('button', { name: 'Démarrer' }).click()
      await expect(focus.getByRole('button', { name: 'Pause' })).toBeVisible()

      // When I say « Plus tard, sans souci »…
      await focus.getByRole('button', { name: 'Plus tard, sans souci' }).click()

      // …the day is back, « Maintenant » shows the next task and the first one is the last of the slots…
      await expect(board.focus(titles[0])).toHaveCount(0)
      await expect(board.now).toContainText(titles[1])
      await expect(board.now).not.toContainText(/retard|reproche/i)
      await expect(board.dayRows).toContainText([titles[1], titles[2], titles[0]])
      // …and the data agrees.
      await expect
        .poll(async () => (await dayPlan(api, today)).slots.map((slot) => slot.task.id), {
          timeout: 30_000,
          message: 'the day should hold the task last',
        })
        .toEqual([ids[1], ids[2], ids[0]])
      expect((await dayPlan(api, today)).now?.task.id).toBe(ids[1])
    } finally {
      await setDayPlan(api, today, [])
      for (const id of [...stepIds, ...ids]) await api.delete(`/api/tasks/${id}`)
    }
  })
})

/**
 * « Ta journée » (MAG-498) and « Le soir » (MAG-500), extending MAG-100: at 8:30 the first visit to « Tâches » opens the
 * morning's proposal — five tasks at most with why and minutes, the time against the free time,
 * the rest in a line — and nothing is written until « C'est parti ».
 *
 * Signed in as the neighbour, whose day nobody else writes. A day cannot be unconfirmed, so a
 * retry meets a day already confirmed and asks for the proposal with « Changer » instead.
 */
test.describe('the morning and the evening', () => {
  test.describe.configure({ mode: 'serial' })

  test('opens at 8:30, swaps one task for the next of the reserve, and « C\'est parti » writes the five', async ({ otherUser }) => {
    const { page, api } = otherUser
    const today = parisDay()
    await page.context().clock.setFixedTime(new Date(parisTime(today, '08:30:00')))

    const late = [perAttempt('Matin — déclaration en retard'), perAttempt('Matin — facture en retard')]
    const others = Array.from({ length: 4 }, (_, i) => perAttempt(`Matin — au calme ${i + 1}`))
    const ids: string[] = []

    try {
      // Given two tasks put off (due on a past day) and four with no day, besides the neighbour's own…
      const past = daysFrom(today, -3)
      for (const title of late) ids.push(await createTask(api, { title, dueDate: past, estimatedMinutes: 10 }))
      for (const title of others) ids.push(await createTask(api, { title }))
      const confirmed = (await dayPlan(api, today)).confirmedAt != null

      const board = new TasksPage(page)
      if (confirmed) {
        await board.open()
        await board.change.click()
      } else {
        // …when I open « Tâches » for the first time of the morning, the proposal comes before the day…
        await board.openMorning()
      }
      await expect(board.morning).toBeVisible()
      await expect(board.proposed.getByRole('listitem').first()).toBeVisible()
      const count = await board.proposed.getByRole('listitem').count()
      expect(count).toBeGreaterThanOrEqual(1)
      expect(count).toBeLessThanOrEqual(5)

      // …the put-off tasks first, each with why and minutes, the time against the free time, the rest in a line.
      for (const title of late) {
        await expect(board.proposedRow(title)).toContainText('reportée')
        await expect(board.proposedRow(title)).toContainText('10 min')
      }
      await expect(board.morningTime).toBeVisible()
      await expect(board.morning.getByRole('heading', { name: 'Ensuite' })).toBeVisible()

      // Nothing is written yet: the day still holds none of them.
      const untouched = (await dayPlan(api, today)).slots.map((slot) => slot.task.id)
      for (const id of ids) expect(untouched).not.toContain(id)

      // When I swap the last task, it takes the next of the reserve in the same place…
      const last = board.proposed.getByRole('listitem').nth(count - 1)
      const swap = last.getByRole('button', { name: /^Échanger / })
      const leaving = ((await swap.getAttribute('aria-label')) ?? '').replace(/^Échanger /, '')
      expect(leaving, 'the swap button names its task').not.toBe('')
      await swap.click()
      await expect(board.proposed.getByRole('listitem')).toHaveCount(count)
      await expect(board.proposed.getByText(leaving, { exact: true })).toHaveCount(0)

      // When I tap « C'est parti », the day holds the kept tasks and the proposal gives way to it.
      const kept = await board.proposed.getByRole('listitem').getByText(/^Matin — /).allTextContents()
      await board.go.click()
      await expect(board.morning).toBeHidden()
      await expect(board.dayRows.first()).toBeVisible()

      await expect
        .poll(
          async () => {
            const day = await dayPlan(api, today)

            return [day.confirmedAt != null, day.slots.map((slot) => slot.task.title).filter((title) => title.startsWith('Matin — '))]
          },
          { timeout: 30_000, message: 'the day should hold the kept tasks, confirmed' },
        )
        .toEqual([true, expect.arrayContaining(kept)])

      // The morning does not come back by itself once the day is confirmed.
      await board.open()
      await expect(board.morning).toBeHidden()
      // « Changer » asks for it again, and « Plus tard » leaves it without writing.
      await board.change.click()
      await expect(board.morning).toBeVisible()
      await board.later.click()
      await expect(board.morning).toBeHidden()
      await expect(board.dayReady).toBeVisible()
    } finally {
      await setDayPlan(api, today, [])
      for (const id of ids) await api.delete(`/api/tasks/${id}`)
    }
  })

  /**
   * At 21:05, a day of five with three done: the first visit opens the review on what is done,
   * then the two left; one goes in tomorrow's five, the other is set aside into « Un jour ».
   * « Plus tard » closes without recording the review, so a retry meets the evening again.
   */
  test('at 21:05 the review lists the three done then the two left; « Demain » and « Mettre de côté » write tomorrow and « Un jour »', async ({ otherUser }) => {
    const { page, api } = otherUser
    const today = parisDay()
    const tomorrow = daysFrom(today, 1)
    await page.context().clock.setFixedTime(new Date(parisTime(today, '21:05:00')))

    const done = [1, 2, 3].map((i) => perAttempt(`Soir — faite ${i}`))
    const forTomorrow = perAttempt('Soir — pour demain')
    const letGo = perAttempt('Soir — à lâcher')
    const ids: string[] = []

    try {
      // Given a day of five, three of them done…
      for (const title of done) ids.push(await createTask(api, { title }))
      ids.push(await createTask(api, { title: forTomorrow }))
      ids.push(await createTask(api, { title: letGo, dueDate: daysFrom(today, 2) }))
      await setDayPlan(api, today, ids)
      for (const id of ids.slice(0, 3)) {
        const ticked = await api.patch(`/api/tasks/${id}`, {
          headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
          data: { completedAt: new Date(parisTime(today, '10:00:00')).toISOString() },
        })
        expect(ticked.ok(), `PATCH /api/tasks/${id} answered ${ticked.status()}`).toBe(true)
      }
      await setDayPlan(api, tomorrow, [], false)

      // …when I open « Tâches », the review comes before the day: the three done, then the two left.
      const board = new TasksPage(page)
      await board.openMorning()
      await expect(board.evening).toBeVisible()
      await expect(board.evening.getByRole('heading', { name: '3 sur 5' })).toBeVisible()
      await expect(board.eveningDone.getByRole('listitem')).toHaveText(done)
      await expect(board.eveningLeft.getByRole('listitem')).toHaveCount(2)
      await expect(board.leftRow(forTomorrow)).toBeVisible()
      await expect(board.leftRow(letGo)).toBeVisible()

      // When I put one in tomorrow's five and let the other go…
      await board.outcome(forTomorrow, 'Demain').click()
      await expect(board.outcome(forTomorrow, 'Demain')).toHaveAttribute('aria-pressed', 'true')
      await board.outcome(letGo, 'Mettre de côté').click()
      await expect(board.leftRow(letGo).getByText('Un jour', { exact: true })).toBeVisible()

      // …then tomorrow holds the first, not settled, and the second waits in « Un jour » without a due day.
      await expect
        .poll(
          async () => {
            const next = await dayPlan(api, tomorrow)

            return [next.confirmedAt, next.slots.map((slot) => slot.task.title)]
          },
          { timeout: 30_000, message: "tomorrow's day should hold the task, unsettled" },
        )
        .toEqual([null, [forTomorrow]])
      await expect.poll(() => viewTitles(api, 'someday'), { timeout: 30_000 }).toContain(letGo)
      expect((await dayPlan(api, today)).slots.map((slot) => slot.task.title)).not.toContain(letGo)

      // « Plus tard » closes the review without recording it; the day is shown.
      await board.eveningLater.click()
      await expect(board.evening).toBeHidden()
      await expect(board.dayReady).toBeVisible()
      const review = await api.get(`/api/day-reviews/${today}`, { headers: { Accept: 'application/json' } })
      expect(((await review.json()) as { reviewedAt: string | null }).reviewedAt).toBeNull()
    } finally {
      await setDayPlan(api, today, [])
      await setDayPlan(api, tomorrow, [], false)
      for (const id of ids) await api.delete(`/api/tasks/${id}`)
    }
  })
})

/**
 * The bravo of a task done (MAG-508): a tick from the day's list opens it over the
 * day, with « {titre} · faite », a message of the tone set and the day so far;
 * the next message is never the same; the last of the five is a shooting star.
 *
 * On an account of its own (`e2e-bravo@maggie.local`): a shooting star comes
 * once a local day per user, and the other journeys tick tasks too. A run again
 * on the same stack and day finds that star gone — reseed first.
 */
test('ticking from the day\'s list opens the bravo; the next message differs; the last of the five is a shooting star', async ({ bravoUser }) => {
  const { page, api } = bravoUser
  const today = parisDay()
  const titles = Array.from({ length: 5 }, (_, i) => perAttempt(`Bravo ${i + 1} — ranger le salon`))
  const ids: string[] = []

  try {
    // Given the tone « direct » and a day of five, two of them done…
    const tone = await api.put('/api/task-settings/me', {
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      data: { tone: 'direct' },
    })
    expect(tone.ok(), `PUT /api/task-settings/me answered ${tone.status()}`).toBe(true)
    for (const [i, title] of titles.entries()) ids.push(await createTask(api, i === 3 ? { title, estimatedMinutes: 15 } : { title }))
    await setDayPlan(api, today, ids)
    for (const id of ids.slice(0, 2)) {
      const ticked = await api.patch(`/api/tasks/${id}`, {
        headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
        data: { completedAt: e2eNow().toISOString() },
      })
      expect(ticked.ok(), `PATCH /api/tasks/${id} answered ${ticked.status()}`).toBe(true)
    }

    const board = new TasksPage(page)
    await board.open()
    await expect(board.ring).toHaveAccessibleName('2 sur 5')

    // When I tick the third from the day's list…
    await board.slot(titles[2]).getByRole('checkbox', { name: `Terminer ${titles[2]}` }).click()

    // …then the bravo says it is done, with a message of the « direct » bank and the day so far…
    await expect(board.bravo).toHaveAccessibleName(`${titles[2]} · faite`)
    await expect(board.bravoMessage).not.toBeEmpty()
    const first = (await board.bravoMessage.textContent()) ?? ''
    await expect(board.bravo).toContainText("3 sur 5 aujourd'hui")
    await expect(board.next(titles[3])).toHaveAccessibleName(`Suivante : ${titles[3]} · 15 min`)
    // …the one the API laid down, a star.
    const reward = await api.get(`/api/rewards/task/${ids[2]}`, { headers: { Accept: 'application/json' } })
    expect(reward.ok(), `GET /api/rewards/task/${ids[2]} answered ${reward.status()}`).toBe(true)
    const stored = (await reward.json()) as { message: string; messageKey: string; star: boolean }
    expect([stored.message, stored.messageKey.startsWith('direct.'), stored.star]).toEqual([first, true, true])

    // When I take a pause, then tick the fourth, its message is not the same.
    await board.pause.click()
    await expect(board.bravo).toBeHidden()
    await board.slot(titles[3]).getByRole('checkbox', { name: `Terminer ${titles[3]}` }).click()
    await expect(board.bravo).toHaveAccessibleName(`${titles[3]} · faite`)
    await expect(board.bravoMessage).not.toBeEmpty()
    await expect(board.bravoMessage).not.toHaveText(first)
    await expect(board.bravo).toContainText("4 sur 5 aujourd'hui")

    // When I tick the fifth, the day is done: a shooting star.
    await board.pause.click()
    await expect(board.bravo).toBeHidden()
    await board.slot(titles[4]).getByRole('checkbox', { name: `Terminer ${titles[4]}` }).click()
    await expect(board.bravo).toHaveAccessibleName(`${titles[4]} · faite`)
    await expect(board.bravo).toContainText("5 sur 5 aujourd'hui")
    await expect(board.bravoMessage).toContainText('Étoile filante')
    await expect(board.bravo.getByTestId('bravo-shooting-star')).toBeAttached()
  } finally {
    await setDayPlan(api, today, [])
    for (const id of ids) await api.delete(`/api/tasks/${id}`)
  }
})

/**
 * The constellation of the month (MAG-509, extends MAG-100): one star per task done, only
 * those earned. The seed gives `e2e-stars@maggie.local` four stars this month and seven last
 * month; a tick brings the month to five, drawn under the bravo and on the task in the day's list,
 * and the evening goes back to last month and its seven, the total never lost.
 *
 * An account of its own, because the counts are exact: any other tick of it would move them.
 * A run again on the same stack finds the star of the first one — reseed first.
 */
test('a tick adds a star to the month: the bravo draws five, the evening goes back to last month and its seven', async ({ starsUser }) => {
  const { page, api } = starsUser
  const today = parisDay()
  const title = perAttempt('Étoile — ranger le bureau')
  const ids: string[] = []

  try {
    // Given four stars this month and seven last month (the seed), and a day of one task…
    ids.push(await createTask(api, { title }))
    await setDayPlan(api, today, ids)

    const board = new TasksPage(page)
    await board.open()
    await expect(board.starOf(title)).toBeHidden()

    // When I tick it from the day's list…
    await board.slot(title).getByRole('checkbox', { name: `Terminer ${title}` }).click()

    // …then the bravo draws five stars, the latest one marked, and says « 5 étoiles »…
    await expect(board.bravo).toHaveAccessibleName(`${title} · faite`)
    await expect(board.constellationOfBravo).toContainText('· 5 étoiles')
    await expect(board.constellationOfBravo.getByTestId('constellation-star')).toHaveCount(5)
    await expect(board.constellationOfBravo.locator('[data-latest="true"]')).toHaveCount(1)
    expect(await (await api.get('/api/stars', { headers: { Accept: 'application/json' } })).json()).toMatchObject({ count: 5, total: 12 })

    // …and the task carries its star in the day's list.
    await board.pause.click()
    await expect(board.bravo).toBeHidden()
    await expect(board.starOf(title)).toBeVisible()

    // When I open the evening and go back a month, it shows the seven of last month and the total.
    await board.openEveningButton.click()
    await expect(board.evening).toBeVisible()
    await expect(board.constellationOfEvening).toContainText('· 5 étoiles')
    await expect(board.constellationOfEvening.getByRole('button', { name: 'Mois suivant' })).toBeDisabled()
    await board.constellationOfEvening.getByRole('button', { name: 'Mois précédent' }).click()
    await expect(board.constellationOfEvening).toContainText('· 7 étoiles')
    await expect(board.constellationOfEvening.getByTestId('constellation-star')).toHaveCount(7)
    await expect(board.constellationOfEvening.getByTestId('constellation-total')).toContainText('12 étoiles depuis toujours')
    await expect(board.constellationOfEvening.getByRole('button', { name: 'Mois suivant' })).toBeEnabled()
  } finally {
    await setDayPlan(api, today, [])
    for (const id of ids) await api.delete(`/api/tasks/${id}`)
  }
})

/**
 * A finished task can be reopened — `MAG-112`, and the reason the seed ships one.
 *
 * From the calendar rather than from the dashboard, and that is not a detour: the
 * digest only ever reads undone tasks, so a completed one has no checkbox there to
 * untick. The agenda draws it with a ✓ and its pencil opens the form that holds the
 * "Terminée" switch, which is the only way back.
 */
test('a finished task can be reopened from the agenda', async ({ page, api }) => {
  const title = 'Sortir les poubelles'
  const taskId = seedId('e2e_task_done')

  // Put the seeded task back to "done" before anything else. It is the one test
  // here that changes a fixture rather than its own row, so a retry would
  // otherwise start on the state the first attempt left — with nothing to reopen
  // and no ✓ on the grid. Done unconditionally, which is what keeps it free of the
  // branch an "if it is already done" check would need.
  await completeTask(api, taskId)

  const calendar = new CalendarPage(page)
  await calendar.open()

  // Due the day before the anchor, so one step back in the day view always lands
  // on it — where month view would have it in the previous month half the time.
  await calendar.chooseView('Jour')
  await calendar.goBack()

  await calendar.chip(`✓ ${title}`).click()
  await expect(calendar.popover).toBeVisible()
  await calendar.editFromPopover()

  const dialog = calendar.editTaskDialog
  await expect(dialog).toBeVisible()
  await dialog.getByRole('switch', { name: 'Terminée' }).uncheck()
  await dialog.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(dialog).toBeHidden()

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === taskId)?.completedAt == null, {
      timeout: 30_000,
      message: 'the reopened task should have no completion date left',
    })
    .toBe(true)
})

/**
 * A subtask is ticked off from its parent's form, and the parent stays open — MAG-404.
 *
 * Google Tasks nests one level of subtasks under a task; the seed gives
 * « Préparer le déménagement » two of them, in order. Ticking the first must
 * write that subtask alone: a parent completed along with it would drop off the
 * day while half its list is still to do.
 */
test('a subtask is ticked off from its parent, which stays open', async ({ page, api }) => {
  const title = 'Préparer le déménagement'
  const parentId = seedId('e2e_task_moving')
  const firstId = seedId('e2e_task_moving_boxes')

  // Reopen the subtask first: CI retries once without reseeding, and a retry
  // would otherwise start on the tick the first attempt left.
  await reopenTask(api, firstId)

  const calendar = new CalendarPage(page)
  await calendar.open()
  await calendar.chooseView('Jour')

  const dialog = await openTask(calendar, title)
  await expect(dialog.getByLabel('Notes')).toHaveValue('Les cartons sont à la cave')

  const subtasks = dialog.getByRole('region', { name: 'Sous-tâches' })
  await expect(subtasks.getByRole('checkbox')).toHaveCount(2)
  await expect(subtasks.getByRole('checkbox').first()).toHaveAccessibleName('Réserver le camion')
  await expect(subtasks.getByRole('checkbox').nth(1)).toHaveAccessibleName("Changer d'adresse à la banque")

  const first = subtasks.getByRole('checkbox', { name: 'Réserver le camion' })
  await expect(first).not.toBeChecked()
  await first.check()
  await expect(first).toBeChecked()

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === firstId)?.completedAt != null, {
      timeout: 30_000,
      message: 'the ticked subtask should be stored as completed',
    })
    .toBe(true)
  const stored = await tasks(api)
  expect(stored.find((task) => task.id === parentId)?.completedAt ?? null, 'the parent was completed too').toBeNull()
  expect(
    stored.find((task) => task.id === seedId('e2e_task_moving_address'))?.completedAt ?? null,
    'the other subtask was completed too',
  ).toBeNull()

  // The state holds once the page and the form are read again from the API.
  await dialog.getByRole('button', { name: 'Annuler' }).click()
  await expect(dialog).toBeHidden()
  await calendar.open()
  await calendar.chooseView('Jour')
  const reopened = await openTask(calendar, title)
  const again = reopened.getByRole('region', { name: 'Sous-tâches' })
  await expect(again.getByRole('checkbox', { name: 'Réserver le camion' })).toBeChecked()
  await expect(again.getByRole('checkbox', { name: "Changer d'adresse à la banque" })).not.toBeChecked()
})

/** Opens the edit form of the task drawn as `title`: chip, detail card, pencil. */
async function openTask(calendar: CalendarPage, title: string) {
  await calendar.openFoldableChip(title)
  await calendar.editFromPopover()
  const dialog = calendar.editTaskDialog
  await expect(dialog).toBeVisible()

  return dialog
}

/** Reopens a task through the API and waits for the collection to agree. */
async function reopenTask(api: APIRequestContext, taskId: string): Promise<void> {
  const response = await api.patch(`/api/tasks/${taskId}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { completedAt: null },
  })
  expect(response.ok(), `PATCH /api/tasks/${taskId} answered ${response.status()}`).toBe(true)

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === taskId)?.completedAt == null, {
      timeout: 30_000,
      message: 'the task should be findable as open before the form is read',
    })
    .toBe(true)
}

/** Marks a task done through the API and waits for the grid's index to agree. */
async function completeTask(api: APIRequestContext, taskId: string): Promise<void> {
  const response = await api.patch(`/api/tasks/${taskId}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { completedAt: '2026-01-01T20:15:00+01:00' },
  })
  expect(response.ok(), `PATCH /api/tasks/${taskId} answered ${response.status()}`).toBe(true)

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === taskId)?.completedAt != null, {
      timeout: 30_000,
      message: 'the task should be findable as completed before the grid is read',
    })
    .toBe(true)
}

/** Creates a task through the API and returns its id. */
async function createTask(
  api: APIRequestContext,
  data: { title: string; dueDate?: string; criticality?: string; estimatedMinutes?: number; firstStep?: string; parent?: string },
): Promise<string> {
  const response = await api.post('/api/tasks', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data,
  })
  expect(response.ok(), `POST /api/tasks answered ${response.status()}`).toBe(true)

  return String(((await response.json()) as { id: string }).id)
}

/** Today's day (MAG-497), as the API reads it. */
async function dayPlan(api: APIRequestContext, date = parisDay()): Promise<DayPlan> {
  const response = await api.get(`/api/day-plans/${date}`, { headers: { Accept: 'application/json' } })
  expect(response.ok(), `GET /api/day-plans/${date} answered ${response.status()}`).toBe(true)

  return (await response.json()) as DayPlan
}

/**
 * Replaces the whole list of the day's tasks, in order; answers the day as it stands.
 * `confirm: false` writes without settling the day, as the evening's « Demain » does (MAG-500).
 */
async function setDayPlan(api: APIRequestContext, date: string, taskIds: string[], confirm = true): Promise<DayPlan> {
  const response = await api.put(`/api/day-plans/${date}`, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    data: { slots: taskIds.map((taskId) => ({ type: 'task', taskId })), ...(confirm ? {} : { confirm }) },
  })
  expect(response.ok(), `PUT /api/day-plans/${date} answered ${response.status()}`).toBe(true)

  return (await response.json()) as DayPlan
}

/** The titles of a view of the « Tâches » page, as the API sorts them. */
async function viewTitles(api: APIRequestContext, view: string): Promise<string[]> {
  const response = await api.get(`/api/tasks/view/${view}`, { headers: { Accept: 'application/ld+json' } })
  expect(response.ok(), `GET /api/tasks/view/${view} answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { member: { title: string }[] }).member.map((task) => task.title)
}
