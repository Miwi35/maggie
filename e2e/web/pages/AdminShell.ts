import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { adminUrl } from './routes.js'

/**
 * The frame every module page sits in: app bar, sidebar menu, chat panel.
 *
 * The menu is the reason this is a page object rather than a handful of
 * selectors. React-admin folds the sidebar away below MUI's `md` breakpoint
 * (900px), so on the `tablet` and `phone` projects the menu only exists once
 * the AppBar toggle has been pressed, and several entries are inside collapsed
 * groups. {@link navigateTo} handles both, which is what lets one journey run
 * at all three widths.
 *
 * Entries are addressed by role rather than by a container: the top-level
 * links sit outside the `<List component="nav">` the collapsible groups live
 * in, so scoping to the navigation landmark would silently miss half of them.
 * `menuitem` is unambiguous page-wide — nothing else in the admin uses it.
 */
export class AdminShell {
  readonly appBar: Locator
  readonly sidebarToggle: Locator
  /**
   * The page itself, without the menu beside it or the chat panel after it.
   *
   * React-admin puts all three inside one `<main>`, so the landmark cannot
   * separate them — and Maggie quotes the page's own wording often enough that
   * an unscoped `getByText` matches her answer as well as the thing under
   * test. Every module page object narrows to this.
   */
  readonly content: Locator

  constructor(readonly page: Page) {
    this.appBar = page.getByRole('banner')
    this.content = page.getByTestId('page-content')
    // react-admin labels it from `ra.action.open_menu` / `close_menu`, which
    // ra-language-french renders as "Ouvrir le menu" / "Fermer le menu".
    this.sidebarToggle = this.appBar.getByRole('button', { name: /menu/i })
  }

  async goto(route = '/'): Promise<void> {
    await this.page.goto(adminUrl(route))
  }

  /** The shell is up: the app bar rendered, so the auth check passed and the resources loaded. */
  async expectLoaded(): Promise<void> {
    await expect(this.appBar).toBeVisible()
  }

  /**
   * Makes the menu reachable whatever the width. Idempotent.
   *
   * Keyed on a collapsible group rather than on "Tableau de bord": a folded
   * sidebar still renders the top-level links as bare icons, accessible name
   * and all, so testing for one of those reads "already open" while half the
   * menu is missing and the icons sit under the app bar. The groups only exist
   * when `useSidebarState()` says the sidebar is expanded — which is exactly
   * the state a journey needs. React-admin refolds it after every navigation
   * on a narrow viewport, so this runs before each click.
   */
  async openMenu(): Promise<void> {
    const expanded = this.menuGroup('Courses')

    if (await expanded.isVisible()) {
      return
    }

    await this.sidebarToggle.click()
    await expect(expanded).toBeVisible()
  }

  menuItem(label: string): Locator {
    return this.page.getByRole('menuitem', { name: label, exact: true })
  }

  /** The collapsible headings — "Courses", "Nutrition", "Finance", "Données brutes", "Paramètres". */
  menuGroup(label: string): Locator {
    return this.page.getByRole('button', { name: label, exact: true })
  }

  /**
   * Clicks a menu entry, expanding the groups it is nested under first.
   *
   * @param groups outermost first, e.g. `navigateTo('Événements', 'Données brutes', 'Agenda')`
   */
  async navigateTo(label: string, ...groups: string[]): Promise<void> {
    await this.openMenu()

    for (const [index, group] of groups.entries()) {
      const heading = this.menuGroup(group)
      await expect(heading).toBeVisible()

      // What this group has to reveal: the next heading down, or the entry
      // itself for the innermost one. Groups keep their state for the life of
      // the page, so testing that rather than clicking blindly is what stops a
      // second visit from collapsing what it came to open.
      const revealed = index + 1 < groups.length ? this.menuGroup(groups[index + 1]) : this.menuItem(label)

      if (!(await revealed.isVisible())) {
        await heading.click()
      }

      await expect(revealed).toBeVisible()
    }

    await this.menuItem(label).click()
  }
}
