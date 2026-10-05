import { test, expect, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

/**
 * The rules that file a statement, and the ones the statement itself implies
 * (MAG-102).
 *
 * **This file owns the owner's uncategorised operations, and with them the
 * consumption of the "Courses" envelope for the current month.** Both buttons
 * it presses — "Appliquer les règles" and "Créer N règle(s)" — run
 * `ApplyCategorizationRules` over *every* uncategorised transaction of the
 * user, so there is no way to scope them to one test's own data. Keeping them
 * in one serial file is what lets the figure be asserted before and after, and
 * why `finance-budget.spec.ts` asserts the **annual** envelope instead: that
 * one no active rule can reach.
 *
 * The chain is the point. A rule is written, the catch-up pass runs it over
 * the history, the transaction it matches gets a category — and the envelope
 * of that category moves by exactly the amount of that transaction. MAG-102
 * asks for the displayed calculations to be checked against the test data;
 * this is that calculation, end to end.
 */

interface StoredTransaction {
  id?: string
  label?: string
  category?: string | null
  categorySource?: string
}

/**
 * `active`, not `isActive`: Symfony drops the `is` prefix when it serialises
 * `isActive()`, and a client spelling it the getter's way reads its own
 * default in silence.
 */
interface StoredRule {
  id?: string
  labelPattern?: string
  category?: string
  active?: boolean
  priority?: number
}

/** 56,00 € — the uncategorised LECLERC the seeded rule is waiting for. */
const UNCATEGORISED_CENTS = 5600
const COURSES_BUDGET_CENTS = 20000
const COURSES_SPENT_CENTS = 12000

test('the seeded rules are listed with their scope and whether they are active', async ({
  page,
}) => {
  const categories = new FinanceCategoriesPage(page)
  await categories.open()
  await categories.openTab('Règles de catégorisation')

  const leclerc = categories.row('LECLERC')
  await expect(leclerc).toContainText('Contient')
  await expect(leclerc).toContainText('Courses')
  await expect(leclerc, 'the rule only ever files debits').toContainText('Dépense')

  // Seeded inactive on purpose, so the active/inactive split is visible — and
  // so the catch-up pass below cannot file anything under "Loisirs".
  await expect(categories.row('FNAC')).toContainText('Commence par')
})

/**
 * Serial, and with retries off — the same trade-off `chat.spec.ts` makes, and
 * scoped to the two tests that need it rather than to the file.
 *
 * Serial because the catch-up pass is global and these two read the envelope
 * either side of it. Retries off because a serial group replays *whole* and
 * nothing reseeds between the attempts: a replay would start on an operation
 * the first attempt already filed, and "120,00 € before" would fail on a run
 * where nothing was wrong. Unlike a label, the seeded operation cannot be made
 * unique per attempt — it is the fixture under test.
 *
 * The read-only test above stays outside the group on purpose: a serial group
 * *skips* what follows a failure, and a skipped test asserts nothing. Inside
 * it, one flake while reading the rule list would take the module's core
 * arithmetic with it, silently.
 */
test.describe('Filing the history', () => {
  test.describe.configure({ mode: 'serial', retries: 0 })

  test('running the rules files the uncategorised operation, and the envelope moves with it', async ({
    page,
    api,
  }) => {
    const budget = new FinanceBudgetPage(page)
    await budget.open()

    // Where the envelope stands before anything is filed: the three "Courses"
    // transactions of the month, 120,00 € out of the 200,00 € budgeted.
    await expect(budget.gauge('Courses')).toContainText(
      overBudget(COURSES_SPENT_CENTS, COURSES_BUDGET_CENTS),
    )
    await expect(budget.gauge('Courses')).toContainText(
      `Reste ${euros(COURSES_BUDGET_CENTS - COURSES_SPENT_CENTS)}`,
    )

    const categories = new FinanceCategoriesPage(page)
    await categories.open()
    await categories.openTab('Règles de catégorisation')
    await categories.applyRules.click()

    // The effect, not the toast: a toast says a request was made, the row says
    // what was stored. `LECLERC DRIVE RENNES` contains `LECLERC`, so the seeded
    // rule claims it and stamps the source as `rule` rather than `manual`.
    const filed = await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) =>
        candidate.label === 'LECLERC DRIVE RENNES' && candidate.categorySource === 'rule',
      { what: 'The uncategorised LECLERC, once the catch-up pass filed it' },
    )

    expect(filed.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)

    // DARTY matches no rule, so the pass leaves it alone — the catch-up is not a
    // "give everything a category" button.
    const transactions = await getCollection<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
    )
    expect(
      transactions.find((candidate) => candidate.label === 'DARTY')?.category ?? null,
    ).toBeNull()

    // And the whole point: 120,00 € + 56,00 € on screen, with 24,00 € left.
    const consumed = COURSES_SPENT_CENTS + UNCATEGORISED_CENTS
    await budget.open()
    await expect(budget.gauge('Courses')).toContainText(
      overBudget(consumed, COURSES_BUDGET_CENTS),
    )
    await expect(budget.gauge('Courses')).toContainText(
      `Reste ${euros(COURSES_BUDGET_CENTS - consumed)}`,
    )
    await expect(
      budget.gauge('Courses'),
      'the breakdown names where the money went, not just how much',
    ).toContainText(`${euros(consumed)} dépensés`)
  })

  /**
   * The rules the statement already implies.
   *
   * The seed holds three "LECLERC RENNES" debits whose category came from a rule
   * or from nowhere — never by hand — and the active rule's pattern is the
   * shorter "LECLERC", so the merchant is not covered yet. That is exactly the
   * case `SuggestCategorizationRules` is for: a shop that comes back, and no
   * rule spelling it the way the bank does.
   *
   * Accepting it must *remove* it from the tab, because the rule it created now
   * covers the merchant. A suggestion that survives its own acceptance is how
   * this screen would ask the same question for ever.
   */
  test('a suggested rule is accepted, and stops being suggested', async ({ page, api }) => {
    const categories = new FinanceCategoriesPage(page)
    await categories.open()
    await categories.openTab('Suggestions')

    const suggestion = categories.suggestion('LECLERC RENNES')
    // The count against its own cell: `toContainText('3')` on the row would be
    // just as happy with 13, 23 or 30 — and the occurrence count is what
    // decides whether a merchant is a habit worth a rule.
    await expect(suggestion.getByRole('cell', { name: '3', exact: true })).toBeVisible()
    await expect(suggestion, 'three debits of 45,00 €, 32,00 € and 380,00 €').toContainText(
      euros(-45700),
    )

    // Nothing is ticked while the heading is a question: the button has nothing
    // to create yet.
    await expect(
      categories.content.getByText('Choisissez une catégorie pour au moins une ligne.'),
    ).toBeVisible()

    await categories.chooseSuggestionCategory('LECLERC RENNES', 'Courses')
    await categories.createSuggestedRules.click()

    const created = await waitForIndexed<StoredRule>(
      api,
      '/api/categorization_rules',
      (rule) => rule.labelPattern === 'LECLERC RENNES',
      { what: 'The rule the accepted suggestion wrote' },
    )

    expect(created.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)
    expect(created.active).toBe(true)
    // Priority is the pattern's length, so the specific spelling is read before
    // the generic one: "LECLERC RENNES" (14) before "LECLERC" (10).
    expect(created.priority).toBe('LECLERC RENNES'.length)

    await categories.open()
    await categories.openTab('Règles de catégorisation')
    await expect(categories.row('LECLERC RENNES')).toContainText('Courses')

    await categories.openTab('Suggestions')
    await expect(
      categories.suggestion('LECLERC RENNES'),
      'the merchant is covered now, so it is not a question any more',
    ).toHaveCount(0)
  })
})
