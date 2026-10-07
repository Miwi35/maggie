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

  constructor(page: Page) {
    super(page)
    // Two labels for one button, and which one is on screen depends on the
    // data: a list with rows carries react-admin's own "Créer" in the toolbar,
    // and an empty one replaces the whole toolbar with `ListEmpty`, whose
    // invitation reads "Ajouter un compte". The journey presses the same
    // button either way.
    this.createAccount = this.content.getByRole('link', { name: /Créer|Ajouter un compte/ })
    this.addTransaction = this.content.getByRole('link', { name: 'Ajouter une transaction' })
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
   * The transactions of one account, by its bare ULID.
   *
   * The wait allows for both outcomes. The collection is served from
   * Elasticsearch, and a react-admin list with an `empty` renders *only* that
   * — toolbar included — so an account whose first transaction is not indexed
   * yet legitimately shows the invitation rather than a grid.
   */
  async openTransactions(accountId: string): Promise<void> {
    await this.goto(accountTransactionsRoute(accountId))
    await expect(
      this.content
        .getByRole('table')
        .or(this.content.getByText('Aucune transaction sur ce compte')),
    ).toBeVisible()
  }

  /**
   * One row of whichever datagrid is on screen, addressed by its text.
   *
   * `row` rather than a cell lookup: both grids put the amount in a `<span>`
   * of its own, so asserting "this line shows that amount" means filtering the
   * row and reading inside it.
   */
  row(text: string): Locator {
    return this.content.getByRole('row').filter({ hasText: text })
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
    await expect(this.content.getByLabel('Nom')).toBeVisible()

    await this.content.getByLabel('Nom').fill(name)

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
