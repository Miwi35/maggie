import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { plannedMealId } from '../helpers/api.js'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

export type MealSlot = 'lunch' | 'dinner'

const SLOT_LABELS: Record<MealSlot, string> = { lunch: 'Déjeuner', dinner: 'Dîner' }

/**
 * Cuisine → Repas (MAG-628): one page, the week as a Menu — a section per day,
 * a card per meal — or as a Liste, the same meals as rows.
 *
 * A card is `meal-<id>`; the days and the rows carry their own prefixes, which
 * is why the cards are told apart here and not by a bare `meal-` selector.
 */
export class MealsPage extends AdminShell {
  readonly menuView: Locator
  readonly listView: Locator
  readonly cards: Locator

  constructor(page: Page) {
    super(page)
    this.menuView = this.content.getByRole('button', { name: 'Menu', exact: true })
    this.listView = this.content.getByRole('button', { name: 'Liste', exact: true })
    this.cards = this.content.locator('[data-testid^="meal-"]:not([data-testid^="meal-day-"]):not([data-testid^="meal-row-"])')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.meals)
    await this.expectMenuView()
  }

  async expectMenuView(): Promise<void> {
    await expect(this.menuView).toHaveAttribute('aria-pressed', 'true')
    await expect(this.day(0)).toBeVisible()
  }

  /** A day of the week, Monday being 0. */
  day(index: number): Locator {
    return this.content.getByTestId(`meal-day-${index}`)
  }

  /** The card of the meal that has this recipe, on a week that may hold other meals. */
  cardWith(recipeName: string): Locator {
    return this.cards.filter({ hasText: recipeName })
  }

  cardOf(mealId: string): Locator {
    return this.content.getByTestId(`meal-${mealId}`)
  }

  /** « Ajouter un repas » on a day, the slot chosen: the dialog, ready for a recipe. */
  async openAddDialog(dayIndex: number, slot: MealSlot): Promise<Locator> {
    await this.day(dayIndex).getByRole('button', { name: '+ Ajouter un repas' }).click()
    const dialog = this.page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: 'Ajouter un repas' })).toBeVisible()

    const slotSelect = dialog.getByRole('combobox', { name: /Créneau/ })
    if (!(await slotSelect.textContent())?.includes(SLOT_LABELS[slot])) {
      await slotSelect.click()
      await this.page.getByRole('option', { name: SLOT_LABELS[slot] }).click()
    }

    return dialog
  }

  /** Picks the recipe in the dialog, found by the beginning of its name. */
  async pickRecipe(dialog: Locator, search: string, recipeName: string): Promise<void> {
    await dialog.getByLabel('Menu').fill(search)
    await this.page.getByRole('option', { name: recipeName }).click()
  }

  /** Plans one recipe the way the owner does, and returns the id the API gave the meal. */
  async plan(dayIndex: number, slot: MealSlot, recipeName: string): Promise<string> {
    const dialog = await this.openAddDialog(dayIndex, slot)
    await this.pickRecipe(dialog, recipeName, recipeName)

    return plannedMealId(this.page, () => dialog.getByRole('button', { name: 'Créer' }).click())
  }
}
