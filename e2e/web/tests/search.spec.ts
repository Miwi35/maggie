import { test, expect, seedDate } from '../fixtures/index.js'
import type { Page } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { GlobalSearch } from '../pages/GlobalSearch.js'

/**
 * Global search: finding something and landing on it (MAG-103, MAG-144).
 *
 * `/api/search` returns the bare Elasticsearch identifier, while react-admin's
 * Hydra provider knows a record by its IRI — so every result used to send `getOne`
 * to `/<ulid>`, which answered 404 and put "Événement introuvable" on screen.
 * Each journey therefore clicks a real result and asserts on the page it lands on,
 * and on the requests it took to get there.
 */

/** The paths the admin itself talks to; anything else is a record id taken for a URL. */
const KNOWN_PREFIXES = ['/api/', '/admin/', '/.well-known/mercure', '/agent']

/** Every request from here on that left those prefixes — a bare `/<ulid>` lookup is one. */
function strayRequests(page: Page): string[] {
  const stray: string[] = []

  page.on('request', (request) => {
    const { pathname } = new URL(request.url())

    if (!KNOWN_PREFIXES.some((prefix) => pathname.startsWith(prefix))) {
      stray.push(`${request.method()} ${pathname}`)
    }
  })

  return stray
}

test.describe('Global search', () => {
  test('an event found by its name opens in the agenda, on its own date', async ({ page, api }) => {
    // The attempt is in the name: a retry starts on the row the first attempt created.
    const summary = `Dentiste Moreau, essai ${test.info().retry}`
    // Far enough from the month the agenda opens on that landing on it proves the grid moved.
    const day = seedDate(45)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.createEvent({ summary, start: `${day}T10:00`, end: `${day}T11:00` })
    await waitForIndexed(api, '/api/events?itemsPerPage=100', (event) => (event as { summary?: string }).summary === summary, {
      what: `The event "${summary}"`,
    })

    // Leave the agenda: the search must be what takes the owner back to it.
    await calendar.goto('/')
    const stray = strayRequests(page)
    const search = new GlobalSearch(page)
    await search.expectLoaded()
    await search.open(summary, 'Événements', summary)

    await expect(calendar.detailTitle(summary), 'the result did not open the event').toBeVisible()
    await expect(calendar.chip(summary), 'the grid did not move to the event').toBeVisible()
    await expect(page.getByText('Événement introuvable')).toBeHidden()
    expect(stray).toEqual([])
  })

  test('a meal found by its name opens as a meal, not as a missing event', async ({ page }) => {
    const search = new GlobalSearch(page)
    await search.goto('/')
    await search.expectLoaded()
    const stray = strayRequests(page)

    await search.open('Pâtes à la tomate', 'Repas', 'Pâtes à la tomate')

    const calendar = new CalendarPage(page)
    await expect(calendar.detailTitle('Dîner: Pâtes à la tomate'), 'the result did not open the meal').toBeVisible()
    await expect(page.getByText(/introuvable/)).toBeHidden()
    expect(stray).toEqual([])
  })

  test('a recipe found by its name opens its page', async ({ page }) => {
    const search = new GlobalSearch(page)
    await search.goto('/')
    await search.expectLoaded()
    const stray = strayRequests(page)

    await search.open('Gratin de courgettes', 'Recettes', 'Gratin de courgettes')

    await expect(page).toHaveURL(/#\/recipes\/.+\/show$/)
    await expect(search.content.getByText('Gratin de courgettes').first()).toBeVisible()
    expect(stray).toEqual([])
  })
})
