import { useCallback, useState } from 'react'

/** One pair the detection found, or would find on a rehearsal. */
export interface DetectedTransferPair {
  transactionId: string
  counterpartId: string
  amountCents: number
  bookedAt: string
  counterpartBookedAt: string
  label: string
  counterpartLabel: string
}

export interface DetectResult {
  matched: number
  scanned: number
  dryRun: boolean
  pairs: DetectedTransferPair[]
}

/**
 * Runs the internal-transfer detection over the history, on demand.
 *
 * A rehearsal (`dryRun`) writes nothing and reports the pairs it would write:
 * four figures of the dashboard move with them, so the owner reads the lines
 * before confirming.
 */
export function useDetectInternalTransfers() {
  const [running, setRunning] = useState(false)

  const detect = useCallback(async (dryRun: boolean): Promise<DetectResult | null> => {
    setRunning(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/internal-transfers/detect', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ dryRun }),
      })
      if (!res.ok) {
        return null
      }

      return (await res.json()) as DetectResult
    } catch {
      return null
    } finally {
      setRunning(false)
    }
  }, [])

  return { detect, running }
}
