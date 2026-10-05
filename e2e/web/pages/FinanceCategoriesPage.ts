import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Catégories (`/categories`), and its three tabs.
 *
 * "Catégories", "Règles de catégorisation" and "Suggestions" are one screen on
 * purpose — a rule only means something next to its category. Each tab is
 * mounted only while it is selected (`{tab === 1 && …}`), so every locator
 * below has to be reached through {@link openTab} first.
 */
export class FinanceCategoriesPage extends AdminShell {
  readonly tabs: Locator
  /** "Appliquer les règles" — the catch-up pass over everything uncategorised. */
  readonly applyRules: Locator
  readonly suggestionsTable: Locator
  readonly createSuggestedRules: Locator

  constructor(page: Page) {
    super(page)
    this.tabs = this.content.getByRole('tablist')
    this.applyRules = this.content.getByRole('button', { name: 'Appliquer les règles' })
    this.suggestionsTable = this.content.getByRole('table')
    this.createSuggestedRules = this.content.getByRole('button', { name: /^Créer \d+ règle/ })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.categories)
    await expect(this.tabs).toBeVisible()
  }

  /** Selects a tab and waits for the panel it mounts. */
  async openTab(label: 'Catégories' | 'Règles de catégorisation' | 'Suggestions'): Promise<void> {
    await this.tabs.getByRole('tab', { name: label }).click()
    await expect(this.tabs.getByRole('tab', { name: label })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  }

  /**
   * A row of whichever tab's table is on screen, by one of its cells read
   * whole.
   *
   * An exact cell, not a substring of the row: the rules tab holds "LECLERC"
   * and, once a suggestion has been accepted, "LECLERC RENNES" as well — and a
   * `hasText: 'LECLERC'` matches both, which is a strict-mode failure rather
   * than an assertion.
   */
  row(cell: string): Locator {
    return this.content
      .getByRole('row')
      .filter({ has: this.page.getByRole('cell', { name: cell, exact: true }) })
  }

  /**
   * The checkbox and the category picker of one suggestion.
   *
   * The pattern is the row's identity — it is what the rule will be written
   * from — and it is rendered in a monospace cell, so filtering the row by it
   * is exact enough.
   */
  suggestion(pattern: string): Locator {
    return this.suggestionsTable.getByRole('row').filter({ hasText: pattern })
  }

  /** Picks the category a suggestion will file its merchant under. */
  async chooseSuggestionCategory(pattern: string, categoryName: string): Promise<void> {
    await this.suggestion(pattern).getByRole('combobox').click()
    await this.page.getByRole('option', { name: categoryName, exact: true }).click()
  }
}
