import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES, accountTransactionsRoute } from './routes.js'

/**
 * Finance → Comptes, and the transactions of one account.
 *
 * Two screens in one page object because in the admin they are one journey:
 * the transaction resource is deliberately kept out of the menu, and clicking
 * an account row is the only way in (`AccountTransactionsView`). A test that
 * wants transactions always goes through an account first.
 */
export class FinanceAccountsPage extends AdminShell {
  readonly createAccount: Locator
  /** "Ajouter une transaction", only on the account-scoped list. */
  readonly addTransaction: Locator
  /** « Mois précédent », under the timeline: it loads the month of the latest earlier line (MAG-422). */
  readonly previousMonth: Locator
  /** The account selector at the top of the transactions view. */
  readonly accountSelect: Locator

  constructor(page: Page) {
    super(page)
    // Two labels for one button, and which one is on screen depends on the
    // data: a list with rows carries react-admin's own "Créer" in the toolbar,
    // and an empty one replaces the whole toolbar with `ListEmpty`, whose
    // invitation reads "Ajouter un compte". The journey presses the same
    // button either way.
    this.createAccount = this.content.getByRole('link', { name: /Créer|Ajouter un compte/ })
    this.addTransaction = this.content.getByRole('link', { name: 'Ajouter une transaction' })
    this.previousMonth = this.content.getByRole('button', { name: 'Mois précédent' })
    this.accountSelect = this.content.getByRole('combobox', { name: 'Compte' })
  }

  /**
   * The account list, whether or not it holds anything.
   *
   * A react-admin list with an `empty` renders *only* that — the toolbar
   * included — so an account list and an invitation to create one are two
   * different screens, and a journey that starts from nothing meets the
   * second.
   */
  async open(): Promise<void> {
    await this.goto(ROUTES.accounts)
    await expect(
      this.content
        .getByRole('table')
        .or(this.content.getByText("Aucun compte pour l'instant")),
    ).toBeVisible()
  }

  /**
   * The transactions of one account, by its bare ULID — its **current month**,
   * day by day (MAG-422).
   *
   * `back` pages back with « Mois précédent » that many times, for a journey
   * whose lines are booked in an earlier month. Each click adds the month of
   * the latest earlier line below the ones already shown; it stops early when
   * there is nothing earlier left.
   *
   * The wait allows for both outcomes: a month with nothing in it shows an
   * invitation (« Aucune transaction sur ce compte », or « … ce mois-ci » when
   * an earlier month holds lines) rather than a table.
   */
  async openTransactions(accountId: string, options: { back?: number } = {}): Promise<void> {
    await this.goto(accountTransactionsRoute(accountId))
    await expect(
      this.content
        .getByRole('table')
        .or(this.content.getByText(/^Aucune transaction (sur ce compte|ce mois-ci)$/)),
    ).toBeVisible()
    await this.pageBack(options.back ?? 0)
  }

  /** Loads `months` earlier months, one click each, waiting for each to be drawn. */
  async pageBack(months: number): Promise<void> {
    const shown = this.content.locator('[data-month]')
    for (let loaded = 0; loaded < months; loaded++) {
      if ((await this.previousMonth.count()) === 0) return
      const before = await shown.count()
      await this.previousMonth.click()
      await expect(shown).toHaveCount(before + 1)
    }
  }

  /** Switches to another of the owner's accounts from the selector; matches by part of its label. */
  async chooseAccount(name: string): Promise<void> {
    await this.accountSelect.click()
    await this.page.getByRole('option', { name }).click()
  }

  /**
   * One row of the timeline (or of the Incidents table) on screen, addressed by its text.
   *
   * `row` rather than a cell lookup: both tables put the amount in a `<span>`
   * of its own, so asserting "this line shows that amount" means filtering the
   * row and reading inside it.
   */
  row(text: string): Locator {
    return this.content.getByRole('row').filter({ hasText: text })
  }

  /**
   * Opens a line on its sheet (MAG-464): the row opens it, there is no edit
   * button any more. `index` picks among rows sharing a label, latest first.
   */
  async openTransaction(label: string, index = 0): Promise<void> {
    // The first cell holds the bulk-select checkbox, which does not open the sheet.
    await this.row(label).nth(index).getByText(label).first().click()
    await expect(this.content.getByRole('button', { name: 'Enregistrer' })).toBeVisible()
  }

