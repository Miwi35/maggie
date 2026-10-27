import { useEffect, useState } from 'react'

/** The most recent synchronisation among the bank connections; null while unknown or when none ever ran. */
export function useLastBankSync(): Date | null {
  const [syncedAt, setSyncedAt] = useState<Date | null>(null)

  useEffect(() => {
    let cancelled = false
    const token = localStorage.getItem('token')

    fetch('/api/finance/bank-connections', {
      headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
      .then((res) => (res.ok ? res.json() : null))
      .then((body: { connections?: { lastSyncedAt: string | null }[] } | null) => {
        if (cancelled || !body?.connections) return
        const times = body.connections
          .map((connection) => (connection.lastSyncedAt ? new Date(connection.lastSyncedAt).getTime() : NaN))
          .filter((time) => !Number.isNaN(time))
        setSyncedAt(times.length > 0 ? new Date(Math.max(...times)) : null)
      })
      .catch(() => {})

    return () => {
      cancelled = true
    }
  }, [])

  return syncedAt
}
