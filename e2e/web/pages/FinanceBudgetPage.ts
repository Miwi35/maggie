import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Budgets (`/envelopes`).
 *
 * Three things stacked on one route, and the journeys read all three: the
 * day's score banner, the consumption gauges for the chosen period with the
 * roll-over button, and the tree of envelopes with its editor.
 *
 * Every number on this screen is computed by the API from the seeded
 * transactions — which is what MAG-102 asks to be checked — so the gauges are
 * addressed by their category name and read whole, label and amounts together.
 */
export class FinanceBudgetPage extends AdminShell {
  readonly scoreBanner: Locator
  readonly budgetCard: Locator
  readonly rollOverButton: Locator
  readonly totalLine: Locator
  /** « Hors budget mensuel »: the annual and one-off envelopes, out of the month's totals (MAG-414). */
  readonly outsideMonthly: Locator

  constructor(page: Page) {
    super(page)
    // The banner is an Alert whose title is the score's own wording; the card
    // below it is the one holding the "Budgets" heading.
    this.scoreBanner = this.content.getByRole('alert').first()
    this.budgetCard = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Budgets' }) })
    this.rollOverButton = this.budgetCard.getByRole('button', {
      name: 'Reconduire le mois précédent',
    })
    this.totalLine = this.budgetCard.getByText(/^Total :/)
    this.outsideMonthly = this.budgetCard.getByTestId('outside-monthly-budget')
  }

  /**
   * Opens the screen and waits for the panel to have *answered*.
   *
   * Not just the card: its "Budgets" heading renders before `useBudgetStatus`
   * comes back, so a read taken on the shell alone can legitimately see no
   * gauge at all — and `gaugedCategories()` is a one-shot `evaluateAll` with
   * no auto-retry behind it. The total line only exists once the status is in,
   * and so does the empty list's own invitation.
   */
  async open(): Promise<void> {
    await this.goto(ROUTES.envelopes)
    await expect(this.budgetCard).toBeVisible()
    await expect(
      this.totalLine.or(this.content.getByText("Aucune enveloppe pour l'instant")),
    ).toBeVisible()
  }

  /**
   * One gauge, with its category, its period and its amounts.
   *
   * Addressed by `data-category` rather than by the amounts it shows: the
   * point of these assertions is that *this* category consumed *that* much,
   * and a `getByText('120,00 € / 200,00 €')` would be just as happy if the two
   * gauges swapped their categories.
   */
  gauge(categoryName: string): Locator {
    return this.budgetCard.locator(`[data-testid="budget-gauge"][data-category="${categoryName}"]`)
  }

  /** A gauge of « Hors budget mensuel »: an annual envelope's category, or a one-off envelope's name. */
  outsideGauge(name: string): Locator {
    return this.outsideMonthly.locator(`[data-testid="budget-gauge"][data-category="${name}"]`)
  }

  /** The gauges on screen, in the order the panel draws them. */
  async gaugedCategories(): Promise<string[]> {
    return this.budgetCard
      .getByTestId('budget-gauge')
      .evaluateAll((gauges) => gauges.map((gauge) => gauge.getAttribute('data-category') ?? ''))
  }

  /** The tree of categories, left of the editor (MAG-419). */
  tree(): Locator {
    return this.content.getByTestId('envelope-tree')
  }

  /**
   * A category's node in the tree, with or without an envelope.
   *
   * By `data-category`, like the gauges: the point is that *this* category
   * reads *that* amount, not that some node does.
   */
  node(categoryName: string): Locator {
    return this.tree().locator(`[data-testid="envelope-node"][data-category="${categoryName}"]`)
  }

  /** An entry of « Hors budget mensuel » in the tree: an annual envelope's category, or a one-off event's name. */
  outsideNode(name: string): Locator {
    return this.content.locator(`[data-testid="envelope-outside-node"][data-category="${name}"]`)
  }

  /** The editor of the selected node, right of the tree. */
  editor(): Locator {
    return this.content.getByTestId('envelope-editor')
  }

  /** Selects a node and waits for the editor to be about it. */
  async selectNode(categoryName: string): Promise<void> {
    const node = this.node(categoryName)
    await node.click()
    await expect(node).toHaveAttribute('aria-selected', 'true')
    await expect(this.editor()).toContainText(categoryName)
  }

