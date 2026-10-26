import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance › Planification › Projets (`/finance/projects`, MAG-519): the
 * projects in priority order, each row with a handle that moves it with the
 * mouse (dnd-kit) or the keyboard (Espace, flèches, Espace), then the Gantt of the
 * planning over 36 months under the list (MAG-527) and, under the Gantt, the stacked chart
 * of the funding month by month (MAG-528). The scenarios (MAG-551) follow on the same
 * page: the scenario cards, the current-scenario banner and the comparison.
 */
export class FinanceProjectsPage extends AdminShell {
  readonly rows: Locator
  readonly gantt: Locator
  readonly fundingChart: Locator

  constructor(page: Page) {
    super(page)
    this.rows = this.content.getByTestId('project-row')
    this.gantt = this.content.getByTestId('project-gantt')
    this.fundingChart = this.gantt.getByTestId('funding-chart')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeProjects)
    await expect(this.content.getByRole('heading', { name: 'Projets' })).toBeVisible()
  }

  row(name: string): Locator {
    return this.content.locator(`[data-testid="project-row"][data-project="${name}"]`)
  }

  /** The drag handle of a project, also its keyboard control. */
  handle(name: string): Locator {
    return this.row(name).getByRole('button', { name: new RegExp(`^Déplacer ${name},`) })
  }

  /** The names shown, top first, among `names` — other projects may sit in between. */
  async order(names: string[]): Promise<string[]> {
    const shown = await this.rows.evaluateAll((rows) => rows.map((row) => row.getAttribute('data-project') ?? ''))

    return shown.filter((name) => names.includes(name))
  }

  /** A project's line in the Gantt. */
  ganttRow(name: string): Locator {
    return this.gantt.locator(`[data-testid="gantt-row"][data-project="${name}"]`)
  }

  /** The projects drawn in the Gantt, top first, among `names`. */
  async ganttOrder(names: string[]): Promise<string[]> {
    const shown = await this.gantt
      .getByTestId('gantt-row')
      .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-project') ?? ''))

    return shown.filter((name) => names.includes(name))
  }

  /** The month index (0 = this month) of a project's purchase mark, or null when it is past the horizon. */
  async purchaseAt(name: string): Promise<number | null> {
    const mark = this.ganttRow(name).getByTestId('gantt-purchase')
    if ((await mark.count()) === 0) return null

    return Number(await mark.getAttribute('data-at'))
  }

  /** A project's funding detail (MAG-525): its sources, then the three ways to pay it. */
  funding(name: string): Locator {
    return this.content.getByRole('region', { name: `Financement de ${name}` })
  }

  async openFunding(name: string): Promise<Locator> {
    await this.row(name).getByRole('button', { name: `Financement de ${name}` }).click()
    const detail = this.funding(name)
    await expect(detail.getByRole('button', { name: 'Enregistrer les sources' })).toBeVisible()

    return detail
  }

  /** « Économies » or « Emprunt » added as a source, its amount typed in euros. */
  async addSource(name: string, kind: 'Économies' | 'Emprunt', euros: number): Promise<void> {
    const detail = this.funding(name)
    await detail.getByRole('button', { name: kind, exact: true }).click()
    await detail.getByLabel(`Montant ${kind.toLowerCase()} (€)`, { exact: true }).fill(String(euros))
  }

  /** The three option columns, comptant · emprunt · partiel, as shown. */
  options(name: string): Locator {
    return this.funding(name).getByTestId('project-option')
  }

  /** The column of a month (`YYYY-MM`) in the funding chart. */
  fundingBar(month: string): Locator {
    return this.fundingChart.locator(`[data-testid="funding-bar"][data-month="${month}"]`)
  }

  /** The months (`YYYY-MM`) the funding chart flags as above the capacity, first month first. */
  async flaggedMonths(): Promise<string[]> {
    return this.fundingChart
      .getByTestId('funding-over')
      .evaluateAll((marks) => marks.map((mark) => mark.getAttribute('data-month') ?? ''))
  }

  /** The investment card of the project opened in the detail panel (MAG-557). */
  investmentCard(name: string): Locator {
    return this.funding(name).getByTestId('investment-card')
  }

  /**
   * Defines the investment of a project from its card: the dialog filled with a down payment, a rent,
   * the loan's rate and duration and a resale in `resaleMonth` (`YYYY-MM`) that funds `target`, then saved.
   */
  async defineInvestment(
    name: string,
    values: { downPayment: string; rent: string; rate: string; months: string; resaleMonth: string; resaleNet: string; target: string },
  ): Promise<void> {
    await this.investmentCard(name).getByRole('button', { name: 'Définir un investissement' }).click()
    const form = this.page.getByRole('form', { name: `Investissement · ${name}` })
    await form.getByLabel(/^Apport/).fill(values.downPayment)
    await form.getByLabel(/^Loyer mensuel/).fill(values.rent)
    await form.getByLabel(/^Taux annuel du prêt/).fill(values.rate)
    await form.getByLabel(/^Durée du prêt/).fill(values.months)
    await form.getByRole('checkbox', { name: 'Revente planifiée' }).check()
    await form.getByLabel(/^Mois de la revente/).fill(values.resaleMonth)
    await form.getByLabel(/^Net de la revente/).fill(values.resaleNet)
    await form.getByRole('combobox', { name: 'Projet financé par la revente' }).click()
    await this.page.getByRole('option', { name: values.target, exact: true }).click()
    await form.getByRole('button', { name: 'Enregistrer' }).click()
  }

  /** One figure of the investment card (`price`, `down-payment`, `loan`, `rent`, `payment`, `balance`). */
  investmentFigure(name: string, figure: string): Locator {
    return this.investmentCard(name).locator(`[data-testid="investment-figure"][data-figure="${figure}"]`)
  }

  /** The scenarios section of the page (MAG-551, MAG-633: no tab of its own). */
  async openScenarios(): Promise<Locator> {
    const panel = this.content.getByTestId('scenarios-panel')
    await expect(panel.getByTestId('scenario-card').first()).toBeVisible()

    return panel
  }

  /** A scenario card of the scenarios section, by its name. */
  scenarioCard(name: string): Locator {
    return this.content.locator(`[data-testid="scenario-card"][data-scenario="${name}"]`)
  }

  /** The « Scénario courant · … » banner. */
  get currentBanner(): Locator {
    return this.content.getByTestId('scenario-current')
  }

  /** The dialog of a new project, filled and sent to « Aperçu » (MAG-553): nothing is created yet. */
  async previewNewProject(values: { name: string; euros: string; fixedOn: string }): Promise<Locator> {
    await this.content.getByRole('button', { name: 'Nouveau projet' }).click()
    const dialog = this.page.getByRole('dialog')
    await dialog.getByLabel(/^Nom/).fill(values.name)
    await dialog.getByLabel(/^Montant/).fill(values.euros)
    await dialog.getByRole('radio', { name: 'Date fixe (verrouillée)' }).check()
    await dialog.getByLabel(/^Date du projet/).fill(values.fixedOn)
    await dialog.getByRole('button', { name: 'Aperçu' }).click()

    return dialog.getByRole('region', { name: `Aperçu · ${values.name}` })
  }

  /** A project's line of the preview table. */
  previewDate(preview: Locator, name: string): Locator {
    return preview.locator(`[data-testid="preview-date"][data-project="${name}"]`)
  }
}
