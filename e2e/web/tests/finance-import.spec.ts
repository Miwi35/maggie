import { test, expect, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, signedEuros } from '../helpers/money.js'
import { FinanceImportPage } from '../pages/FinanceImportPage.js'

/**
 * Import de relevé CSV (MAG-44, MAG-102).
 *
 * The parser and the dedup pass were already there, reachable only from a
 * shell; this journey is the screen that opens them — and the two steps are
 * what it has to prove. A rehearsal that wrote anything, or a confirmation
 * that imported a movement twice, would both be the kind of thing nobody
 * notices until their statement is wrong.
 *
 * **Dated 2019 on purpose.** Everything else in the finance journeys reads a
 * figure of the current or the previous month — an envelope's consumption, the
 * month's top headings, the monthly review — and `e2e:seed` runs once for the
 * whole suite. A movement filed six years back is invisible to all of them,
 * which is what lets this file write to the owner's own account instead of
 * borrowing the neighbour's: the import screen only lists accounts, so a
 * neighbour's account would never show the rule being applied.
 */

interface StoredTransaction {
  id?: string
  label?: string
  bookedAt?: string
  amountCents?: number
  category?: string | null
  categorySource?: string
}

/** Claimed by the seeded "LECLERC" rule (contains, debits) → "Courses". */
const CLAIMED = 'LECLERC IMPORT CSV 2019'
const CLAIMED_CENTS = -2345

/** No rule spells this, so it arrives without a heading. */
const UNCLAIMED = 'GARAGE IMPORT CSV 2019'
const UNCLAIMED_CENTS = -8900

/**
 * A two-line export in a format no fixture file declares: `;` separated,
 * `d/m/Y`, comma decimals — a French bank's default, and the format detection
 * is part of what this journey covers.
 */
const STATEMENT = [
  'Date;Libellé;Montant',
  `11/03/2019;${CLAIMED};-23,45`,
  `12/03/2019;${UNCLAIMED};-89,00`,
  '',
].join('\n')

const stored = (label: string) => (candidate: StoredTransaction) => candidate.label === label

test.describe('Importing a statement', () => {
  // Serial: the second rehearsal only means something after the first import
  // wrote, and both read the same two movements.
  test.describe.configure({ mode: 'serial', retries: 0 })

  test('the rehearsal says what would happen, and writes nothing', async ({ page, api }) => {
    const importPage = new FinanceImportPage(page)
    await importPage.open()

    await expect(
      importPage.confirm,
      'nothing is confirmable before a rehearsal has run',
    ).toHaveCount(0)

    await importPage.chooseAccount('Compte courant')
    await importPage.dropStatement(STATEMENT)
    await importPage.simulate.click()

    await expect(importPage.content.getByText('Rapport de simulation')).toBeVisible()

    // The figures the reader confirms on.
    await importPage.expectFigure('Lignes lues', '2')
    await importPage.expectFigure('À importer', '2')
    await importPage.expectFigure('Déjà présentes', '0')
    await importPage.expectFigure('Catégorisées par règle', '1')
    await importPage.expectFigure('Période', '11/03/2019 → 12/03/2019')
    await importPage.expectFigure(
      'Solde des mouvements',
      euros(CLAIMED_CENTS + UNCLAIMED_CENTS),
    )

    // And line by line: the rule's heading is the one thing a reader cannot
    // work out for themselves.
    const claimed = importPage.row(CLAIMED)
    await expect(claimed).toContainText('11/03/2019')
    await expect(claimed).toContainText(signedEuros(CLAIMED_CENTS))
    await expect(claimed, 'the rule files it before it is even written').toContainText('Courses')
    await expect(claimed).toContainText('Nouvelle')

    await expect(importPage.row(UNCLAIMED)).toContainText('Non catégorisée')

    // The whole point of the step: the database is untouched.
    const transactions = await getCollection<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=200',
    )
    expect(transactions.find(stored(CLAIMED))).toBeUndefined()
    expect(transactions.find(stored(UNCLAIMED))).toBeUndefined()
  })

  test('confirming files the movements, with the category the rule gave them', async ({
    page,
    api,
  }) => {
    const importPage = new FinanceImportPage(page)
    await importPage.open()
    await importPage.chooseAccount('Compte courant')
    await importPage.dropStatement(STATEMENT)
    await importPage.simulate.click()

    await expect(importPage.confirm).toHaveText('Importer 2 opération(s)')
    await importPage.confirm.click()

    await expect(importPage.content.getByText('Import effectué')).toBeVisible()
    await expect(
      importPage.confirm,
      'what is written cannot be written again from the same report',
    ).toHaveCount(0)

    // The effect, not the wording on screen: the row, indexed, with the
    // heading the rule stamped on it.
    const filed = await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=200',
      stored(CLAIMED),
      { what: 'The imported movement the LECLERC rule claims' },
    )

    expect(filed.amountCents).toBe(CLAIMED_CENTS)
    expect(filed.bookedAt).toContain('2019-03-11')
    expect(filed.categorySource).toBe('rule')
    expect(filed.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)

    const other = await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=200',
      stored(UNCLAIMED),
      { what: 'The imported movement no rule claims' },
    )

    expect(other.amountCents).toBe(UNCLAIMED_CENTS)
    expect(other.category ?? null, 'no rule spells this merchant').toBeNull()
  })

  test('re-importing the same file brings nothing, and says so', async ({ page }) => {
    const importPage = new FinanceImportPage(page)
    await importPage.open()
    await importPage.chooseAccount('Compte courant')
    await importPage.dropStatement(STATEMENT)
    await importPage.simulate.click()

    await importPage.expectFigure('À importer', '0')
    await importPage.expectFigure('Déjà présentes', '2')
    await expect(importPage.row(CLAIMED)).toContainText('Déjà présente')
    await expect(importPage.row(UNCLAIMED)).toContainText('Déjà présente')

    // Nothing to confirm, and the screen says why rather than offering a
    // button that would do nothing.
    await expect(importPage.confirm).toHaveCount(0)
    await expect(importPage.content.getByText(/il n'y a rien à importer/)).toBeVisible()
  })
})
