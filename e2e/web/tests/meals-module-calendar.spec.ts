import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { MealsPage } from '../pages/MealsPage.js'
import { adminUrl, ROUTES } from '../pages/routes.js'

/**
 * The meals' agenda stays out of the owner's list — MAG-354 — and the module no
 * longer has a calendar of its own: Cuisine › Repas is the one page, and the
 * old address lands on it — MAG-628.
 *
 * One planned meal and one ordinary event, both on the anchor's day and both under
 * a name the current attempt alone writes (CI retries once and nothing reseeds in
 * between). The general calendar shows the two with « Repas » once in its filters;
 * `#/meals/calendar` opens the Repas page in its Menu view, the meal on its day.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const DAY = seedDate()

// The account of this journey alone: the planning it reads is the one it wrote.
test.use({ journeyAccount: 'standard' })

interface Named {
  summary?: string
  name?: string
}

async function plan(api: APIRequestContext, agendaId: string): Promise<{ recipe: string; event: string }> {
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
      agenda: `/api/agendas/${agendaId}`,
      recipes: [recipeIri],
    },
  })
  expect(meal.status(), `POST /api/meals answered ${meal.status()}: ${await meal.text()}`).toBe(201)
  const mealId = ((await meal.json()) as { id: string }).id

  const createdEvent = await api.post('/api/events', {
    headers: jsonLd,
    data: {
      summary: event,
      startAt: `${DAY}T15:00:00+00:00`,
      endAt: `${DAY}T16:00:00+00:00`,
      agenda: `/api/agendas/${agendaId}`,
    },
  })
  expect(createdEvent.status(), `POST /api/events answered ${createdEvent.status()}`).toBe(201)

  await waitForIndexed<Named>(api, '/api/recipes?itemsPerPage=200', (r) => r.name === recipe, { what: 'The recipe of the meal' })
  await waitForIndexed<{ id: string }>(api, '/api/meals?itemsPerPage=200', (m) => m.id === mealId, { what: 'The planned meal' })
  await waitForIndexed<Named>(api, '/api/events?itemsPerPage=200', (e) => e.summary === event, { what: 'The ordinary event' })

  return { recipe, event }
}

test('the general agenda shows the meal and the event with « Repas » once, and #/meals/calendar lands on the Repas page in Menu — MAG-354, MAG-628', async ({ page, api, journeyUser }) => {
  const { recipe, event } = await plan(api, journeyUser.ref('e2e_agenda_personal'))
  // The list carries the recipe's name as the meal's summary; anchored at the
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

  // The old address of the module's calendar, as a bookmark or a link still has it.
  await page.goto(adminUrl(`${ROUTES.meals}/calendar`))

  const meals = new MealsPage(page)
  await meals.expectMenuView()
  await expect(page).toHaveURL(new RegExp(`#${ROUTES.meals}$`))
  await expect(page.getByTestId('module-calendar')).toHaveCount(0)
  await expect(meals.cardWith(recipe)).toBeVisible()
  await expect(meals.content.getByText(event)).toHaveCount(0)
})
