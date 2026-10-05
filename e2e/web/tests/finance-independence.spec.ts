import { test, expect } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros } from '../helpers/money.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'

/**
 * The independence counter (MAG-102, MAG-46).
 *
 * Both sides of the ratio are measured by `GetIndependenceCounter` over the
 * three complete months before this one, so what is checked here is arithmetic
 * over the seeded data:
 *
 *   train de vie   380,00 € + 120,00 € consumed last month, / 3 = 166,66 €
 *   rentes         300,00 € of rent received last month, / 3 = 100,00 €
 *   coverage       100,00 / 166,66 = 60 %, 66,66 € a month still missing
 *
 * The train de vie is the same figure `finance-overview.spec.ts` asserts on
 * the saving capacity card — one measure, two readers — and the window ends at
 * the first of this month, so nothing another journey writes into the current
 * month can move it.
 *
 * Read-only for the owner. Declaring a rente writes, and writes as the
 * **neighbour**, whose finance module nothing else reads.
 */

const LIFESTYLE_CENTS = 16666
const RENTE_CENTS = 10000

/**
 * Only the fields the journey reads, spelled the way the API spells them.
 *
 * `passiveIncome`, and the property is named that way on purpose: Symfony
 * serialises `isFoo()` as `foo`, so a field declared `isPassiveIncome` would
 * be `passiveIncome` over REST and `isPassiveIncome` on Mercure — the
 * disagreement `isCushion` already pays for.
 */
interface StoredCategory {
  name?: string
  obligation?: string
  passiveIncome?: boolean
}

test('the counter says what share of the train de vie the rentes cover', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const counter = overview.card('Indépendance financière')

  await expect(counter).toContainText('60 %')
  // Both terms, so a wrong percentage can be told from a wrong term.
  await expect(counter).toContainText(
    `${euros(RENTE_CENTS)} de rentes sur ${euros(LIFESTYLE_CENTS)} de train de vie`,
  )
  await expect(counter).toContainText('mesurés sur 3 mois')
})

test('what is still missing, and the next step, are named', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const counter = overview.card('Indépendance financière')

  await expect(counter).toContainText(`Il manque ${euros(LIFESTYLE_CENTS - RENTE_CENTS)} par mois`)
  // 75 % of 166,66 € is 125,00 €, so 25,00 € above today's rentes.
  await expect(counter).toContainText('prochain palier 75 %')
  await expect(counter).toContainText(`${euros(2500)} près`)

  // The target date N is Premium: a date shown here would be a promise the
  // Free counter does not make.
  await expect(counter).not.toContainText(/Date|date d'indépendance/)
})

/**
 * A rente is a declared category, and the breakdown is what says which one.
 *
 * The salary is income too — 2 350,00 € of it, every month — and it is
 * deliberately not a rente: a counter that read it would say independence was
 * reached fourteen times over.
 */
test('the breakdown names the rente categories, and only those', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const rent = overview.rente('Loyers perçus')
  await expect(rent).toContainText(`${euros(RENTE_CENTS)} / mois`)
  await expect(rent).toContainText('100 %')

  await expect(overview.rente('Salaire'), 'a salary is worked for').toHaveCount(0)
})

/**
 * Declaring a rente, end to end, on an identity whose finance module is empty.
 *
 * The neighbour has no movement at all, so the counter has no train de vie to
 * divide by: it must say so rather than show 0 %, which would read as a
 * verdict on somebody who has simply not started.
 */
test('a rente is declared from the category form, and the counter picks it up', async ({
  otherUser,
}) => {
  const categories = new FinanceCategoriesPage(otherUser.page)
  // Suffixed with the attempt, unconditionally: CI retries once and nothing
  // reseeds in between.
  const name = `Dividendes MAG-46, essai ${test.info().retry}`

  await categories.open()
  await categories.createCategoryNamed(name, { obligation: 'Recette', rente: true })

  // The list reads Elasticsearch, so the row that has just been written is not
  // findable for another moment — and the flag travelling to the API is the
  // half of this that a reloaded screen cannot prove on its own.
  const stored = await waitForIndexed<StoredCategory>(
    otherUser.api,
    '/api/categories',
    (category) => category.name === name,
    { what: `The rente category "${name}"` },
  )
  expect(stored.passiveIncome, 'the form sent the flag the API spells').toBe(true)

  await categories.open()
  await expect(
    categories.row(name),
    'the list says which categories feed the counter',
  ).toContainText('Recette · rente')

  const overview = new FinanceOverviewPage(otherUser.page)
  await overview.open()

  const counter = overview.card('Indépendance financière')
  await expect(counter).toContainText('Pas encore de train de vie mesuré')
  await expect(counter, 'no denominator, no percentage').not.toContainText('0 %')
})
