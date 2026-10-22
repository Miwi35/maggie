import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'

/** The day every card of the Accueil shows. */
export type HomeDay = 'today' | 'tomorrow'

export const HOME_DAYS: readonly { value: HomeDay; label: string }[] = [
  { value: 'today', label: "Aujourd'hui" },
  { value: 'tomorrow', label: 'Demain' },
]

/** The selector lives in the URL (`/?day=tomorrow`), so a reload or a shared link keeps it; anything else reads as today. */
export function useHomeDay(): [HomeDay, (day: HomeDay) => void] {
  const [params, setParams] = useSearchParams()
  const day: HomeDay = params.get('day') === 'tomorrow' ? 'tomorrow' : 'today'

  const setDay = useCallback(
    (next: HomeDay) =>
      setParams(
        (current) => {
          const updated = new URLSearchParams(current)
          if (next === 'today') updated.delete('day')
          else updated.set('day', next)

          return updated
        },
        // Switching the day is not a page: the back button must leave the Accueil.
        { replace: true },
      ),
    [setParams],
  )

  return [day, setDay]
}
