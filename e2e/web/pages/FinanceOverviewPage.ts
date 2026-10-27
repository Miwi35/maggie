import { expect } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → État courant (`/finance/dashboard`), the finance home (MAG-420, MAG-631).
 *
 * One read of `GetFinanceDashboard`, drawn top to bottom as the dated header,
 * the review's action card when it is due, the two rings beside the
 * « Prochain projet » card, the month's spending against its rhythm, then
 * three tiles — Comptes, Budget, Planification — each opening its menu.
 * Nothing here is stored: the total is the sum of the accounts, the saving
 * capacity is income minus loan payments minus a measured lifestyle. So the
 * journeys read the tiles and check the arithmetic against the fixtures.
 *
 * A second tab, « À venir » (MAG-463), reads `/api/finance/upcoming`: the week
 * and the month after the current ones, the next month's adjusted envelopes and
 * what is left toward the projects.
 *
 * Tiles are reached by their test id (`tile-accounts`, `tile-budget`,
 * `tile-planning`); the cards inside them by their own.
 */
export class FinanceOverviewPage extends AdminShell {
  async open(): Promise<void> {
    await this.goto(ROUTES.financeOverview)
    // Not the tabs: they render before the fetch answers, so waiting on them
    // would let an assertion run against a screen that has no data yet.
    await expect(this.accountsTile).toBeVisible()
  }

  /** « Comptes »: the total, the matelas set aside, each account. */
  get accountsTile(): Locator {
    return this.content.getByTestId('tile-accounts')
  }

  /** « Budget »: envelope histogram, matelas, expected salary, balance before it. */
  get budgetTile(): Locator {
    return this.content.getByTestId('tile-budget')
  }

  /** « Planification »: saving capacity and financial independence. */
  get planningTile(): Locator {
    return this.content.getByTestId('tile-planning')
  }

  /** The tile's own title is the link that opens its menu (the whole card is clickable). */
  tileLink(tile: Locator, name: string): Locator {
    return tile.getByRole('heading', { name }).getByRole('link')
  }

  /**
   * What waits for the owner (MAG-421), drawn as pills in the tiles. A pill with
   * nothing to say is not drawn, so `toHaveCount(0)` is the « nothing to do » check.
   */
  get toSortPill(): Locator {
    return this.accountsTile.getByTestId('pill-to-sort')
  }

  get rejectionsPill(): Locator {
    return this.accountsTile.getByTestId('pill-rejections')
  }

  /** « On te doit 80 € » / « Tu dois 80 € » (MAG-455): the net of the créances and dettes, one tap from their screen. */
  get owedPill(): Locator {
    return this.accountsTile.getByTestId('pill-owed')
  }

  /** « 800,00 € à remettre », in the Budget tile: the savings advances not given back. */
  get advancePill(): Locator {
    return this.budgetTile.getByTestId('pill-advance')
  }

  /** « Revue d'octobre · ce soir · Lancer », above the rings while the month just ended is not reviewed. */
  get reviewActionCard(): Locator {
    return this.content.getByTestId('review-action-card')
  }

  /** « Octobre, jour 13 sur 31 · Synchronisé il y a 12 min ». */
  get header(): Locator {
    return this.content.getByTestId('dashboard-header')
  }

  /** « Dépenses d'octobre »: what is spent against the month's rhythm, the direct debits marked on it. */
  get monthSpendingCard(): Locator {
    return this.content.getByTestId('month-spending-card')
  }

  /** The « Prochain projet » card (MAG-529): the first project of the planning, absent with none. */
  get nextProject(): Locator {
    return this.content.getByTestId('next-project-card')
  }

  /** The envelope's column in the Budget tile, by the category it carries. */
  envelopeBar(name: string): Locator {
    return this.budgetTile.locator(`[data-testid="budget-bar"][data-category="${name}"]`)
  }

  /** « Salaire attendu » of the Budget tile (MAG-275): the amount and where it comes from. */
  expectedSalary(): Locator {
    return this.budgetTile.getByTestId('expected-salary')
  }

  /** « Solde attendu avant le salaire » of the Budget tile. */
  balanceBeforeSalary(): Locator {
    return this.budgetTile.getByTestId('balance-before-salary')
  }

  /** The saving capacity, in the Planification tile. */
  savingCapacity(): Locator {
    return this.planningTile.getByTestId('saving-capacity')
  }

  /** The independence counter, in the Planification tile. */
  independence(): Locator {
    return this.planningTile.getByTestId('independence-card')
  }

  /**
   * A line of the independence counter's breakdown: one rente category, what
   * it brings in per month, and its share. Addressed by `data-category`: the
   * lines repeat, and the assertion is that *this* category brings *that* much.
   */
  rente(categoryName: string): Locator {
    return this.content.locator(`[data-testid="rente"][data-category="${categoryName}"]`)
  }

  /** The week's or the month's ring: the remainder, the status word, the tick. */
  scoreRing(period: 'week' | 'month'): Locator {
    return this.content.locator(`[data-testid="score-ring"][data-period="${period}"]`)
  }

  /** What a touch on a ring shows: the reasons of its status. */
  async scoreReasons(period: 'week' | 'month'): Promise<Locator> {
    const ring = this.scoreRing(period)
    await ring.getByRole('button').click()
    return ring.getByTestId('score-reasons')
  }

  /** The rhythm curve of the period picked, and the direct debits marked on it. */
  get rhythmCurve(): Locator {
    return this.content.getByTestId('rhythm-curve')
  }

  debitMarker(label: string): Locator {
    return this.rhythmCurve.locator(`[data-testid="debit-marker"][data-label="${label}"]`)
  }

  /** The « À venir » tab of the dashboard (MAG-463). */
  get upcomingTab(): Locator {
    return this.content.getByRole('tab', { name: 'À venir' })
  }

  /** The « État courant » tab: everything the home shows, and the default. */
  get currentTab(): Locator {
    return this.content.getByRole('tab', { name: 'État courant' })
  }

  /** Opens the home, then its « À venir » tab, and waits for the projection to have answered. */
  async openUpcoming(): Promise<void> {
    await this.open()
    await this.upcomingTab.click()
    await expect(this.upcomingRemaining).toBeVisible()
  }

  /** The week after the current one: totals, days and items. */
  get upcomingWeek(): Locator {
    return this.content.getByTestId('upcoming-week')
  }

  /** The month after the current one: totals, days and items. */
  get upcomingMonth(): Locator {
    return this.content.getByTestId('upcoming-month')
  }

  /** One line of a window (a recurring series' occurrence, a planned or committed movement), by its label. */
  upcomingItem(window: Locator, label: string): Locator {
    return window.locator(`[data-testid="upcoming-item"][data-label="${label}"]`)
  }

  /** An envelope of the month after, by the category it carries. */
  upcomingEnvelope(name: string): Locator {
    return this.content.getByTestId(`upcoming-envelope-${name}`)
  }

  /** What is outside every envelope in the month after. */
  get upcomingUnbudgeted(): Locator {
    return this.content.getByTestId('upcoming-unbudgeted')
  }

  /** « Reste vers les projets »: the headline figure and its split. */
  get upcomingRemaining(): Locator {
    return this.content.getByTestId('upcoming-remaining')
  }
}