  /**
   * Fills the editor of a node that has no envelope, saves, and waits for the
   * POST itself: the tree redraws from the budget status, so an assertion made
   * before the response would read the node as it was.
   */
  async createEnvelope(options: {
    type?: 'Mensuelle' | 'Annuelle'
    amountEuros: number
    thresholdPercent?: number
    seasons?: { fromMonth: number; toMonth: number; amountEuros: number }[]
  }): Promise<void> {
    const editor = this.editor()
    if (options.type && 'Mensuelle' !== options.type) {
      await editor.getByLabel("Type d'enveloppe").click()
      await this.page.getByRole('option', { name: options.type }).click()
    }
    await editor.getByLabel("Montant de l'enveloppe (€)").fill(String(options.amountEuros))
    if (undefined !== options.thresholdPercent) {
      await editor.getByLabel("Seuil d'alerte (%)").fill(String(options.thresholdPercent))
    }
    for (const [index, season] of (options.seasons ?? []).entries()) {
      await editor.getByRole('button', { name: 'Ajouter une saison' }).click()
      await editor.getByLabel('De', { exact: true }).nth(index).click()
      await this.page.getByRole('option', { name: MONTH_LABELS[season.fromMonth] }).click()
      await editor.getByLabel('À', { exact: true }).nth(index).click()
      await this.page.getByRole('option', { name: MONTH_LABELS[season.toMonth] }).click()
      await editor.getByLabel('Montant (€)').nth(index).fill(String(season.amountEuros))
    }
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/api/envelopes') && 'POST' === response.request().method(),
      ),
      editor.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
  }

  /** Saves the editor of a node that has an envelope, and waits for the PATCH/PUT. */
  async saveEnvelope(): Promise<void> {
    await Promise.all([
      this.page.waitForResponse(
        (response) =>
          response.url().includes('/api/envelopes/') && ['PATCH', 'PUT'].includes(response.request().method()),
      ),
      this.editor().getByRole('button', { name: 'Enregistrer' }).click(),
    ])
  }

  /**
   * Carries the previous period's envelopes into the one on screen, and waits
   * for the call itself.
   *
   * Waiting on the response rather than on the button coming back: the pass is
   * safe to re-run, so the second run changes nothing on screen — and an
   * assertion made while it is still in flight would be reading the state
   * before it, which is the state it is meant to prove unchanged.
   */
  async rollOver(): Promise<void> {
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/api/finance/rollover-envelopes'),
      ),
      this.rollOverButton.click(),
    ])
  }

  /**
   * Moves the period picker, and waits for the panel to have answered for it.
   *
   * The picker drives three independent fetches (score, status, list), so the
   * wait is on the heading the new period produces rather than on a spinner
   * that may already have gone.
   */
  async selectPeriod(year: number, month: number): Promise<void> {
    const picker = this.budgetCard
    await picker.getByLabel('Année').fill(String(year))
    await picker.getByLabel('Mois').click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(picker.getByLabel('Mois')).toHaveText(MONTH_LABELS[month])
  }

  /**
   * Opens the ruler of a mother envelope, below its gauge.
   *
   * It stays closed until asked for: the gauge alone is the read screen, the
   * ruler is where the owner shares her amount between the daughters.
   */
  async openRuler(motherName: string): Promise<Locator> {
    await this.gauge(motherName).getByRole('button', { name: 'Répartir' }).click()
    const ruler = this.gauge(motherName).getByTestId('envelope-split')
    await expect(ruler).toBeVisible()

    return ruler
  }

  /** The edit form of an envelope, where a mother carries her ruler already open. */
  async openEnvelopeForm(envelopeId: string): Promise<Locator> {
    await this.goto(`${ROUTES.envelopes}/${encodeURIComponent(`/api/envelopes/${envelopeId}`)}`)
    const ruler = this.content.getByTestId('envelope-split')
    await expect(ruler).toBeVisible()

    return ruler
  }

  /** What the ruler says a segment holds: a daughter, or « libre ». */
  rulerAmount(ruler: Locator, segment: string): Locator {
    return ruler.locator(`[data-testid="split-amount"][data-category="${segment}"]`)
  }

  /**
   * Moves the boundary after `daughter` by keyboard — one euro a press — and
   * saves. Waits for the write itself: the gauges redraw from the API's answer,
   * so an assertion made before the response would read the old split.
   */
  async moveBoundary(ruler: Locator, daughter: string, next: string, euros: number): Promise<void> {
    const handle = ruler.getByRole('slider', { name: `Frontière entre ${daughter} et ${next}` })
    await handle.focus()
    for (let i = 0; i < Math.abs(euros); i += 1) {
      await this.page.keyboard.press(euros > 0 ? 'ArrowRight' : 'ArrowLeft')
    }
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/split') && 'PUT' === response.request().method(),
      ),
      ruler.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
  }
}
