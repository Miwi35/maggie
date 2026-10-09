import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'

/** The bell in the app bar and the list it opens. */
export class NotificationBell extends AdminShell {
  readonly bell: Locator
  readonly popover: Locator

  constructor(page: Page) {
    super(page)
    // Not by role: with the list open the app bar is aria-hidden, and the journey keeps it open on purpose.
    this.bell = page.locator('button[aria-label="Notifications"]')
    this.popover = page.getByRole('presentation').filter({ hasText: 'Notifications' })
  }

  async expectUnreadCount(count: number, timeout = 5_000): Promise<void> {
    if (count === 0) {
      await expect(this.bell.locator('.MuiBadge-badge:not(.MuiBadge-invisible)')).toHaveCount(0, { timeout })
    } else {
      await expect(this.bell.locator('.MuiBadge-badge:not(.MuiBadge-invisible)')).toHaveText(String(count), { timeout })
    }
  }

  async open(): Promise<void> {
    await this.bell.click()
    await expect(this.popover).toBeVisible()
  }

  item(title: string): Locator {
    return this.popover.getByRole('button', { name: title })
  }

  /** The row of one notification, with its trash icon. */
  row(title: string): Locator {
    return this.popover.getByRole('listitem').filter({ hasText: title })
  }

  async delete(title: string): Promise<void> {
    await this.row(title).getByRole('button', { name: 'Supprimer' }).click()
  }

  /** "Tout effacer", then the confirmation. */
  async clearAll(): Promise<void> {
    await this.popover.getByRole('button', { name: 'Tout effacer' }).click()
    const dialog = this.page.getByRole('dialog', { name: 'Effacer toutes les notifications ?' })
    await dialog.getByRole('button', { name: 'Effacer', exact: true }).click()
    await expect(dialog).toBeHidden()
  }
}
