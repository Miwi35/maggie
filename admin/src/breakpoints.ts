/**
 * Where the admin changes shape (MAG-38).
 *
 * One breakpoint carries the whole decision: **`md`, 900px**. Above it the app
 * is the three-column desk it has always been — menu, page, chat side by side.
 * Below it there is room for one column, so the menu and the chat become
 * drawers over the page and everything else gets a finger's worth of room.
 *
 * 900px rather than `sm` (600px) because of what sits beside the page: a 240px
 * menu and a 380px chat panel. On an 834px tablet in portrait — react-admin's
 * own cutoff leaves both open there — that is 620px of chrome around 214px of
 * content. The tablet is the width this ticket is really about; the phone was
 * merely more obviously broken.
 *
 * The scale itself is MUI's, written down rather than inherited silently: it
 * is the contract `useNarrowScreen`, `src/theme.ts` and the Playwright
 * projects (1440 / 834 / 393) share.
 *
 * Kept apart from `theme.ts` so that reading the breakpoint never costs a
 * theme: `useNarrowScreen` is called by components that unit tests render
 * without a `ThemeProvider`.
 */
export const BREAKPOINTS = { xs: 0, sm: 600, md: 900, lg: 1200, xl: 1536 } as const

/**
 * `theme.breakpoints.down('md')`, spelled out — MUI subtracts 5/100 of a pixel
 * so that `up('md')` and `down('md')` cannot both match at exactly 900px.
 */
export const NARROW_QUERY = `(max-width:${BREAKPOINTS.md - 5 / 100}px)`
