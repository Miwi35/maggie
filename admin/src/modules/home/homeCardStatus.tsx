import { createContext, useContext, useEffect } from 'react'

/**
 * What the Accueil needs to know of a card: only `empty` takes it off the page. A card still loading, or whose
 * read failed, is not empty — it stays, with its skeleton or its error (MAG-594).
 */
export type HomeCardStatus = 'loading' | 'error' | 'empty' | 'filled'

/** Where a card tells the Accueil its status; outside the Accueil (a card rendered alone) nobody listens. */
export const HomeCardStatusContext = createContext<(module: string, status: HomeCardStatus | null) => void>(() => {})

interface CardRead<T> {
  state: T | null
  loading: boolean
  error: boolean
}

/**
 * The status of a card from its read. `isEmpty` is the module's own definition of « nothing to show » for the
 * chosen day. A read in flight (the day just changed) or failed is never empty, whatever the previous state.
 */
export function homeCardStatus<T>({ state, loading, error }: CardRead<T>, isEmpty: (state: T) => boolean): HomeCardStatus {
  if (error) return 'error'
  if (loading || !state) return 'loading'

  return isEmpty(state) ? 'empty' : 'filled'
}

/** Tells the Accueil the card's status, and forgets it when the card goes. */
export function useReportHomeCard(module: string, status: HomeCardStatus): void {
  const report = useContext(HomeCardStatusContext)

  useEffect(() => {
    report(module, status)
  }, [report, module, status])

  useEffect(() => () => report(module, null), [report, module])
}
