import { test, expect } from '../fixtures/index.js'
import type { Page } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { dayOfThisWeek } from '../helpers/week.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The admin, drawn for the window it is in (MAG-38).
 *
 * Every test here is `@responsive`, so it runs on all three projects — 1440,
 * 834 and 393 — and asserts the same claim at each. What differs between the
 * widths is not *whether* a thing holds but what it measures, so the widths
 * are read once into {@link shapeOf} and the test bodies stay straight lines:
 * no branch, and nothing skipped.
 *
 * 834 is the width that justified the ticket. React-admin folds its sidebar at
 * `sm` (600px), so a tablet in portrait used to get a 240px menu *and* a 380px
 * chat panel open beside the page, leaving 214px of content.
 *
 * What is **not** here: pixel comparisons. A screenshot of a month grid at
 * three widths is a test of FullCalendar's fonts. What an owner notices is the
 * page spilling sideways out of the window, the conversation covering what he
 * asked about, and a control too small to hit — so those are what is asserted.
 */

/** Where the app changes shape. Mirrors `admin/src/breakpoints.ts`. */
const NARROW_BELOW = 900

/** The chat panel's width as a column, from `ChatWidget`'s `SIDEBAR_WIDTH`. */
const CHAT_COLUMN = 380

/** WCAG 2.5.5, and what `admin/src/theme.ts` guarantees below `md`. */
const TOUCH_TARGET = 44

interface Shape {
  /** Below `md`: one column, menu and chat as drawers over the page. */
  narrow: boolean
  width: number
  /** The chat once open — the whole window as a sheet, or a column beside the page. */
  chatWidth: number
  /** The smallest an app-bar control may be here. Only a finger is promised 44px. */
  touchTarget: number
  /** What opens the event dialog: the sidebar's split button, or the floating one. */
  createEvent: string
}

function shapeOf(page: Page): Shape {
  const width = page.viewportSize()?.width ?? NARROW_BELOW
  const narrow = width < NARROW_BELOW

  return {
    narrow,
    width,
    chatWidth: narrow ? width : CHAT_COLUMN,
    touchTarget: narrow ? TOUCH_TARGET : 0,
    createEvent: narrow ? 'Créer un événement' : 'Créer',
  }
}

/**
 * How far the document spills past the device's own width, in pixels.
 *
 * The one measurement that catches a layout a finger cannot use. Measured
 * against `viewportSize()` and not against `innerWidth`, because on a phone
 * the two part company exactly when something is wrong: Chrome answers
 * overflowing content by widening the layout viewport and drawing the whole
 * app smaller to fit — 393px of screen reporting `innerWidth: 564` and, by
 * its own arithmetic, no overflow at all. Comparing to the device width sees
 * the shrink for what it is.
 *
 * One pixel of slack for sub-pixel rounding at the device scale factors
 * Playwright emulates.
 */
async function sidewaysOverflow(page: Page): Promise<number> {
  const device = page.viewportSize()?.width ?? 0

  return page.evaluate(
    (width: number) => document.documentElement.scrollWidth - width,
    device,
  )
}

const PAGES = [
  ['le tableau de bord', ROUTES.dashboard],
  ['le calendrier', ROUTES.calendar],
  ['les courses', ROUTES.grocery],
  ['les repas de la semaine', ROUTES.meals],
  ["la vue d'ensemble finance", ROUTES.financeOverview],
  ['les comptes', ROUTES.accounts],
] as const

