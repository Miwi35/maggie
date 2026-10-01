import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'

/**
 * The search box in the app bar and the dropdown of results under it.
 *
 * Results come back grouped by index, each group opened by a subheader — "Repas",
 * "Recettes", "Événements". Two indexes can hold the same words (the seeded meal
 * and its recipe are both "Pâtes à la tomate"), so a result is addressed by its
 * group as well as its label.
 */
export class GlobalSearch extends AdminShell {
  readonly input: Locator

  constructor(page: Page) {
    super(page)
    this.input = this.appBar.getByPlaceholder('Rechercher…')
  }

  async search(query: string): Promise<void> {
    await this.input.fill(query)
  }

  /** The result labelled `label` inside the group headed `group`. */
  result(group: string, label: string): Locator {
    const subheader = this.page.getByRole('listitem').filter({ hasText: new RegExp(`^${group}$`) })

    return subheader.locator('xpath=following-sibling::*[@role="button"][1]').filter({ hasText: label })
  }

  /** Types `query`, waits for the group to list `label`, and clicks it. */
  async open(query: string, group: string, label: string): Promise<void> {
    await this.search(query)
    const result = this.result(group, label)
    await expect(result, `"${query}" never listed "${label}" under "${group}"`).toBeVisible()
    await result.click()
  }
}
