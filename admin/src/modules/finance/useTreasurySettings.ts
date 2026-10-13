import { useCallback, useState } from 'react'

/** The day of the month the salary is expected, 1 to 31; `null` forgets it. */
export type SalaryExpectedDay = number | null

export const isValidSalaryDay = (day: number): boolean => Number.isInteger(day) && day >= 1 && day <= 31

/**
 * The owner's treasury settings. Written through `PATCH /api/finance/treasury-settings`,
 * which reads the field present in the body and leaves the others alone.
 */
export function useTreasurySettings() {
  const [saving, setSaving] = useState(false)

  const saveSalaryExpectedDay = useCallback(async (day: SalaryExpectedDay): Promise<boolean> => {
    setSaving(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/treasury-settings', {
        method: 'PATCH',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ salaryExpectedDay: day }),
      })

      return res.ok
    } catch {
      return false
    } finally {
      setSaving(false)
    }
  }, [])

  return { saving, saveSalaryExpectedDay }
}
