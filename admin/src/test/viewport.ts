/**
 * A window width, for a test that cares about one (MAG-38).
 *
 * jsdom implements no `matchMedia` at all, so MUI's `useMediaQuery` answers
 * `false` to everything — which happens to be the desktop branch, and is why
 * every test written before this one keeps passing untouched. A test that
 * wants the narrow branch installs a width here instead.
 *
 * Only `min-width` and `max-width` are understood; that is all
 * `theme.breakpoints` ever emits. Call `resetViewport()` in an `afterEach`:
 * the stub is global, and a width left behind would quietly rewrite the next
 * test in the file.
 */

const MAX_WIDTH = /\(max-width:\s*([\d.]+)px\)/
const MIN_WIDTH = /\(min-width:\s*([\d.]+)px\)/

function queryMatches(query: string, width: number): boolean {
  const max = MAX_WIDTH.exec(query)
  const min = MIN_WIDTH.exec(query)

  if (!max && !min) {
    return false
  }

  return (!max || width <= Number(max[1])) && (!min || width >= Number(min[1]))
}

export function setViewportWidth(width: number): void {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: queryMatches(query, width),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

export function resetViewport(): void {
  Reflect.deleteProperty(window, 'matchMedia')
}

/** The Playwright `phone` project's width — a Pixel 5 in portrait. */
export const PHONE_WIDTH = 393

/** The Playwright `tablet` project's width — the one react-admin got wrong. */
export const TABLET_WIDTH = 834

/** The Playwright `desktop` project's width. */
export const DESKTOP_WIDTH = 1440
