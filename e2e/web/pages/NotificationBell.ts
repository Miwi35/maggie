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

  /** The count on the badge; 0 once it is hidden (MUI keeps the last number in the DOM while it fades). */
  async unreadCount(): Promise<number> {
    const badge = this.bell.locator('.MuiBadge-badge')
    if ((await badge.count()) === 0 || (await badge.getAttribute('class'))?.includes('MuiBadge-invisible')) return 0

    return Number((await badge.textContent()) ?? 0)
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
}
