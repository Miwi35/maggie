import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'

/** The « Digest du matin » screen: the voice and the word at the head, then one card per module with something to say. */
export class DigestPage extends AdminShell {
  readonly trail: Locator
  readonly greeting: Locator
  readonly word: Locator

  constructor(page: Page) {
    super(page)
    this.trail = page.getByRole('navigation', { name: "Fil d'Ariane" })
    this.greeting = this.content.getByText('Bonjour monsieur')
    this.word = this.content.getByTestId('digest-word')
  }

  async open(messageId: string): Promise<void> {
    await this.goto(`/digest?message=${messageId}`)
    await expect(this.trail.getByText('Digest du matin')).toHaveAttribute('aria-current', 'page')
    await expect(this.greeting).toBeVisible()
  }

  /** `weather`, `agenda`, `tasks`, `cuisine` or `health`. */
  card(name: string): Locator {
    return this.content.getByTestId(name === 'weather' ? 'digest-weather' : `home-card-${name}`)
  }
}
