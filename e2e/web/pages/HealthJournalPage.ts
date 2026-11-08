import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Santé › Journal (MAG-750): on the left the « Jours » of the period, a row
 * `journal-row-<YYYY-MM-DD>` per day the journal holds; on the right the
 * « Entrée du jour » — `journal-day` (« Aujourd’hui samedi 7 nov. »), the
 * radiogroups « Moral » and « Fatigue » (radios « 1 » to « 10 »),
 * `journal-noted-by` (« avec Maggie · 21:42 »), the textbox « Note » and
 * « Enregistrer », then the day's figures, each a link to its section:
 * `journal-weight` (`journal-weight-value` « 71,6 kg », `journal-weight-detail`
 * « pesé à 07:12 »), `journal-steps`, `journal-balance`, `journal-sport`.
 */
export class HealthJournalPage extends AdminShell {
  readonly days: Locator
  readonly entry: Locator
  readonly day: Locator
  readonly mood: Locator
  readonly fatigue: Locator
  readonly note: Locator
  readonly save: Locator
  readonly weight: Locator

  constructor(page: Page) {
    super(page)
    this.days = this.content.getByRole('region', { name: 'Jours', exact: true })
    this.entry = this.content.getByRole('region', { name: 'Entrée du jour', exact: true })
    this.day = this.entry.getByTestId('journal-day')
    this.mood = this.entry.getByRole('radiogroup', { name: 'Moral', exact: true })
    this.fatigue = this.entry.getByRole('radiogroup', { name: 'Fatigue', exact: true })
    this.note = this.entry.getByRole('textbox', { name: 'Note' })
    this.save = this.entry.getByRole('button', { name: 'Enregistrer', exact: true })
    this.weight = this.entry.getByTestId('journal-weight-value')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.healthJournal)
    await expect(this.entry).toBeVisible()
    await expect(this.mood).toBeVisible()
  }

  row(date: string): Locator {
    return this.days.getByTestId(`journal-row-${date}`)
  }

  score(scale: 'Moral' | 'Fatigue', value: number): Locator {
    return (scale === 'Moral' ? this.mood : this.fatigue).getByRole('radio', { name: String(value), exact: true })
  }

  /** Clicks a score: the PATCH leaves at once; resolves once the API answered 200 and the radio is checked. */
  async rate(scale: 'Moral' | 'Fatigue', value: number): Promise<void> {
    const patched = this.page.waitForResponse(
      (response) => response.request().method() === 'PATCH' && new URL(response.url()).pathname.startsWith('/api/health/days/'),
    )
    await this.score(scale, value).click()
    const response = await patched
    expect(response.status(), `PATCH ${new URL(response.url()).pathname} answered ${response.status()}`).toBe(200)
    await expect(this.score(scale, value)).toHaveAttribute('aria-checked', 'true')
  }

  /** Types the note and saves it. */
  async writeNote(note: string): Promise<void> {
    await this.note.fill(note)
    const patched = this.page.waitForResponse(
      (response) => response.request().method() === 'PATCH' && new URL(response.url()).pathname.startsWith('/api/health/days/'),
    )
    await this.save.click()
    const response = await patched
    expect(response.status(), `PATCH ${new URL(response.url()).pathname} answered ${response.status()}`).toBe(200)
    await expect(this.save).toBeDisabled()
  }
}
