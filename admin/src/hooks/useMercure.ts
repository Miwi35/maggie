import { useEffect } from 'react'

const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL

function getUserId(): string | null {
  try {
    const raw = localStorage.getItem('user')
    return raw ? JSON.parse(raw).id : null
  } catch {
    return null
  }
}

/**
 * Subscribe to user-scoped Mercure topics.
 *
 * Topics are prefixed with `/users/{userId}` so each user only receives
 * updates for their own resources.
 *
 * @param topics  Resource topic patterns, e.g. ['/api/recipes/{id}']
 * @param onMessage  Called on every SSE message
 */
export function useMercure(topics: string[], onMessage: () => void): void {
  useEffect(() => {
    const userId = getUserId()
    if (!userId || topics.length === 0) return

    const url = new URL(MERCURE_URL)
    for (const topic of topics) {
      url.searchParams.append('topic', `/users/${userId}${topic}`)
    }

    const es = new EventSource(url.toString())
    es.onmessage = () => onMessage()
    return () => es.close()
  }, [topics, onMessage])
}
