import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Budget (`/envelopes`, MAG-632): two views, « Mois » and « Hors budget ».
 *
 * « Mois » is the compact toolbar (period, roll-over, « Ajuster », the lines to
 * qualify) over the tree of envelopes, each node drawing its own gauge; the
 * editor of a node only exists once one is selected. « Hors budget » lists the
 * annual and one-off envelopes apart from the month. The score rings and the
 * total belong to the Dashboard and are not repeated here.
 *
 * Every number on this screen is computed by the API from the seeded
 * transactions — which is what MAG-102 asks to be checked — so the nodes are
 * addressed by their category name and read whole, label and amounts together.
 */
export class FinanceBudgetPage extends AdminShell {
  readonly rollOverButton: Locator

  constructor(page: Page) {
    super(page)
    this.rollOverButton = this.content.getByRole('button', { name: 'Reconduire le mois précédent' })
  }

  /**
   * Opens « Mois » and waits for the tree to have *answered*.
   *
   * The tree renders before `useBudgetStatus` comes back, so a read taken on
   * the shell alone could see no envelope at all — and `gaugedCategories()` is
   * a one-shot `evaluateAll` with no auto-retry behind it. The wait is on the
   * status response, then on the tree's progress bar having gone.
   */
  async open(): Promise<void> {
    const answered = this.page.waitForResponse((response) => response.url().includes('/api/finance/budget-status'))
    await this.goto(ROUTES.envelopes)
    await answered
    await expect(this.tree()).toBeVisible()
    await expect(this.content.getByRole('progressbar')).toHaveCount(0)
  }

  /** Opens « Hors budget », the second tab of Budget: the annual and one-off envelopes. */
  async openOutside(): Promise<void> {
    const answered = this.page.waitForResponse((response) => response.url().includes('/api/finance/budget-status'))
    await this.goto(ROUTES.financeBudgetOutside)
    await answered
    await expect(this.content.getByTestId('envelope-outside')).toBeVisible()
    await expect(this.content.getByRole('progressbar')).toHaveCount(0)
  }

  /** The tabs of the menu, by name. */
  tabs(): Locator {
    return this.content.getByRole('tab')
  }

  /**
   * One category's gauge — its node in the tree — with its amounts.
   *
   * Addressed by `data-category` rather than by the amounts it shows: the
   * point of these assertions is that *this* category consumed *that* much,
   * and a `getByText('120,00 € / 200,00 €')` would be just as happy if the two
   * nodes swapped their categories.
   */
  gauge(categoryName: string): Locator {
    return this.node(categoryName)
  }

  /** The categories that have an envelope this month, in the order the tree draws them. */
  async gaugedCategories(): Promise<string[]> {
    return this.tree()
      .locator('[data-testid="envelope-node"][data-has-envelope="true"]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-category') ?? ''))
  }

  /** The tree of categories (MAG-419); the editor opens beside it once a node is selected. */
  tree(): Locator {
    return this.content.getByTestId('envelope-tree')
  }

  /**
   * A category's node in the tree, with or without an envelope.
   *
   * By `data-category`: the point is that *this* category
   * reads *that* amount, not that some node does.
   */
  node(categoryName: string): Locator {
    return this.tree().locator(`[data-testid="envelope-node"][data-category="${categoryName}"]`)
  }

  /** An entry of the « Hors budget » tab: an annual envelope's category, or a one-off event's name. */
  outsideNode(name: string): Locator {
    return this.content.getByTestId('envelope-outside').locator(`[data-testid="envelope-outside-node"][data-category="${name}"]`)
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
   * Moves the period picker; the assertions that follow retry until the tree
   * has answered for the new period.
   */
  async selectPeriod(year: number, month: number): Promise<void> {
    const picker = this.content
    await picker.getByLabel('Année', { exact: true }).fill(String(year))
    await picker.getByLabel('Mois', { exact: true }).click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(picker.getByLabel('Mois', { exact: true })).toHaveText(MONTH_LABELS[month])
  }

  /**
   * Opens the ruler of a mother envelope: selecting her node opens her editor,
   * where the ruler is already drawn, with its handles.
   */
  async openRuler(motherName: string): Promise<Locator> {
    await this.selectNode(motherName)
    const ruler = this.editor().getByTestId('envelope-split')
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

  /** « Ajuster »: opens next month's adjustments and returns the row of a category once they are read. */
  async openAdjustments(categoryName: string): Promise<Locator> {
    await this.content.getByRole('button', { name: 'Ajuster', exact: true }).click()
    const row = this.content.locator(`[data-testid="adjustment-row"][data-category="${categoryName}"]`)
    await expect(row).toBeVisible()

    return row
  }

  /** Applies a category's suggestion and returns what the API answered: the screen redraws from it. */
  async applySuggestion(categoryName: string): Promise<{ applied: { categoryId: string; envelopeId: string; amountCents: number }[] }> {
    const [response] = await Promise.all([
      this.page.waitForResponse(
        (candidate) => candidate.url().includes('/api/finance/envelope-adjustments') && 'POST' === candidate.request().method(),
      ),
      this.content.getByRole('button', { name: `Appliquer pour ${categoryName}` }).click(),
    ])
    expect(response.status(), await response.text()).toBe(200)

    return response.json()
  }
}
