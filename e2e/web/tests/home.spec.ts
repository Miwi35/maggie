import { test, expect, e2eNow, parisTime, parisDay } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'
import { euros } from '../helpers/money.js'

/**
 * The Accueil (MAG-560): the trail says where we are and a selector picks the
 * day every card shows. The selector lives in the URL, so a reload or a shared
 * link keeps it. The cards come with their own tickets and journeys: the Agenda
 * card's (MAG-562) is at the end of this file.
 */
test('the Accueil shows its trail and a day selector that switches to « Demain » @responsive', async ({ page }) => {
  const home = new HomePage(page)
  await home.open()

  await expect(home.day("Aujourd'hui")).toHaveAttribute('aria-pressed', 'true')
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'false')

  await home.day('Demain').click()

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/day=tomorrow/)

  // A reload keeps the day.
  await page.reload()
  await home.expectReady()
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

/**
 * The Accueil to the letter of the mockup (MAG-625): the page is titled with the day it shows,
 * and the left column (Agenda here) comes before the grid of the other modules.
 */
test('the Accueil is titled with the date of the day, « Demain » with tomorrow, and the Agenda card comes before the grid', async ({ homeUser }) => {
  const { page } = homeUser
  const home = new HomePage(page)
  await home.open()

  const title = page.getByRole('heading', { level: 1, name: /^Accueil · / })
  await expect(title).toHaveText(/^Accueil · \p{L}+ \d{1,2}(er)? \p{L}+$/u)
  const today = await title.textContent()

  await home.day('Demain').click()
  await expect(title).not.toHaveText(String(today))
  await expect(title).toHaveText(/^Accueil · \p{L}+ \d{1,2}(er)? \p{L}+$/u)

  await home.day("Aujourd'hui").click()
  await expect(title).toHaveText(String(today))

  const [agenda, grocery, pageBox] = await Promise.all([
    home.card('agenda').boundingBox(),
    home.groceryCard.boundingBox(),
    page.getByTestId('home-page').boundingBox(),
  ])
  expect(agenda && grocery && pageBox, 'the cards and the page are drawn').toBeTruthy()
  if (pageBox!.width >= 760) {
    // The page is wide enough (1440px with the menu and the chat open leaves ~820px): the left column
    // sits beside the grid, whose cards come two by two.
    expect(agenda!.x + agenda!.width, 'Agenda is left of the grid').toBeLessThanOrEqual(grocery!.x + 1)
    const columns = await home.groceryCard.evaluate((card) => {
      const grid = card.parentElement?.parentElement
      return grid ? getComputedStyle(grid).gridTemplateColumns.split(' ').length : 0
    })
    expect(columns, 'the grid of the other modules has two columns').toBe(2)
  } else {
    expect(agenda!.y, 'Agenda is above the grid on a narrow page').toBeLessThan(grocery!.y)
  }
})

