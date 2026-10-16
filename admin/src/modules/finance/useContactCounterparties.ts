import { useCallback, useEffect, useRef, useState } from 'react'
import {
  fetchCounterparties,
  fetchCounterpartySuggestions,
  linkCounterparty,
  unlinkCounterparty,
} from './contactsApi'
import type { ContactCounterparty, CounterpartySuggestion } from './contactsApi'

const LOAD_ERROR = 'Impossible de lire ses comptes.'

/**
 * A contact's labels and the ones that may be theirs, read together: linking a
 * suggestion moves it from one list to the other. Writes are not optimistic —
 * both lists are what the API answers after the write, and a refusal rejects
 * with the API's message for the caller to show.
 */
export function useContactCounterparties(contactId: string) {
  const [counterparties, setCounterparties] = useState<ContactCounterparty[] | null>(null)
  const [suggestions, setSuggestions] = useState<CounterpartySuggestion[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Only the latest read may land: a slow answer must not bring a removed label back.
  const latestRead = useRef(0)

  const load = useCallback(async () => {
    const read = ++latestRead.current
    const current = () => read === latestRead.current
    try {
      const [linked, maybe] = await Promise.all([
        fetchCounterparties(contactId),
        // The suggestions are a bonus: their failure must not hide the labels.
        fetchCounterpartySuggestions(contactId).catch(() => [] as CounterpartySuggestion[]),
      ])
      if (current()) {
        setCounterparties(linked)
        setSuggestions(maybe)
        setError(null)
      }
    } catch (e) {
      if (current()) setError(e instanceof Error ? e.message : LOAD_ERROR)
    }
  }, [contactId])

  useEffect(() => {
    void load()
  }, [load])

  const write = useCallback(
    async (action: () => Promise<void>) => {
      setBusy(true)
      try {
        await action()
        await load()
      } finally {
        setBusy(false)
      }
    },
    [load],
  )

  const add = useCallback((label: string) => write(() => linkCounterparty(contactId, label)), [write, contactId])
  const remove = useCallback((counterpartyId: string) => write(() => unlinkCounterparty(counterpartyId)), [write])

  return { counterparties, suggestions, error, busy, add, remove, reload: load }
}
