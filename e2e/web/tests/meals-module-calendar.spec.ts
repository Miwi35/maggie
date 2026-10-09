import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * The meals' agenda stays out of the owner's list, and the module has a calendar
 * of its own — MAG-354.
 *
 * One planned meal and one ordinary event, both on the anchor's day and both under
 * a name the current attempt alone writes (CI retries once and nothing reseeds in
 * between). The general calendar shows the two with « Repas » once in its filters;
 * « Planning des repas », in the Nutrition menu, shows the meal and nothing else.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const DAY = seedDate()

interface Named {
  summary?: string
  name?: string
}

async function plan(api: APIRequestContext): Promise<{ recipe: string; event: string }> {
  const { retry } = test.info()
  const suffix = 0 === retry ? '' : ` essai ${retry}`
  const recipe = `Gratin MAG-354${suffix}`
  const event = `Dentiste MAG-354${suffix}`

  const createdRecipe = await api.post('/api/recipes', { headers: jsonLd, data: { name: recipe, servings: 2 } })
  expect(createdRecipe.status(), `POST /api/recipes answered ${createdRecipe.status()}`).toBe(201)
  const recipeIri = ((await createdRecipe.json()) as { '@id': string })['@id']

  const meal = await api.post('/api/meals', {
    headers: jsonLd,
    data: {
      summary: 'Dîner',
      date: DAY,
      slot: 'dinner',
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
      recipes: [recipeIri],
    },
  })
  expect(meal.status(), `POST /api/meals answered ${meal.status()}: ${await meal.text()}`).toBe(201)

  const createdEvent = await api.post('/api/events', {
    headers: jsonLd,
    data: {
      summary: event,
      startAt: `${DAY}T15:00:00+00:00`,
      endAt: `${DAY}T16:00:00+00:00`,
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
    },
  })
  expect(createdEvent.status(), `POST /api/events answered ${createdEvent.status()}`).toBe(201)

  await waitForIndexed<Named>(api, '/api/meals?itemsPerPage=200', (m) => String(m.summary).includes(recipe), { what: 'The planned meal' })
  await waitForIndexed<Named>(api, '/api/events?itemsPerPage=200', (e) => e.summary === event, { what: 'The ordinary event' })

  return { recipe, event }
}

test('the general agenda shows the meal and the event with « Repas » once, the meals planning only the meal — MAG-354', async ({ page, api }) => {
  const { recipe, event } = await plan(api)
  // The list carries the recipe's name inside the meal's summary (« Dîner : <recette> »); anchored at the
  // end so a retry does not also match the first attempt's meal.
  const mealChip = new RegExp(`${recipe}$`)

  // The API no longer lists the module's agenda among the user's.
  const listed = await api.get('/api/agendas?itemsPerPage=100', { headers: { Accept: 'application/ld+json' } })
  expect(listed.status()).toBe(200)
  const names = ((await listed.json()) as { member: Named[] }).member.map((a) => a.name)
  expect(names).not.toContain('Repas')

  const general = new CalendarPage(page)
  await general.open()
  await general.chooseView('Semaine')
  await expect(general.chip(event)).toBeVisible()
  await expect(general.grid.getByText(mealChip)).toBeVisible()
  await expect(general.content.getByText('Repas', { exact: true })).toHaveCount(1)

  // The menu entry, not a typed URL, is how the owner gets to the module's calendar.
  await general.navigateTo('Planning des repas', 'Nutrition')

  const module = page.getByTestId('module-calendar')
  await expect(module).toBeVisible()
  await expect(page.getByTestId('module-calendar-title')).toHaveText('Repas')
  await expect(page.getByTestId('agenda-row')).toHaveCount(0)

  await general.chooseView('Semaine')
  await expect(general.grid.getByText(mealChip)).toBeVisible()
  await expect(general.chip(event)).toHaveCount(0)

  await general.chooseView('Mois')
  await expect(general.grid.getByText(mealChip)).toBeVisible()
  await expect(general.chip(event)).toHaveCount(0)
})
