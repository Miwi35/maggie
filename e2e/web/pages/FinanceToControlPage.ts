import { expect } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Reconnues à contrôler (`/finance/to-control`): the lines a rule or a
 * recurring series qualified this month and nobody checked yet, one card per source.
 */
export class FinanceToControlPage extends AdminShell {
  async open(): Promise<void> {
    await this.goto(ROUTES.financeToControl)
    await expect(
      this.content.getByRole('region', { name: /^Groupe / }).first().or(this.content.getByText('Rien à contrôler ce mois-ci')),
    ).toBeVisible()
  }

  /** The card of one source, found by the start of its name (« Groupe <label> »). */
  group(label: string): Locator {
    return this.content.getByRole('region', { name: new RegExp(`^Groupe ${label}`) })
  }

  /** « Valider » or « Tout à conserver », on the whole group. */
  async control(group: Locator, gesture: 'Valider' | 'Tout à conserver'): Promise<void> {
    await group.getByRole('button', { name: gesture }).click()
  }
}