test.describe('Responsive @responsive', () => {
  test('every page fits the window it is in', async ({ page }) => {
    const shell = new AdminShell(page)

    for (const [label, route] of PAGES) {
      await shell.goto(route)
      await shell.expectLoaded()
      // Settled: a datagrid that has not fetched yet is an empty table, and
      // an empty table never overflows.
      await expect(shell.content.getByText(/\S/).first()).toBeVisible()

      expect(await sidewaysOverflow(page), `${label} spills out of the window`).toBeLessThanOrEqual(1)
    }
  })

  test('the conversation is where the window has room for it', async ({ page }) => {
    const shape = shapeOf(page)
    const dashboard = new DashboardPage(page)
    const chat = new ChatPanel(page)

    await dashboard.open()

    // Open beside the page on a desk; below `md` it waits behind the app
    // bar's button rather than taking a third of the window from the page it
    // is about.
    await expect(chat.input).toBeVisible({ visible: !shape.narrow })

    await chat.open()
    const panel = await chat.panel.boundingBox()
    expect(Math.round(panel?.width ?? 0)).toBe(shape.chatWidth)

    // And the page is whole again once it is put away.
    await chat.close()
    await expect(dashboard.heading).toBeVisible()
  })

  test('the menu folds away, and comes back over the page', async ({ page }) => {
    const shape = shapeOf(page)
    const dashboard = new DashboardPage(page)

    await dashboard.open()

    await expect(dashboard.menuItem('Calendrier')).toBeVisible({ visible: !shape.narrow })

    // An overlay below `md`, already a column above it: either way, opening
    // the menu takes nothing from the page.
    const before = await dashboard.content.boundingBox()
    await dashboard.openMenu()
    expect((await dashboard.content.boundingBox())?.width).toBe(before?.width)

    // Picking a destination puts the drawer away — and leaves the desk's
    // column where it was.
    await dashboard.navigateTo('Calendrier')
    await expect(page).toHaveURL(new RegExp(`#${ROUTES.calendar}$`))
    await expect(dashboard.menuGroup('Courses')).toBeVisible({ visible: !shape.narrow })
  })

  test('the app bar gives a finger something to hit', async ({ page }) => {
    const shape = shapeOf(page)
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.dashboard)
    await shell.expectLoaded()

    // The search field becomes a magnifier below `md`: the bar carries a
    // burger, a title, the dictation, the bell, the chat and the avatar, and
    // 400px of field on top of that pushed half of them off a 393px screen.
    await expect(shell.appBar.getByRole('button', { name: 'Rechercher' })).toBeVisible({
      visible: shape.narrow,
    })

    // And the Mind shortcut goes the other way: one icon too many for a
    // phone's bar, and the sheet carries a "Mind" tab of its own.
    await expect(shell.appBar.getByRole('button', { name: "Maggie's Mind" })).toBeVisible({
      visible: !shape.narrow,
    })

    const chatButton = await shell.appBar.getByRole('button', { name: 'Chat avec Maggie' }).boundingBox()
    expect(chatButton?.height ?? 0, 'the chat button is too short to tap').toBeGreaterThanOrEqual(shape.touchTarget)
    expect(chatButton?.width ?? 0, 'the chat button is too narrow to tap').toBeGreaterThanOrEqual(shape.touchTarget)
  })

  test('the week of meals keeps its fourteen slots', async ({ page }) => {
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.meals)
    await shell.expectLoaded()

    // Seven columns across 393px give each day 42px, so below `md` the week
    // reads downwards instead — same cells, one card per day.
    for (const slot of ['lunch', 'dinner']) {
      for (let day = 0; day < 7; day++) {
        await expect(shell.content.getByTestId(`meal-cell-${slot}-${day}`)).toBeVisible()
      }
    }
  })

  test('a week with a long unbroken meal name still fits the window', async ({ page, api }) => {
    // MAG-366: the grid's `1fr` columns grew to the widest word they held, so a
    // busy week pushed the page sideways — the CI run that merged MAG-366 failed
    // here on what other journeys had planted. Plant the word instead of hoping,
    // on Wednesday dinner, a cell no other journey uses (meals-move owns Saturday).
    const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
    const name = `Velouté-MAG-366-${'x'.repeat(40)}`
    const recipe = await api.post('/api/recipes', { headers: jsonLd, data: { name, servings: 2 } })
    expect(recipe.status()).toBe(201)
    const planted = (await recipe.json()) as { id: string; '@id': string }
    const created = await api.post('/api/meals', {
      headers: jsonLd,
      data: { summary: 'Dîner', date: dayOfThisWeek(2), slot: 'dinner', recipes: [planted['@id']] },
    })
    expect(created.status()).toBe(201)
    const meal = (await created.json()) as { id: string }

    try {
      await waitForIndexed<{ summary: string }>(api, '/api/meals', (m) => String(m.summary).includes(name), {
        what: 'The meal with the long name',
      })

      const shell = new AdminShell(page)
      await shell.goto(ROUTES.meals)
      await shell.expectLoaded()
      await expect(shell.content.getByTestId('meal-cell-dinner-2')).toContainText(name)

      expect(await sidewaysOverflow(page), 'a long meal name spills the week out of the window').toBeLessThanOrEqual(1)
    } finally {
      await api.delete(`/api/meals/${meal.id}`)
      await api.delete(`/api/recipes/${planted.id}`)
    }
  })

  test('an event can be created from the calendar at any width', async ({ page }) => {
    const shape = shapeOf(page)
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.calendar)
    await shell.expectLoaded()

    // The left column — mini calendar, agenda list and the "Créer" split
    // button — is hidden below `md`, so a floating button is the way in there.
    const create = page.getByRole('button', { name: shape.createEvent, exact: true })

    await expect(create).toBeVisible()
    await create.click()
    await expect(page.getByRole('dialog').getByText('Nouvel événement')).toBeVisible()
  })
})
