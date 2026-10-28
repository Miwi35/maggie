import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Créances et dettes (`/finance/peer-debts`, MAG-455): the two tiles
 * « On te doit » and « Tu dois », then « Par personne » (a net per person, a line
 * per object once opened) and « Par objet » (amount, who, note) — a switch, not tabs: the
 * section also sits on Trésorerie, which has none.
 */
export class FinancePeerDebtsPage extends AdminShell {
  readonly heading: Locator
  readonly owedToMeTile: Locator
  readonly iOweTile: Locator
  readonly byPersonSwitch: Locator
  readonly byObjectSwitch: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Créances et dettes', level: 2 })
    this.owedToMeTile = this.content.getByTestId('tile-owed-to-me')
    this.iOweTile = this.content.getByTestId('tile-i-owe')
    this.byPersonSwitch = this.content.getByRole('button', { name: 'Par personne' })
    this.byObjectSwitch = this.content.getByRole('button', { name: 'Par objet' })
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
