import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'
import { LoginPage } from '../pages/LoginPage.js'
import { adminUrl, ROUTES } from '../pages/routes.js'

/**
 * The web smoke journey: the admin loads signed in on the Accueil, the seeded
 * world is behind the menu, and the menu goes where it says it goes.
 *
 * Tagged `@responsive`, so it runs on all three projects. That is the point of
 * having three: the sidebar is folded away below 900px, and everything below
 * has to hold at 1440, 834 and 393.
 *
 * It is the browser counterpart of e2e/smoke/smoke.sh, which checks the same
 * stack through HTTP. Both stay: when a journey fails, the shell script says
 * whether the stack or the browser is at fault.
 */

test.describe('Admin smoke @responsive', () => {
  test('signs in with the test login and lands on the Accueil', async ({ page }) => {
    const home = new HomePage(page)
    await home.open()

    // The seeded events are read on the Agenda below (the cards of the Accueil
    // bring their own journeys): the JWT reached the data provider, the Hydra
    // documentation was parsed *with* it (MAG-93 — 3f16b42, where it was not,
    // and every resource 401'd at schema introspection), and the search index
    // the seed rebuilt is being read.
    await expect(home.day("Aujourd'hui")).toHaveAttribute('aria-pressed', 'true')
  })

  test('the menu opens and navigates, folded or not', async ({ page }) => {
    const dashboard = new HomePage(page)
    await dashboard.open()

    await dashboard.openMenu()
    await expect(dashboard.menuItem('Accueil')).toBeVisible()
    await expect(dashboard.menuItem('Agenda')).toBeVisible()

    // A nested entry, the case the folded sidebar makes interesting: the module
    // has to be opened before the link exists.
    await dashboard.navigateTo('Événements', 'Réglages')
    await expect(page).toHaveURL(new RegExp(`#${ROUTES.events}$`))
    await expect(dashboard.content.getByText('Cours de piano').first()).toBeVisible()

    // The seeded lunch sits *on* the anchor. Read from the manifest rather
    // than from the wall clock: the two differ either side of midnight in Paris,
    // and for anyone who passed `--now` to the seed on purpose.
    const [year, month, day] = seedAnchorDate().split('-')
    await expect(
      dashboard.content.getByRole('row', { name: /Déjeuner avec Alex/ }),
    ).toContainText(`${day}/${month}/${year}`)

    await dashboard.navigateTo('Accueil')
    await dashboard.expectReady()
  })
})

test('an anonymous visitor gets the login screen, not a blank page @responsive', async ({
  anonymousPage,
}) => {
  const login = new LoginPage(anonymousPage)
  await anonymousPage.goto(adminUrl(ROUTES.home))

  // MAG-140: this used to take 7 to 25 seconds, on a blank page.
  await login.expectShown(3_000)
})
