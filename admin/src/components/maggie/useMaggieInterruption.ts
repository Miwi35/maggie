import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeAgentFeed } from '../../hooks/agentFeed'
import { getStoredUserId } from '../../hooks/agentTopics'
import { readFeedMessage, type Interruption } from './interruptions'

/** How long « Plus tard » waits before Maggie speaks again. */
export const LATER_DELAY_MS = 10 * 60 * 1000

interface Options {
  chatOpen: boolean
  onOpenChat: () => void
}

/**
 * Everything Maggie says unprompted, one at a time (MAG-311): the user's
 * notifications — reminders, proactions, a task due, anything raised on her
 * own initiative — the questions she puts to the user (an action waiting for an
 * answer), and the proactions that finish with an answer. They queue up and are
 * shown in the order they arrive, the next one after « Plus tard » or the
 * action; none is ever shown twice.
 *
 * An open chat already shows a proaction's answer, so that one is neither shown
 * nor queued behind it. It also shows the actions waiting for an answer, as cards
 * (MAG-6): those wait in the queue while it is open, and interrupt if it closes
 * with the question still unanswered. Nothing else is in the chat: a notification
 * interrupts over it too.
 */
export function useMaggieInterruption({ chatOpen, onOpenChat }: Options) {
  const [queue, setQueue] = useState<Interruption[]>([])
  const seen = useRef(new Set<string>())
  const timers = useRef(new Map<number, Interruption>())
  // Read by the stream handler, which outlives any one render.
  const chatOpenRef = useRef(chatOpen)
  useEffect(() => {
    chatOpenRef.current = chatOpen
  }, [chatOpen])

  const clearTimers = useCallback((only?: (item: Interruption) => boolean) => {
    timers.current.forEach((item, timer) => {
      if (only && !only(item)) return
      window.clearTimeout(timer)
      timers.current.delete(timer)
    })
  }, [])

  const enqueue = useCallback((interruption: Interruption) => {
    if (seen.current.has(interruption.id)) return
    seen.current.add(interruption.id)
    if (interruption.source === 'proaction' && chatOpenRef.current) return
    setQueue((prev) => [...prev, interruption])
  }, [])

  const withdraw = useCallback(
    (id: string) => {
      setQueue((prev) => prev.filter((item) => item.id !== id))
      clearTimers((item) => item.id === id)
    },
    [clearTimers],
  )

  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return

    return subscribeAgentFeed(userId, (raw) => {
      const event = readFeedMessage(raw)
      if (!event) return
      if (event.kind === 'withdraw') withdraw(event.id)
      else enqueue(event.interruption)
    })
  }, [enqueue, withdraw])

  // The questions already waiting when the admin opens: nothing is published for them.
  useEffect(() => {
    if (!getStoredUserId()) return
    const controller = new AbortController()
    const token = localStorage.getItem('token')

    fetch('/agent/approvals?status=pending', {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: controller.signal,
    })
      .then((response) => (response.ok ? response.json() : []))
      .then((actions: unknown) => {
        if (!Array.isArray(actions)) return
        for (const action of actions) {
          const event = readFeedMessage(JSON.stringify(action))
          if (event?.kind === 'interrupt') enqueue(event.interruption)
        }
      })
      .catch(() => {
        // Offline or signed out: the feed will bring the next ones.
      })

    return () => controller.abort()
  }, [enqueue])

  // The chat opening answers every pending proaction: it shows them. Adjusted
  // while rendering, as in Layout, so no stale interruption paints once more.
  const [wasChatOpen, setWasChatOpen] = useState(chatOpen)
  if (wasChatOpen !== chatOpen) {
    setWasChatOpen(chatOpen)
    if (chatOpen) setQueue((prev) => prev.filter((item) => item.source !== 'proaction'))
  }

  useEffect(() => {
    if (chatOpen) clearTimers((item) => item.source === 'proaction')
  }, [chatOpen, clearTimers])

  useEffect(() => () => clearTimers(), [clearTimers])

  const current = queue.find((item) => !(chatOpen && item.source === 'approval')) ?? null

  const dismiss = useCallback(
    (later: boolean) => {
      if (!current) return
      setQueue((prev) => prev.filter((item) => item.id !== current.id))
      if (!later) return
      const timer = window.setTimeout(() => {
        timers.current.delete(timer)
        setQueue((prev) => [...prev, current])
      }, LATER_DELAY_MS)
      timers.current.set(timer, current)
    },
    [current],
  )

  const openChat = useCallback(() => {
    dismiss(false)
    onOpenChat()
  }, [dismiss, onOpenChat])

  return { current, dismiss, openChat }
}
