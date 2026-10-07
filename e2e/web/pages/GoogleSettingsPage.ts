import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Settings → Paramètres Google — the Google Tasks list Maggie syncs with.
 *
 * The screen called three endpoints that did not exist until MAG-118, so the
 * select was always empty and "Connecter" posted into the void. What it shows
 * now follows the account: several lists are offered, a single one is stated.
 */
export class GoogleSettingsPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Paramètres Google' })
  }

  /**
   * Loads the screen, waiting out the spinner it opens on.
   *
   * It fetches the lists from Google through the API before it renders
   * anything, so every locator below would otherwise race the round trip.
   */
  async open(): Promise<void> {
    await this.goto(ROUTES.googleSettings)
    await expect(this.heading).toBeVisible()
    await expect(this.content.getByText('Synchronisez vos tâches Maggie')).toBeVisible()
  }

  get taskListSelect(): Locator {
    return this.content.getByRole('combobox', { name: 'Liste Google Tasks' })
  }

  get connectButton(): Locator {
    return this.content.getByRole('button', { name: 'Connecter' })
  }

  get disconnectButton(): Locator {
    return this.content.getByRole('button', { name: 'Déconnecter' })
  }

  /** The line a connected screen shows: « Synchronisée avec "…" ». */
  syncedWith(title: string): Locator {
    // The component writes non-breaking spaces inside the quotes, so the text
    // is matched on the title alone rather than on the whole sentence.
    return this.content.getByText(new RegExp(`Synchronisée avec\\s*«\\s*${title}\\s*»`))
  }

  async chooseTaskList(title: string): Promise<void> {
    await this.taskListSelect.click()
    await this.page.getByRole('option', { name: title, exact: true }).click()
  }

  async connectTaskList(title: string): Promise<void> {
    await this.chooseTaskList(title)
    await this.connectButton.click()
  }
}
