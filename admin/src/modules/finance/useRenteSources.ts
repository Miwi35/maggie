import { useCallback, useEffect, useRef, useState } from 'react'
import type { RenteSourceInvestment, RenteSources } from './useFinanceDashboard'

/**
 * What the independence projection counts beyond the measured rentes (MAG-558), read from
 * `GET /api/finance/independence`. A read that fails keeps what is on screen: the line it feeds is
 * an addition, never a reason to block the page.
 */
export function useRenteSources() {
  const [sources, setSources] = useState<RenteSources | null>(null)
  const ticket = useRef(0)

  const reload = useCallback(async () => {
    const mine = ++ticket.current
    try {
      const token = localStorage.getItem('token')
      const response = await fetch('/api/finance/independence', {
        headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      })
      if (!response.ok) return
      const body = (await response.json()) as { renteSources?: RenteSources }
      if (mine === ticket.current && body.renteSources?.investments) setSources(body.renteSources)
    } catch {
      // keep the previous figures
    }
  }, [])

  useEffect(() => {
    reload()

    return () => {
      ticket.current++
    }
  }, [reload])

  return { sources, reload }
}

/** The investment's item in the projection, by project id; null when it counts for nothing. */
export const renteOfInvestment = (sources: RenteSources | null, projectId: string): RenteSourceInvestment | null =>
  sources?.investments.items.find((item) => item.projectId === projectId) ?? null
