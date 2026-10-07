import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** Courses → Liste de courses. */
export class GroceryListPage extends AdminShell {
  readonly heading: Locator
  readonly addButton: Locator
  readonly endErrandButton: Locator
  /** « Plus tard »: the lines whose `buyAfter` is still ahead. */
  readonly laterSection: Locator
  /** The dialog behind "Ajouter" and the one behind "Terminer les courses". */
  readonly addDialog: Locator
  readonly remainingDialog: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByText('Ma liste de courses')
    this.addButton = this.content.getByRole('button', { name: 'Ajouter' })
    this.endErrandButton = this.content.getByRole('button', { name: 'Terminer les courses' })
    this.laterSection = this.content.getByTestId('grocery-later')
    this.addDialog = page.getByRole('dialog').filter({ hasText: 'Ajouter un article' })
    this.remainingDialog = page.getByRole('dialog').filter({ hasText: 'Articles restants' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.grocery)
    await expect(this.heading).toBeVisible()
  }

  item(label: string): Locator {
    return this.content.getByText(label, { exact: false }).first()
  }

  /**
   * Reloads once, then waits for the item.
   *
   * Call it *after* the write is known to be indexed — `waitForIndexed` on
   * the collection. This view fetches when it mounts and again on a Mercure
   * update, and both can happen before the index caught up, so the page in
   * front of you may be stale even though the data is not. One reload is
   * enough once the index is confirmed; polling `open()` in a loop meant each
   * attempt paid for a full page load and, on a loaded CI runner, the budget
   * went entirely on loading.
   */
  async expectItemEventually(label: string): Promise<void> {
    await this.page.reload()
    await expect(this.heading).toBeVisible()
    await expect(this.item(label)).toBeVisible()
  }

  /**
   * One line of the list, addressed by the words on it.
   *
   * `data-testid` rather than `getByRole('listitem')`: MUI renders a store's
   * `ListSubheader` as an `<li>` as well, so the role matches the aisle
   * headings too — and a filter on the store's name would then return a
   * heading and every line under it.
   */
  line(label: string): Locator {
    return this.content.getByTestId('grocery-item').filter({ hasText: label })
  }

  /** The tick box of one line — what a shopper presses in the shop. */
  tickBox(label: string): Locator {
    return this.line(label).getByRole('checkbox')
  }

  /** The confirmation behind a line's trash button. */
  deleteDialog(label: string): Locator {
    return this.page.getByRole('dialog').filter({ hasText: `Supprimer « ${label} » ?` })
  }

  /** Deletes a line the way the owner does: trash button, then confirm. */
  async deleteLine(label: string): Promise<void> {
    await this.line(label).getByRole('button', { name: `Supprimer ${label}` }).click()
    const dialog = this.deleteDialog(label)
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Supprimer' }).click()
    await expect(dialog).toBeHidden()
  }

  /** The aisle headings, in the order the page draws them. */
  async storeOrder(): Promise<string[]> {
    return this.content.getByTestId('grocery-store-group').evaluateAll((groups) =>
      groups.map((group) => group.getAttribute('data-store') ?? ''),
    )
  }

  /**
   * Writes a line through the dialog, exactly as the owner does.
   *
   * The label is free text on purpose: the add dialog's product autocomplete is
   * `freeSolo`, and a label matching no product is the path that creates one
   * (`AddGroceryItemHandler`). Pass `store` to choose an existing shop from its
   * own autocomplete.
   */
  async addItem(label: string, options: { quantity?: number; store?: string } = {}): Promise<void> {
    await this.addButton.click()
    await expect(this.addDialog).toBeVisible()

    await this.addDialog.getByLabel('Article').fill(label)

    if (options.quantity !== undefined) {
      await this.addDialog.getByLabel('Quantité').fill(String(options.quantity))
    }

    if (options.store !== undefined) {
      const store = this.addDialog.getByLabel('Magasin')
      await store.fill(options.store)
      // The option list is the shop's, not a free-text echo: clicking it is
      // what sends `storeId` rather than `storeName`, and so what proves the
      // line joined an aisle the seed already knows.
      await this.page.getByRole('option', { name: options.store }).click()
    }

    await this.addDialog.getByRole('button', { name: 'Ajouter' }).click()
    await expect(this.addDialog).toBeHidden()
  }

  /** Ends the errand and waits for the dialog listing what is left. */
  async endErrand(): Promise<void> {
    await this.endErrandButton.click()
    await expect(this.remainingDialog).toBeVisible()
  }

  /** A line offered for removal in the "Articles restants" dialog. */
  remainingLine(label: string): Locator {
    return this.remainingDialog.getByRole('listitem').filter({ hasText: label })
  }
}
