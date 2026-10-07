import { useCallback, useEffect, useRef, useState } from 'react'
import { AGENT_STREAMS, agentTopic, getStoredUserId } from '../../hooks/agentTopics'
import { mercureUrl } from '../../hooks/mercureUrl'

// May be relative in production ('/.well-known/mercure'): mercureUrl() resolves it.
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

/** How long « Plus tard » waits before Maggie speaks again. */
export const LATER_DELAY_MS = 10 * 60 * 1000

export interface Interruption {
  id: string
  message: string
}

interface Options {
  chatOpen: boolean
  onOpenChat: () => void
}

/**
 * Maggie's spontaneous words, from the user's `proactions` stream: a finished
 * proaction with an answer interrupts, one at a time. Only proactions trigger
 * it — the `approvals` cards are MAG-5's.
 */
export function useMaggieInterruption({ chatOpen, onOpenChat }: Options) {
  const [queue, setQueue] = useState<Interruption[]>([])
  const seen = useRef(new Set<string>())
  const timers = useRef(new Set<number>())
  // Read by the stream handler, which outlives any one render.
  const chatOpenRef = useRef(chatOpen)
  useEffect(() => {
    chatOpenRef.current = chatOpen
  }, [chatOpen])

  const clearTimers = useCallback(() => {
    timers.current.forEach((timer) => window.clearTimeout(timer))
    timers.current.clear()
  }, [])

  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return
    const url = mercureUrl(MERCURE_URL, [agentTopic(AGENT_STREAMS.proactions, userId)])

    const eventSource = new EventSource(url.toString(), { withCredentials: true })
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.status !== 'completed' || typeof data.response !== 'string' || !data.response.trim()) return
        if (!data.id || seen.current.has(data.id)) return
        seen.current.add(data.id)
        // The open chat already shows it: nothing to interrupt, nothing to queue.
        if (chatOpenRef.current) return
        setQueue((prev) => [...prev, { id: data.id, message: data.response }])
      } catch {
        // Ignore malformed messages
      }
    }

    return () => eventSource.close()
  }, [])

  // The chat opening by itself answers every pending interruption. Adjusted
  // while rendering, as in Layout, so no stale interruption paints once more.
  const [wasChatOpen, setWasChatOpen] = useState(chatOpen)
  if (wasChatOpen !== chatOpen) {
    setWasChatOpen(chatOpen)
    if (chatOpen) setQueue([])
  }

  useEffect(() => {
    if (chatOpen) clearTimers()
  }, [chatOpen, clearTimers])

  useEffect(() => clearTimers, [clearTimers])

  const current = queue[0] ?? null

  const dismiss = useCallback(
    (later: boolean) => {
      if (!current) return
      setQueue((prev) => prev.filter((item) => item.id !== current.id))
      if (!later) return
      const timer = window.setTimeout(() => {
        timers.current.delete(timer)
        setQueue((prev) => [...prev, current])
      }, LATER_DELAY_MS)
      timers.current.add(timer)
    },
    [current],
  )

  const openChat = useCallback(() => {
    clearTimers()
    dismiss(false)
    onOpenChat()
  }, [clearTimers, dismiss, onOpenChat])

  return { current, dismiss, openChat }
}
