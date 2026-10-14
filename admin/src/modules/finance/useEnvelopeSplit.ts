import { useCallback, useState } from 'react'

export interface SplitChild {
  envelopeId: string
  amountCents: number
}

export type SplitOutcome = { ok: true } | { ok: false; refused: boolean }

/** Saves how a mother envelope is shared between her daughters, all at once. */
export function useEnvelopeSplit() {
  const [saving, setSaving] = useState(false)

  const save = useCallback(
    async (envelopeId: string, children: SplitChild[]): Promise<SplitOutcome> => {
      setSaving(true)
      try {
        const token = localStorage.getItem('token')
        const res = await fetch(`/api/finance/envelopes/${envelopeId}/split`, {
          method: 'PUT',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ children }),
        })

        // 400: the API refused and changed nothing.
        return res.ok ? { ok: true } : { ok: false, refused: res.status === 400 }
      } catch {
        return { ok: false, refused: false }
      } finally {
        setSaving(false)
      }
    },
    [],
  )

  return { save, saving }
}
