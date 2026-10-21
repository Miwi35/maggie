import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { PeerTransferQualifier } from './PeerTransferQualifier.js'
import { ROUTES } from './routes.js'

type State = 'all' | 'unrecognised' | 'recognised'
type Period = 'month' | 'since_review' | 'all'

/**
 * Finance › Revue (`/finance/review`, MAG-539): the one list of transactions — a state, a
 * period and a search — with the fiche of the line on the right (below the list on a phone).
 * It replaces the three pages « À qualifier », « Reconnues à contrôler » and « Revue mensuelle ».
 *
 * A line the owner handles changes state in place and stays on its row until the filter changes
 * or the page is reloaded, so « Suivant » can walk on to the next one.
 */
export class FinanceReviewPage extends AdminShell {
  readonly sheet: Locator
  readonly rows: Locator
  readonly search: Locator
  readonly always: Locator
  readonly otherCategory: Locator
  readonly validate: Locator
  readonly qualify: Locator
  readonly correct: Locator
  readonly satisfaction: Locator
  readonly associateContact: Locator
  readonly contactPicker: Locator
  /** « Ce virement est », on a transfer with a person not answered yet (MAG-453). */
  readonly peerTransfer: PeerTransferQualifier

  constructor(page: Page) {
    super(page)
    this.sheet = this.content.getByRole('region', { name: 'Fiche de la transaction' })
    this.rows = this.content.getByTestId('review-row')
    this.search = this.content.getByRole('searchbox', { name: 'Rechercher une transaction' })
    this.always = this.sheet.getByRole('switch', { name: 'Toujours pour ce marchand' })
    this.otherCategory = this.sheet.getByRole('button', { name: 'Autre catégorie' })
    this.validate = this.sheet.getByRole('button', { name: 'Valider' })
    this.qualify = this.sheet.getByRole('button', { name: 'Qualifier' })
    this.correct = this.sheet.getByRole('button', { name: 'Correct' })
    this.satisfaction = this.sheet.getByRole('group', { name: 'Satisfaction' })
    this.associateContact = this.sheet.getByRole('button', { name: 'Associer à un contact ?' })
    // A dialog, portalled out of the page content.
    this.contactPicker = page.getByRole('dialog', { name: 'Associer à un contact' })
    this.peerTransfer = new PeerTransferQualifier(page, this.sheet)
  }

  /** Opens the list with these filters (the URL carries them) and waits for it to have answered. */
  async open(filter: { state?: State; period?: Period; q?: string } = {}): Promise<void> {
    const query = new URLSearchParams()
    if (filter.state) query.set('state', filter.state)
    if (filter.period) query.set('period', filter.period)
    if (filter.q) query.set('q', filter.q)
    const text = query.toString()

    await this.goto(text === '' ? ROUTES.financeReview : `${ROUTES.financeReview}?${text}`)
    await expect(this.rows.first().or(this.content.getByText('Aucune transaction'))).toBeVisible()
  }

  /** The state pill, « À qualifier · 2 » for `À qualifier`: its count is read by the caller. */
  statePill(name: 'Toutes' | 'À qualifier' | 'Reconnues'): Locator {
    return this.content.getByRole('group', { name: 'État' }).getByRole('button', { name: new RegExp(`^${name}`) })
  }

  /** The row of a line, by its label. */
  row(label: string): Locator {
    return this.content.getByRole('button', { name: label, exact: true })
  }

  /** The state the row shows: `À qualifier` or `Reconnue`. */
  rowState(label: string): Locator {
    return this.row(label).getByText(/^(À qualifier|Reconnue)$/)
  }

  /** Brings a line up on the fiche. */
  async select(label: string): Promise<void> {
    await this.row(label).click()
    await this.expectSheet(label)
  }

  /** The fiche on screen is this line's. */
  async expectSheet(label: string): Promise<void> {
    await expect(this.sheet.getByRole('heading', { name: label, exact: true })).toBeVisible()
  }

  /** « Suivant » / « Précédent »: the next or previous line of the filtered list, in its order. */
  async step(direction: 'Précédent' | 'Suivant'): Promise<void> {
    await this.content.getByRole('button', { name: direction, exact: true }).click()
  }

  async chooseCategory(category: string): Promise<void> {
    await this.otherCategory.click()
    await this.sheet.getByLabel('Catégorie').fill(category)
    await this.page.getByRole('option', { name: category, exact: true }).click()
    await this.qualify.click()
  }

  /** « À conserver » or « Superflue », on a spend only: an income offers neither. */
  async judge(verdict: 'À conserver' | 'Superflue'): Promise<void> {
    await this.satisfaction.getByRole('button', { name: verdict }).click()
    await expect(this.satisfaction.getByRole('button', { name: verdict, pressed: true })).toBeVisible()
  }

  /**
   * « Associer à un contact ? » → a contact created from its name (MAG-450).
   * Waits for the picker to close: the link is made and the list read again.
   */
  async associateWithNewContact(name: string): Promise<void> {
    await this.associateContact.click()
    await expect(this.contactPicker).toBeVisible()
    await this.contactPicker.getByLabel('Rechercher un contact').fill(name)
    await this.contactPicker.getByRole('button', { name: `+ Créer « ${name} »`, exact: true }).click()
    await expect(this.contactPicker).toBeHidden()
  }
}
