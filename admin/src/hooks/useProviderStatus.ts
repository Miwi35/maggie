import { useEffect, useState } from 'react'

export type ProviderStatus = 'ok' | 'credit' | 'auth'

export const PROVIDER_POLL_MS = 5 * 60 * 1000

const parse = (body: unknown): ProviderStatus => {
  const status = (body as { status?: unknown } | null)?.status
  return status === 'credit' || status === 'auth' ? status : 'ok'
}

/** Whether Maggie can reach the model (MAG-727): read on open and on return to the foreground, every 5 minutes while down. */
export function useProviderStatus(): ProviderStatus {
  const [status, setStatus] = useState<ProviderStatus>('ok')

  useEffect(() => {
    let active = true

    const read = () => {
      const token = localStorage.getItem('token')
      fetch('/agent/provider-status', { headers: token ? { Authorization: `Bearer ${token}` } : {} })
        .then((res) => (res.ok ? res.json() : null))
        .then((body) => {
          if (active && body) setStatus(parse(body))
        })
        .catch(() => {})
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') read()
    }

    read()
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') read()
    }, PROVIDER_POLL_MS)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      active = false
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  return status
}
