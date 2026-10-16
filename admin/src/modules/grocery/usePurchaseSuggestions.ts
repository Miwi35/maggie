import { useCallback, useEffect, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import { fetchSuggestions, snoozeSuggestion } from './purchaseSuggestions'
import type { PurchaseSuggestion } from './purchaseSuggestions'

/**
 * The products the owner buys regularly and is due again (MAG-495). It holds
 * no Mercure stream of its own: the list screen already shares one for the
 * list, the recurring items, the products and the errands, and calls `reload`.
 * A failed read leaves the banner as it was — it is a hint, not the list.
 */
export function usePurchaseSuggestions() {
  const notify = useNotify()
  const [suggestions, setSuggestions] = useState<PurchaseSuggestion[]>([])
  // Only the last read answers: an older one finishing late would bring back a row just dealt with.
  const latest = useRef(0)

  const reload = useCallback(async () => {
    const request = ++latest.current
    try {
      const next = await fetchSuggestions()
      if (request === latest.current) setSuggestions(next)
    } catch {
      // Non-blocking: the banner keeps what it showed.
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  /** The row leaves at once, then the API says what is still due. */
  const forget = useCallback(
    (suggestion: PurchaseSuggestion) => {
      setSuggestions((prev) => prev.filter((s) => s.product.id !== suggestion.product.id))
      reload()
    },
    [reload],
  )

  const snooze = useCallback(
    async (suggestion: PurchaseSuggestion) => {
      const failed = () => notify(`Erreur : « ${suggestion.product.name} » n'a pas été écarté`, { type: 'error' })
      try {
        const response = await snoozeSuggestion(suggestion.product.id)
        if (!response.ok) {
          failed()
          return
        }
        forget(suggestion)
      } catch {
        failed()
      }
    },
    [forget, notify],
  )

  return { suggestions, reload, forget, snooze }
}
