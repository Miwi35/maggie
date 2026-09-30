import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { LoginPage } from '../pages/LoginPage.js'
import { adminUrl, ROUTES } from '../pages/routes.js'

/**
 * The web smoke journey: the admin loads signed in, the dashboard shows the
 * seeded world, and the menu goes where it says it goes.
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
  test('signs in with the test login and lands on the dashboard', async ({ page }) => {
    const dashboard = new DashboardPage(page)
    await dashboard.open()

    // The seeded event of the anchor day. Its presence proves rather a lot at
    // once: the JWT reached the data provider, the Hydra documentation was
    // parsed *with* that JWT (MAG-93 — 3f16b42, where it was not, and every
    // resource 401'd at schema introspection), and the search index the seed
    // rebuilt is being read.
    await expect(dashboard.entry('Déjeuner avec Alex')).toBeVisible()
  })

  test('the menu opens and navigates, folded or not', async ({ page }) => {
    const dashboard = new DashboardPage(page)
    await dashboard.open()

    await dashboard.openMenu()
    await expect(dashboard.menuItem('Tableau de bord')).toBeVisible()
    await expect(dashboard.menuItem('Calendrier')).toBeVisible()

    // A nested entry, the case the folded sidebar makes interesting: two
    // collapsed groups to open before the link exists.
    await dashboard.navigateTo('Événements', 'Données brutes', 'Agenda')
    await expect(page).toHaveURL(new RegExp(`#${ROUTES.events}$`))
    await expect(dashboard.content.getByText('Cours de piano').first()).toBeVisible()

    // The seeded lunch sits *on* the anchor. Read from the manifest rather
    // than from the wall clock: the two differ either side of midnight in Paris,
    // and for anyone who passed `--now` to the seed on purpose.
    const [year, month, day] = seedAnchorDate().split('-')
    await expect(
      dashboard.content.getByRole('row', { name: /Déjeuner avec Alex/ }),
    ).toContainText(`${day}/${month}/${year}`)

    await dashboard.navigateTo('Tableau de bord')
    await dashboard.expectReady()
  })
})

test('an anonymous visitor gets the login screen, not a blank page @responsive', async ({
  anonymousPage,
}) => {
  const login = new LoginPage(anonymousPage)
  await anonymousPage.goto(adminUrl(ROUTES.dashboard))

  await login.expectShown()
})
