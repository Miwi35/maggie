import { useEffect, useState } from 'react'
import { apiFetch } from './healthProfileApi'
import { DAILY_ENERGY_PATH } from './dailyEnergySeries'
import type { DailyEnergy } from './dailyEnergySeries'

export type DailyEnergyState = { status: 'loading' } | { status: 'error' } | { status: 'ready'; rows: DailyEnergy[] }

/** The daily energy of `from`..`to`, read again when the range changes. */
export function useDailyEnergy(from: string, to: string): DailyEnergyState {
  const key = `${from}/${to}`
  const [answer, setAnswer] = useState<{ key: string; state: DailyEnergyState } | null>(null)

  useEffect(() => {
    let cancelled = false
    apiFetch(`${DAILY_ENERGY_PATH}?from=${from}&to=${to}`)
      .then(async (res) => {
        if (!res.ok) throw new Error(String(res.status))
        const rows = (await res.json()) as DailyEnergy[]
        if (!cancelled) setAnswer({ key, state: { status: 'ready', rows } })
      })
      .catch(() => {
        if (!cancelled) setAnswer({ key, state: { status: 'error' } })
      })

    return () => {
      cancelled = true
    }
  }, [from, to, key])

  return answer?.key === key ? answer.state : { status: 'loading' }
}
