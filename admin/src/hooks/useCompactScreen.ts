import useMediaQuery from '@mui/material/useMediaQuery'
import { COMPACT_QUERY } from '../breakpoints'

/**
 * True on a phone held upright — under `sm` (600px). `noSsr` for the reason
 * `useNarrowScreen` gives: the first render has to be the right one.
 */
export function useCompactScreen(): boolean {
  return useMediaQuery(COMPACT_QUERY, { noSsr: true })
}
