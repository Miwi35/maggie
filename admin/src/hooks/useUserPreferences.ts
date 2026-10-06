import { useCallback, useEffect, useState } from 'react'

export interface UserPreference {
  id: string
  theme: string
  locale: string
  timezone: string
  defaultCalendarView: string
  // Absent from the API answer while it is unset.
  defaultCity?: string | null
  enabledAgendaIds: string[]
  notificationsEnabled: boolean
}

function apiFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(path, {
    ...options,
    headers: {
      Accept: 'application/ld+json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

export function useUserPreferences() {
  const [preferences, setPreferences] = useState<UserPreference | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await apiFetch('/api/user_preferences/me')
      if (res.ok) {
        setPreferences(await res.json())
      }
    } catch {
      // silently ignore
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const updatePreference = useCallback(
    async (patch: Partial<Omit<UserPreference, 'id'>>): Promise<boolean> => {
      try {
        const res = await apiFetch('/api/user_preferences/me', {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/merge-patch+json' },
          body: JSON.stringify(patch),
        })
        if (res.ok) {
          setPreferences(await res.json())
        }
        return res.ok
      } catch {
        return false
      }
    },
    [],
  )

  return { preferences, updatePreference, loading, refresh }
}
