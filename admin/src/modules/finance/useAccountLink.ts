import { useCallback, useEffect, useState } from 'react'

/** An account a bank created that a hand-typed account can be linked to (MAG-823). */
export interface LinkCandidate {
  id: string
  name: string
  bank: string | null
  type: string
  currency: string
  balanceCents: number
}

export interface LinkPreview {
  kept: string
  moved: number
  dropped: number
}

const LINK_ERROR = 'Impossible de rattacher le compte'

const request = (path: string, init: { method?: string; body?: unknown } = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    method: init.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(init.body !== undefined ? { body: JSON.stringify(init.body) } : {}),
  })
}

/** The refusal of the API as it words it, or the generic failure. */
const refusal = async (res: Response): Promise<string> => {
  try {
    const body = (await res.json()) as { error?: string }
    return body.error || LINK_ERROR
  } catch {
    return LINK_ERROR
  }
}

/**
 * Linking a hand-typed account to the one a bank created: the candidates
 * (read only when [enabled]), the preview of one, and the link itself.
 */
export function useAccountLink(accountId: string | undefined, enabled: boolean) {
  const [candidates, setCandidates] = useState<LinkCandidate[]>([])
  const [error, setError] = useState<string | null>(null)

  const loadCandidates = useCallback(async () => {
    if (!accountId || !enabled) {
      setCandidates([])
      return
    }
    try {
      const res = await request(`/api/accounts/${accountId}/link-candidates`)
      if (!res.ok) return
      const body = (await res.json()) as { candidates?: LinkCandidate[] }
      setCandidates(Array.isArray(body.candidates) ? body.candidates : [])
    } catch {
      setCandidates([])
    }
  }, [accountId, enabled])

  useEffect(() => {
    void loadCandidates()
  }, [loadCandidates])

  const send = useCallback(
    async (bankAccountId: string, confirm: boolean): Promise<LinkPreview | null> => {
      try {
        const res = await request(`/api/accounts/${accountId}/link`, {
          method: 'POST',
          body: { bankAccountId, confirm },
        })
        if (!res.ok) {
          setError(await refusal(res))
          return null
        }
        setError(null)
        return (await res.json()) as LinkPreview
      } catch {
        setError(LINK_ERROR)
        return null
      }
    },
    [accountId],
  )

  const preview = useCallback((bankAccountId: string) => send(bankAccountId, false), [send])
  const confirm = useCallback(
    async (bankAccountId: string) => {
      const done = await send(bankAccountId, true)
      if (done) setCandidates([])
      return done !== null
    },
    [send],
  )
  const clearError = useCallback(() => setError(null), [])

  return { candidates, preview, confirm, error, clearError }
}
