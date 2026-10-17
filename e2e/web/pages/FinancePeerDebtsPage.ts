import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Créances et dettes (`/finance/peer-debts`, MAG-455): the two tiles
 * « On te doit » and « Tu dois », then « Par personne » (a net per person, a line
 * per object once opened) and « Par objet » (amount, who, note).
 */
export class FinancePeerDebtsPage extends AdminShell {
  readonly heading: Locator
  readonly owedToMeTile: Locator
  readonly iOweTile: Locator
  readonly byPersonTab: Locator
  readonly byObjectTab: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Créances et dettes' })
    this.owedToMeTile = this.content.getByTestId('tile-owed-to-me')
    this.iOweTile = this.content.getByTestId('tile-i-owe')
    this.byPersonTab = this.content.getByRole('tab', { name: 'Par personne' })
    this.byObjectTab = this.content.getByRole('tab', { name: 'Par objet' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financePeerDebts)
    await expect(this.owedToMeTile).toBeVisible()
  }

  /** One person's row in « Par personne », by name. */
  person(name: string): Locator {
    return this.content.getByTestId('peer-debt-person').filter({ hasText: name })
  }

  /** One object's row in « Par objet ». */
  object(label: string): Locator {
    return this.content.getByTestId('peer-debt-object').filter({ hasText: label })
  }
}
