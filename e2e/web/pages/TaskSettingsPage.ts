import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The urgencies that alert, label by stored value — `low` never does. */
export const URGENCIES = { critical: 'Critique', high: 'Haute', normal: 'Normale' } as const

export type Urgency = keyof typeof URGENCIES

/** A day chip: `J-7`, `J-3`, `J-2`, `J-1` or `Jour J`. */
export type DayChip = 'J-7' | 'J-3' | 'J-2' | 'J-1' | 'Jour J'

/** The three order signals, label by stored group. */
export const LEVEL_GROUPS = { timePressure: "L'échéance", criticality: 'La gravité', priority: 'Ma priorité' } as const

export type LevelGroup = keyof typeof LEVEL_GROUPS

/** Every change saves itself: runs the action and waits for the API to accept the PUT. */
async function savedBy(page: Page, action: () => Promise<void>): Promise<void> {
  const put = page.waitForResponse(
    (response) => response.url().endsWith('/api/task-settings/me') && response.request().method() === 'PUT',
  )
  await action()
  const response = await put
  expect(response.ok(), `PUT /api/task-settings/me answered ${response.status()}`).toBe(true)
}

/**
 * Settings → Tâches (MAG-407): the morning time, then one block per urgency —
 * toggle chips for the days before the due date and a select for overdue
 * tasks. Saved on every change.
 */
export class TaskSettingsPage extends AdminShell {
  readonly heading: Locator
  readonly morningTime: Locator
  readonly defaultsButton: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Tâches', exact: true })
    this.morningTime = this.content.getByLabel('Heure du matin')
    this.defaultsButton = this.content.getByRole('button', { name: 'Valeurs par défaut' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.taskSettings)
    await this.expectReady()
  }

  async expectReady(): Promise<void> {
    await expect(this.heading).toBeVisible()
    await expect(this.morningTime).toBeVisible()
  }

  /** A chip; `aria-pressed` says whether it is on. */
  day(urgency: Urgency, chip: DayChip): Locator {
    return this.content.getByRole('button', { name: `${URGENCIES[urgency]} — ${chip}`, exact: true })
  }

  overdue(urgency: Urgency): Locator {
    return this.content.getByLabel(`${URGENCIES[urgency]} — en retard`)
  }

  /** The time is saved when the field is left. */
  async setMorningTime(time: string): Promise<void> {
    await savedBy(this.page, async () => {
      await this.morningTime.fill(time)
      await this.morningTime.blur()
    })
  }

  async toggleDay(urgency: Urgency, chip: DayChip): Promise<void> {
    await savedBy(this.page, () => this.day(urgency, chip).click())
  }
}

/**
 * Settings → Ce qui passe en premier (MAG-407): three five-level sliders and a
 * preview of five real tasks in the resulting order. Saved on every change.
 */
export class TaskOrderSettingsPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Ce qui passe en premier', exact: true })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.taskOrderSettings)
    await this.expectReady()
  }

  async expectReady(): Promise<void> {
    await expect(this.heading).toBeVisible()
    await expect(this.level('criticality')).toBeAttached()
  }

  /** A slider; its `aria-valuetext` is the level's word (« Très peu » … « Énormément »). */
  level(group: LevelGroup): Locator {
    return this.content.getByRole('slider', { name: LEVEL_GROUPS[group], exact: true })
  }

  /** Pushes a slider to its top level, « Énormément », from the keyboard. */
  async setToMax(group: LevelGroup): Promise<void> {
    await savedBy(this.page, async () => {
      await this.level(group).focus()
      await this.page.keyboard.press('End')
    })
  }
}
