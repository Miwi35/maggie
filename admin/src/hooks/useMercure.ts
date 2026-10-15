import { useEffect, useRef } from 'react'
import { mercureUrl } from './mercureUrl'

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
export function useMercure(topics: string[], onMessage: (data?: string) => void): void {
  const onMessageRef = useRef(onMessage)
  useEffect(() => {
    onMessageRef.current = onMessage
  })

  useEffect(() => {
    const userId = getUserId()
    if (!userId || topics.length === 0) return

    const url = mercureUrl(
      MERCURE_URL,
      topics.map((topic) => `/users/${userId}${topic}`),
    )

    return subscribe(url.toString(), (data) => onMessageRef.current(data))
  }, [topics])
}

interface SharedStream {
  source: EventSource
  listeners: Set<(data?: string) => void>
}

const streams = new Map<string, SharedStream>()

/**
 * One EventSource per subscribe URL, however many components ask for it.
 *
 * Over HTTP/1.1 a browser keeps six connections per origin, and every open
 * stream holds one for good: a screen listening three times to the same topics
 * (its list, its header, its sheet) left the page unable to send any request
 * once the notification bell, the chat and the agent streams had taken theirs
 * (MAG-473).
 */
function subscribe(url: string, listener: (data?: string) => void): () => void {
  let stream = streams.get(url)
  if (!stream) {
    const listeners = new Set<(data?: string) => void>()
    const source = new EventSource(url, { withCredentials: true })
    source.onmessage = (event) => {
      for (const notify of [...listeners]) notify(event.data)
    }
    stream = { source, listeners }
    streams.set(url, stream)
  }

  const shared = stream
  shared.listeners.add(listener)

  return () => {
    shared.listeners.delete(listener)
    if (shared.listeners.size === 0) {
      shared.source.close()
      streams.delete(url)
    }
  }
}
