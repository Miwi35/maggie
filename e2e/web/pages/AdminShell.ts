import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { adminUrl } from './routes.js'

/**
 * The frame every module page sits in: app bar, sidebar menu, chat panel.
 *
 * The menu is the reason this is a page object rather than a handful of
 * selectors. React-admin folds the sidebar away below MUI's `md` breakpoint
 * (900px), so on the `tablet` and `phone` projects the menu only exists once
 * the AppBar toggle has been pressed, and the sub-entries only exist under the
 * current module. {@link navigateTo} handles both, which is what lets one
 * journey run at all three widths.
 *
 * Entries are addressed by role rather than by a container. `menuitem` is
 * unambiguous page-wide — nothing else in the admin uses it.
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

  /**
   * Loads an admin route — and really loads it, even when already there.
   *
   * The admin routes with a hash router, so `page.goto('/admin/#/calendar')`
   * from `/admin/#/calendar` is a same-document navigation: the document is
   * not fetched, nothing remounts, and every `useEffect` that fills a view
   * keeps the data it had. A journey that writes through the API and reopens
   * the page to see the result would then assert against the list the view
   * fetched *before* the write — and collections are served from
   * Elasticsearch, so "before" is exactly the state a journey is reopening to
   * get past. It cost MAG-100's Google journey a red check (see there).
   *
   * Reloading unconditionally would be the other half of the trap: the
   * real-time journeys hold a window open on purpose, and `expectRealtimeSync`
   * is built to fail if the observer navigates.
   */
  async goto(route = '/'): Promise<void> {
    const url = adminUrl(route)

    if (this.page.url().endsWith(url)) {
      await this.page.reload()

      return
    }

    await this.page.goto(url)
  }

  /** The shell is up: the app bar rendered, so the auth check passed and the resources loaded. */
  async expectLoaded(): Promise<void> {
    await expect(this.appBar).toBeVisible()
  }

  /**
   * Makes the menu reachable whatever the width. Idempotent.
   *
   * Keyed on the menu's header (Maggie's avatar and name) rather than on an
   * entry: a folded sidebar still renders the top-level links as bare icons,
   * accessible name and all, so testing for one of those reads "already open"
   * while the sub-entries are missing and the icons sit under the app bar. The
   * header only exists when `useSidebarState()` says the sidebar is expanded —
   * which is exactly the state a journey needs. React-admin refolds it after
   * every navigation on a narrow viewport, so this runs before each click.
   */
  async openMenu(): Promise<void> {
    const expanded = this.page.getByTestId('menu-header')

    if (await expanded.isVisible()) {
      return
    }

    await this.sidebarToggle.click()
    await expect(expanded).toBeVisible()
  }

  menuItem(label: string): Locator {
    return this.page.getByRole('menuitem', { name: label, exact: true })
  }

  /**
   * Clicks a menu entry.
   *
   * The sidebar is flat (`charte/Admin-Cible`, MAG-620): the modules are always
   * there, their sub-entries only under the module the page belongs to. An
   * entry nested in a module is reached by opening that module first.
   *
   * @param module the module the entry sits under, e.g. `navigateTo('Événements', 'Réglages')`
   */
  async navigateTo(label: string, module?: string): Promise<void> {
    await this.openMenu()

    if (module === undefined) {
      await this.menuItem(label).click()

      return
    }

    await this.menuItem(module).click()
    await this.openMenu()

    // Last: a sub-entry may carry the name of a module (« Tâches » under
    // Réglages), and the sub-entries are drawn after their module.
    await this.menuItem(label).last().click()
  }
}
