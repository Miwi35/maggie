import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { euros, overBudget } from '../helpers/money.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
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
   * The seed holds three "FROMAGERIE DES LICES" debits nothing has filed, and no
   * rule or dictionary entry names the shop. That is exactly the case
   * `SuggestCategorizationRules` is for: a shop that comes back, still to file.
   * The "LECLERC RENNES" debits are not one: a broader rule filed them already,
   * and a line already filed needs no rule of its own (MAG-45).
   *
   * Accepting it must *remove* it from the tab, because the rule it created now
   * covers the merchant. A suggestion that survives its own acceptance is how
   * this screen would ask the same question for ever.
   */
  test('a suggested rule is accepted, and stops being suggested', async ({ page, api }) => {
    const categories = new FinanceCategoriesPage(page)
    await categories.open()
    await categories.openTab('Suggestions')

    const suggestion = categories.suggestion('FROMAGERIE DES LICES')
    // The count against its own cell: `toContainText('3')` on the row would be
    // just as happy with 13, 23 or 30 — and the occurrence count is what
    // decides whether a merchant is a habit worth a rule.
    await expect(suggestion.getByRole('cell', { name: '3', exact: true })).toBeVisible()
    await expect(suggestion, 'three debits of 18,50 €, 22,00 € and 26,50 €').toContainText(
      euros(-6700),
    )

    // Nothing is ticked while the heading is a question: the button has nothing
    // to create yet.
    await expect(
      categories.content.getByText('Choisissez une catégorie pour au moins une ligne.'),
    ).toBeVisible()

    await categories.chooseSuggestionCategory('FROMAGERIE DES LICES', 'Courses')
    await categories.createSuggestedRules.click()

    const created = await waitForIndexed<StoredRule>(
      api,
      '/api/categorization_rules',
      (rule) => rule.labelPattern === 'FROMAGERIE DES LICES',
      { what: 'The rule the accepted suggestion wrote' },
    )

    expect(created.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)
    expect(created.active).toBe(true)
    // Priority is the pattern's length, so a specific spelling is read before a
    // generic one.
    expect(created.priority).toBe('FROMAGERIE DES LICES'.length)

    await categories.open()
    await categories.openTab('Règles de catégorisation')
    await expect(categories.row('FROMAGERIE DES LICES')).toContainText('Courses')

    await categories.openTab('Suggestions')
    await expect(
      categories.suggestion('FROMAGERIE DES LICES'),
      'the merchant is covered now, so it is not a question any more',
    ).toHaveCount(0)
  })

  /**
   * What the rule would catch is shown while it is written, and the box ticks
   * the history along (MAG-370).
   *
   * Last of the group on purpose: the three NETFLIX lines are created here,
   * after the passes above have run, so no earlier "Appliquer les règles" can
   * file them, and the suggestions tab the previous test reads never sees
   * them. Suffixed labels are not needed — retries are off for the group.
   *
   * The account list sits in the other window and must not navigate: the three
   * lines reach "Abonnements" through the Mercure updates of the rule's
   * application alone.
   */
  test('the preview counts the NETFLIX lines, and ticking the box files them live', async ({
    twoWindows,
    api,
  }) => {
    const checkingId = seedId('e2e_account_checking')
    const category = 'Abonnements MAG-370'
    const labels = ['NETFLIX.COM 4412', 'NETFLIX.COM 4413', 'NETFLIX.COM 4414']

    const created = await api.post('/api/categories', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: { name: category, obligation: 'optional' },
    })
    expect(created.status(), await created.text()).toBe(201)
    await waitForIndexed(
      api,
      '/api/categories',
      (candidate: { name?: string }) => candidate.name === category,
      { what: `The category ${category}` },
    )

    for (const [index, label] of labels.entries()) {
      const response = await api.post('/api/transactions', {
        headers: { 'Content-Type': 'application/ld+json' },
        data: {
          account: `/api/accounts/${checkingId}`,
          label,
          amountCents: -1349,
          currency: 'EUR',
          bookedAt: seedDate(-index),
          status: 'spent',
        },
      })
      expect(response.status(), await response.text()).toBe(201)
    }
    for (const label of labels) {
      await waitForIndexed<StoredTransaction>(
        api,
        '/api/transactions?itemsPerPage=100',
        (candidate) => candidate.label === label,
        { what: `The transaction ${label}` },
      )
    }

    const { actor, observer } = twoWindows
    const accounts = new FinanceAccountsPage(observer)
    await openSubscribed(observer, () => accounts.openTransactions(checkingId))
    for (const label of labels) {
      await expect(accounts.row(label), 'still to file').toBeVisible()
      await expect(accounts.row(label)).not.toContainText(category)
    }

    const rules = new FinanceCategoriesPage(actor)
    await rules.goto('/categorization_rules/create')
    const form = rules.content
    await form.getByLabel('…ce texte').fill('netflix')

    const found = form.getByText('3 transaction(s) trouvée(s)')
    await expect(found, 'the panel counts the lines while the rule is typed').toBeVisible()

    await form.getByLabel('Catégorie').fill(category)
    await actor.getByRole('option', { name: category, exact: true }).click()

    const box = form.getByLabel(/^Appliquer aux transactions existantes/)
    await expect(box, 'the box says how many lines would change').toHaveAccessibleName(
      'Appliquer aux transactions existantes (3)',
    )
    await expect(form.getByText('3 changeraient de catégorie', { exact: false })).toBeVisible()
    await box.check()

    await expectRealtimeSync(
      observer,
      async () => {
        await form.getByRole('button', { name: 'Enregistrer' }).click()
        await waitForIndexed<StoredRule>(
          api,
          '/api/categorization_rules',
          (rule) => rule.labelPattern === 'netflix',
          { what: 'The rule written from the form' },
        )
      },
      async () => {
        for (const label of labels) {
          await expect(accounts.row(label)).toContainText(category)
        }
      },
    )

    const stored = await getCollection<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
    )
    const filed = stored.filter((candidate) => labels.includes(candidate.label ?? ''))
    expect(filed).toHaveLength(3)
    for (const transaction of filed) {
      expect(transaction.categorySource).toBe('rule')
    }
  })
})
