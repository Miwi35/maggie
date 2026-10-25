import { test, expect, parisDay, seedDate } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * « Déplace le repas boeuf bourguignon à demain midi » — MAG-639.
 *
 * The meal is planned six weeks out, under a name with a ligature: she must find it from « boeuf »
 * with no date and no agenda, then move it on the id her own lookup gave — tomorrow, lunch.
 * `97-move-meal-by-name.yaml` scripts the model's side; what is proved is the chain through the real
 * `manage_meals` and the day and slot the API stored. Whether the real model picks that tool and
 * leaves a remembered agenda filter alone is the eval suite's (`scripts/prompt-lab/scenarios/`).
 *
 * The signed-in account is the one this journey owns, so the meal it moves is in nobody else's week.
 */

/** The day after `day` (YYYY-MM-DD), by calendar arithmetic: no hour of daylight saving can move it. */
function dayAfter(day: string): string {
  const [year, month, date] = day.split('-').map(Number)

  return new Date(Date.UTC(year, month - 1, date + 1)).toISOString().slice(0, 10)
}

test('Maggie finds a meal by its name, ligature or not, and moves it to tomorrow lunch — MAG-639', async ({ otherUser }) => {
  const { api } = otherUser
  const { retry } = test.info()
  const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

  const recipe = await api.post('/api/recipes', {
    headers: jsonLd,
    data: { name: `Bœuf bourguignon${0 === retry ? '' : ` essai ${retry}`}`, servings: 2 },
  })
  expect(recipe.status(), `POST /api/recipes answered ${recipe.status()}: ${await recipe.text()}`).toBe(201)
  const recipeIri = ((await recipe.json()) as { '@id': string })['@id']

  const planned = await api.post('/api/meals', {
    headers: jsonLd,
    data: { date: seedDate(42), slot: 'dinner', recipes: [recipeIri] },
  })
  expect(planned.status(), `POST /api/meals answered ${planned.status()}: ${await planned.text()}`).toBe(201)
  const mealId = ((await planned.json()) as { id: string }).id

  const shell = new AdminShell(otherUser.page)
  await shell.goto(ROUTES.calendar)
  await shell.expectLoaded()
  const events = await new ChatPanel(otherUser.page).send('Déplace le repas boeuf bourguignon à demain midi')

  expect(calledTools(events), 'the meals tool never ran — did the scenario stop matching?').toEqual(['manage_meals', 'manage_meals'])
  expect(toolResults(events)).toEqual([
    expect.objectContaining({ toolName: 'manage_meals', status: 'success' }),
    expect.objectContaining({ toolName: 'manage_meals', status: 'success' }),
  ])

  await expect
    .poll(
      async () => {
        const meal = (await (await api.get(`/api/meals/${mealId}`, { headers: { Accept: 'application/ld+json' } })).json()) as {
          date?: string
          slot?: string
        }

        return `${String(meal.date ?? '').slice(0, 10)} ${meal.slot}`
      },
      { message: 'the meal was not moved to tomorrow lunch', timeout: 30_000 },
    )
    .toBe(`${dayAfter(parisDay())} lunch`)
})
