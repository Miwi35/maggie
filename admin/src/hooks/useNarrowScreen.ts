import useMediaQuery from '@mui/material/useMediaQuery'
import { NARROW_QUERY } from '../breakpoints'

/**
 * True when the window has room for one column only — under `md` (900px).
 *
 * The single place the app asks "phone or tablet?", so the answer cannot drift
 * between the menu, the chat and a page: see `src/breakpoints.ts` for why
 * 900px.
 *
 * Asks for the media query by hand rather than through `theme.breakpoints`,
 * which would make every caller need a `ThemeProvider` around it — unit tests
 * render these components bare, and `useMediaQuery(theme => …)` throws on a
 * null theme rather than falling back.
 *
 * `noSsr` because the first render has to be right. Without it MUI answers
 * `false` once and corrects itself in an effect, and the components that read
 * this pick their *initial* state from it — a chat panel that flashes open on
 * a phone before folding away.
 */
export function useNarrowScreen(): boolean {
  return useMediaQuery(NARROW_QUERY, { noSsr: true })
}
