import { test, expect, seedId } from '../fixtures/index.js'
import type { APIRequestContext, Locator, Page } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { dayOfThisWeek } from '../helpers/week.js'
import { AdminShell } from '../pages/AdminShell.js'

/**
 * Moving a meal from one cell of the week to another — MAG-250.
 *
 * Both journeys plan a recipe of their own with **no ingredient** (nothing
 * reaches the shared grocery list) and look their meal up by its name, so they
 * run beside every other journey that plans meals. Each owns its cells: Tuesday
 * lunch and Thursday dinner for the mouse, Saturday dinner and Sunday lunch for
 * the keyboard.
 *
 * What is asserted is what the API stored — the day and the slot — and the cell
 * the meal is drawn in on a page loaded afresh, which is what the owner sees
 * the next morning.
 */

interface MealRow {
  summary: string
  date: string
  slot: string
  recipes?: unknown[]
}

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

async function planMeal(api: APIRequestContext, what: string, day: string, slot: 'lunch' | 'dinner'): Promise<string> {
  const { retry } = test.info()
  const name = `Cassoulet ${what}${0 === retry ? '' : ` essai ${retry}`}`

  const recipe = await api.post('/api/recipes', { headers: jsonLd, data: { name, servings: 2 } })
  expect(recipe.status(), `POST /api/recipes answered ${recipe.status()}`).toBe(201)
  const recipeIri = ((await recipe.json()) as { '@id': string })['@id']

  const meal = await api.post('/api/meals', {
    headers: jsonLd,
    data: {
      summary: 'Déjeuner',
      date: day,
      slot,
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
      recipes: [recipeIri],
    },
  })
  expect(meal.status(), `POST /api/meals answered ${meal.status()}: ${await meal.text()}`).toBe(201)

  // The week view reads from Elasticsearch: wait for the meal before opening it.
  await waitForIndexed<MealRow>(api, '/api/meals?itemsPerPage=200', (m) => String(m.summary).includes(name), {
    what: 'The meal this journey moves',
  })

  return name
}

const handleOf = (page: Page, name: string): Locator => page.getByRole('button', { name: new RegExp(`Déplacer le repas .*${name}`) })

/** A drag a real pointer makes: past the activation distance first, then over the cell. */
async function dragTo(page: Page, handle: Locator, cell: Locator): Promise<void> {
  const from = await handle.boundingBox()
  const to = await cell.boundingBox()
  if (!from || !to) throw new Error('the handle or the cell is not on screen')

  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(from.x + from.width / 2 + 12, from.y + from.height / 2 + 12, { steps: 3 })
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 15 })
  await page.mouse.up()
}

test('dragging Tuesday lunch onto Thursday dinner moves it, and it stays there after a reload — MAG-250', async ({ page, api }) => {
  const name = await planMeal(api, 'MAG-250 souris', dayOfThisWeek(1), 'lunch')
  const shell = new AdminShell(page)
  await shell.goto('/meals')

  const tuesdayLunch = shell.content.getByTestId('meal-cell-lunch-1')
  const thursdayDinner = shell.content.getByTestId('meal-cell-dinner-3')
  await expect(tuesdayLunch).toContainText(name)

  await dragTo(page, handleOf(page, name), thursdayDinner)

  await expect(thursdayDinner).toContainText(name)
  await expect(tuesdayLunch).not.toContainText(name)

  // The day and the slot the API holds, nothing else moved with them.
  const stored = await waitForIndexed<MealRow>(
    api,
    '/api/meals?itemsPerPage=200',
    (m) => String(m.summary).includes(name) && 'dinner' === m.slot,
    { what: 'The meal at Thursday dinner' },
  )
  expect(stored.date).toBe(dayOfThisWeek(3))
  expect(stored.recipes).toHaveLength(1)

  await page.reload()
  await expect(shell.content.getByTestId('meal-cell-dinner-3')).toContainText(name)
  await expect(shell.content.getByTestId('meal-cell-lunch-1')).not.toContainText(name)
})

test('the keyboard moves a meal: pick it up, an arrow per cell, drop — MAG-250', async ({ page, api }) => {
  const name = await planMeal(api, 'MAG-250 clavier', dayOfThisWeek(5), 'dinner')
  const shell = new AdminShell(page)
  await shell.goto('/meals')
  await expect(shell.content.getByTestId('meal-cell-dinner-5')).toContainText(name)

  const handle = handleOf(page, name)
  // dnd-kit measures the cells after each key: wait for what it announces
  // before the next one, or a key lands on rects that are not there yet.
  const announced = page.locator('[role="status"][aria-live="assertive"]')
  await handle.focus()
  await page.keyboard.press('Space')
  await expect(announced).toContainText('saisi')
  await page.keyboard.press('ArrowRight') // Saturday → Sunday
  await expect(announced).toContainText('dimanche, dîner')
  await page.keyboard.press('ArrowUp') // dinner → lunch
  await expect(announced).toContainText('dimanche, déjeuner')
  await page.keyboard.press('Space')
  await expect(announced).toContainText('Repas déposé : dimanche, déjeuner')

  await expect(shell.content.getByTestId('meal-cell-lunch-6')).toContainText(name)
  await expect(shell.content.getByTestId('meal-cell-dinner-5')).not.toContainText(name)

  const stored = await waitForIndexed<MealRow>(
    api,
    '/api/meals?itemsPerPage=200',
    (m) => String(m.summary).includes(name) && 'lunch' === m.slot,
    { what: 'The meal at Sunday lunch' },
  )
  expect(stored.date).toBe(dayOfThisWeek(6))
})