  /** The sections of the open sheet, in the order they are drawn. */
  async sheetSections(): Promise<string[]> {
    return this.content
      .locator('[data-section]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-section') ?? ''))
  }

  /**
   * Reopens the account list and waits for the row.
   *
   * Call it *after* `waitForIndexed` confirmed the write: the list is served
   * from Elasticsearch and fetches when it mounts, so the screen can be stale
   * while the data is not. A reload would not do — a create lands on the
   * record's own edit screen, not back on the list.
   */
  async expectAccountEventually(name: string): Promise<void> {
    await this.open()
    await expect(this.row(name)).toBeVisible()
  }

  /** Fills the account form and saves. The form is the create and the edit one. */
  async createAccountNamed(
    name: string,
    options: { bank?: string; type?: string; balanceEuros?: number; cushion?: boolean } = {},
  ): Promise<void> {
    await this.createAccount.click()
    // A text field: until the form replaces the list, a label containing
    // « Nom » is also the list's « Trier par nom croissant » header.
    const nameField = this.content.getByRole('textbox', { name: 'Nom' })
    await expect(nameField).toBeVisible()

    await nameField.fill(name)

    if (options.bank !== undefined) {
      await this.content.getByLabel('Banque').fill(options.bank)
    }

    if (options.type !== undefined) {
      // A react-admin SelectInput is a MUI select: a combobox whose options
      // only exist once it is open.
      await this.content.getByLabel('Type de compte').click()
      await this.page.getByRole('option', { name: options.type }).click()
    }

    if (options.balanceEuros !== undefined) {
      await this.content.getByLabel('Solde actuel (€)').fill(String(options.balanceEuros))
    }

    if (options.cushion) {
      await this.content.getByLabel(/matelas de sécurité/).check()
    }

    await this.save()
  }

  /**
   * Fills the transaction form and saves.
   *
   * The account is pre-filled when the form was opened from an account's own
   * list — `CreateButton` passes it in the router state — so only the fields
   * the caller names are touched. The amount is typed without a sign: the
   * nature (a dépense unless the caller says otherwise) gives it its direction.
   */
  async createTransaction(options: {
    label: string
    amountEuros: number
    date: string
    nature?: 'Dépense' | 'Recette'
    category?: string
    status?: string
  }): Promise<void> {
    await this.addTransaction.click()
    await expect(this.content.getByLabel('Libellé')).toBeVisible()

    if (options.nature !== undefined) {
      await this.chooseNature(options.nature)
    }

    await this.content.getByLabel('Libellé').fill(options.label)
    await this.content.getByLabel('Montant (€)').fill(String(options.amountEuros))
    await this.content.getByLabel('Date').fill(options.date)

    if (options.category !== undefined) {
      const category = this.content.getByLabel('Catégorie')
      await category.fill(options.category)
      await this.page.getByRole('option', { name: options.category, exact: true }).click()
    }

    if (options.status !== undefined) {
      await this.content.getByLabel('Statut').click()
      await this.page.getByRole('option', { name: options.status }).click()
    }

    await this.save()
  }

  /** The Dépense / Recette choice at the top of the transaction form. */
  async chooseNature(nature: 'Dépense' | 'Recette'): Promise<void> {
    const button = this.content.getByRole('button', { name: nature, exact: true })
    await button.click()
    await expect(button).toHaveAttribute('aria-pressed', 'true')
  }

  /** The names offered by the category field, once it is open. */
  async offeredCategories(): Promise<string[]> {
    await this.content.getByLabel('Catégorie').click()
    const options = this.page.getByRole('option')
    await expect(options.first()).toBeVisible()
    return options.allInnerTexts()
  }

  /**
   * react-admin's own save button, and the wait for the form to have gone.
   *
   * Not "a list with a table is on screen": the save redirects to the list,
   * the list reads Elasticsearch, and the row that has just been written is
   * not findable for another moment — so the page it lands on is the empty
   * one, which renders no grid at all. The form having left is what says the
   * write went through; `waitForIndexed` is what says it landed.
   */
  async save(): Promise<void> {
    await this.content.getByRole('button', { name: 'Enregistrer' }).click()
    // The button is no signal: react-admin sends a create straight to the new
    // record's edit screen, which carries an "Enregistrer" of its own. The URL
    // leaving `/create` is what says the write went through — and
    // `waitForIndexed` is what says it landed.
    await this.page.waitForURL((url) => !url.hash.includes('/create'))
  }
}