test('a link to « Demain » opens the Accueil on it', async ({ page }) => {
  const home = new HomePage(page)
  await home.open('tomorrow')

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

/**
 * The Courses card (MAG-563, extends MAG-99): the open list by shop, five lines
 * of it and « + n » for the rest. Nothing on it is ticked from here — touching it
 * opens Courses.
 */
test('the Courses card shows five lines of a six-line list and « + 1 »', async ({ homeUser }) => {
  const home = new HomePage(homeUser.page)
  await home.open()

  const card = home.groceryCard
  await expect(card.getByTestId('home-grocery-store')).toHaveCount(1)
  await expect(card.getByTestId('home-grocery-store')).toContainText("Biocoop de l'Accueil")
  await expect(card.getByTestId('home-grocery-item')).toHaveCount(5)
  await expect(card.getByTestId('home-grocery-more')).toHaveText('+ 1')

  // The card does not follow the selector, and it is read-only.
  await home.day('Demain').click()
  await expect(card.getByTestId('home-grocery-item')).toHaveCount(5)
  await expect(card.getByRole('checkbox')).toHaveCount(0)
})

/**
 * The Finance card (MAG-565, extends MAG-102): the week and the month as rings with their
 * spent. The Accueil's account has a 1 000 € envelope and 10 € spent on the 1st. Touching the card opens Finance › Dashboard.
 * Its only project, « Vélo », saves nothing (no income, no savings): the planning lands it beyond the horizon, so the
 * card and the Dashboard both name it « au-delà de 3 ans », never « 36 mois » (MAG-667).
 */
test('the Finance card says what was spent, without a verdict, and opens the Dashboard', async ({ homeUser }) => {
  const home = new HomePage(homeUser.page)
  await home.open()

  const card = home.financeCard
  await expect(card.getByTestId('home-finance-ring')).toHaveCount(2)
  const month = card.locator('[data-testid="home-finance-ring"][data-period="month"]')
  await expect(month.getByTestId('home-finance-spent')).toContainText(euros(1000))
  await expect(card).not.toContainText(/rythme|restants/)
  const project = card.getByTestId('home-finance-next-project')
  await expect(project).toContainText('Vélo')
  await expect(project).toContainText('au-delà de 3 ans')
  await expect(project).not.toContainText('36 mois')

  // It does not follow the selector.
  await home.day('Demain').click()
  await expect(card.getByTestId('home-finance-ring')).toHaveCount(2)

  await card.getByRole('link', { name: 'Finance' }).first().click()
  await expect(homeUser.page).toHaveURL(/\/finance\/dashboard/)
  const dashboardProject = homeUser.page.getByTestId('next-project-card')
  await expect(dashboardProject).toContainText('Vélo')
  await expect(dashboardProject.getByTestId('next-project-date')).toContainText('au-delà de 3 ans')
})

/**
 * The Agenda card (MAG-562). The seed has « Point du matin » (08:00) done and
 * « Appel avec le plombier » (18:30) to come around the lunch (12:00): the line sits
 * between them while the stack is pinned at noon, which CI does. Outside 08:30–18:30
 * Paris (an unpinned run) the line is elsewhere, so the journey has nothing to say.
 */
test('the Agenda card puts « maintenant » between the done and the coming event, and the fiche opens on touch @responsive', async ({ page }) => {
  const day = parisDay()
  const now = e2eNow().getTime()
  test.skip(
    now < Date.parse(parisTime(day, '08:30:00')) || now >= Date.parse(parisTime(day, '18:30:00')),
    'the seeded events frame « maintenant » only between 08:30 and 18:30',
  )

  const home = new HomePage(page)
  await home.open()

  const done = home.agendaRow('Point du matin')
  const upcoming = home.agendaRow('Appel avec le plombier')
  await expect(done).toBeVisible()
  await expect(home.nowLine).toBeVisible()
  await expect(done).toContainText('fait')

  const [doneBox, lineBox, upcomingBox] = await Promise.all([
    done.boundingBox(),
    home.nowLine.boundingBox(),
    upcoming.boundingBox(),
  ])
  expect(doneBox && lineBox && upcomingBox).toBeTruthy()
  expect(doneBox!.y).toBeLessThan(lineBox!.y)
  expect(lineBox!.y).toBeLessThan(upcomingBox!.y)

  await upcoming.click()

  const fiche = home.eventPanel('Appel avec le plombier')
  await expect(fiche).toBeVisible()
  await expect(fiche).toContainText('18:30')
  await expect(fiche).toContainText('Au téléphone')
  await expect(fiche).toContainText('Famille')
  await expect(fiche).toContainText('Devis de la salle de bain')
  await expect(fiche.getByRole('link', { name: "Ouvrir dans l'agenda" })).toBeVisible()
})

/**
 * The empty cards (MAG-594, extends MAG-101): a card with nothing to say for the chosen day is not
 * drawn, no title, no frame. An account of its own, with the `empty` profile (no meal, no
 * shopping line, no task, no event, no account): the Accueil says one calm sentence; a task
 * due today brings the Tâches card back — and only that one — until « Demain », where it is empty.
 */
test.describe('an empty card is not drawn', () => {
  test.use({ journeyAccount: 'empty' })

  const MODULES = ['tasks', 'agenda', 'grocery', 'cuisine', 'finance'] as const

  test('a user with nothing sees no card but one calm sentence, and a task brings only its card @responsive', async ({ page, api }) => {
    const home = new HomePage(page)

    // Given a user with no meal, no shopping line and nothing planned, when I open the Accueil…
    await home.open()

    // …no card is drawn, and the page says one sentence for the day.
    await expect(home.calmSentence).toHaveText("Rien de prévu aujourd'hui.")
    for (const module of MODULES) await expect(home.card(module)).toHaveCount(0)
    await home.day('Demain').click()
    await expect(home.calmSentence).toHaveText('Rien de prévu demain.')

    // When a task falls due today…
    const response = await api.post('/api/tasks', {
      headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
      data: { title: 'Appeler le plombier', dueDate: parisDay() },
    })
    expect(response.ok(), `POST /api/tasks answered ${response.status()}`).toBe(true)
    await home.open()

    // …the Tâches card comes, the others stay away, and the sentence is gone.
    await expect(home.card('tasks')).toBeVisible()
    await expect(home.tick('Appeler le plombier')).toBeVisible()
    await expect(home.calmSentence).toHaveCount(0)
    for (const module of MODULES.filter((m) => m !== 'tasks')) await expect(home.card(module)).toHaveCount(0)

    // Tomorrow it is empty, so it goes again.
    await home.day('Demain').click()
    await expect(home.calmSentence).toHaveText('Rien de prévu demain.')
    await expect(home.card('tasks')).toHaveCount(0)
  })
})
