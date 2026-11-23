import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeAgentFeed } from '../../hooks/agentFeed'
import { getStoredUserId } from '../../hooks/agentTopics'
import { playChime } from './chime'
import { readFeedMessage, type Interruption } from './interruptions'

/** How long « Plus tard » waits before Maggie speaks again. */
export const LATER_DELAY_MS = 10 * 60 * 1000

/** A message the hub replays later than this is no news: it does not ring. */
export const MESSAGE_CHIME_WINDOW_MS = 60_000

/** What `setTimeout` can wait for: past it the delay wraps and fires at once. */
const MAX_TIMEOUT_MS = 2 ** 31 - 1

interface Options {
  chatOpen: boolean
  /** The chat is in front, under the user's eyes: defaults to `chatOpen`. */
  chatFocused?: boolean
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
 *
 * A message of Maggie (`message`, MAG-923) never interrupts: it rings, the portrait counts it unread,
 * unless the chat is in front, where it is marked read at once. The notifications left unread
 * are read again when the admin loads (owner, 22 Nov.).
 */
function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** The chat shows this message already: it is read, the bell keeps no entry for it. */
function markRead(ref: string) {
  fetch(ref, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json', ...authHeaders() },
    body: JSON.stringify({ readAt: new Date().toISOString() }),
  }).catch(() => {})
}

export function useMaggieInterruption({ chatOpen, chatFocused = chatOpen, onOpenChat }: Options) {
  const [queue, setQueue] = useState<Interruption[]>([])
  const seen = useRef(new Set<string>())
  const timers = useRef(new Map<number, Interruption>())
  // The moment an interruption stops being worth saying, by its id (MAG-679).
  const expiryTimers = useRef(new Map<string, number>())
  // Read by the stream handler, which outlives any one render.
  const chatOpenRef = useRef(chatOpen)
  useEffect(() => {
    chatOpenRef.current = chatOpen
  }, [chatOpen])
  const chatFocusedRef = useRef(chatFocused)
  useEffect(() => {
    chatFocusedRef.current = chatFocused
  }, [chatFocused])

  const clearTimers = useCallback((only?: (item: Interruption) => boolean) => {
    timers.current.forEach((item, timer) => {
      if (only && !only(item)) return
      window.clearTimeout(timer)
      timers.current.delete(timer)
    })
  }, [])

  const withdraw = useCallback(
    (id: string) => {
      setQueue((prev) => prev.filter((item) => item.id !== id))
      clearTimers((item) => item.id === id)
      window.clearTimeout(expiryTimers.current.get(id))
      expiryTimers.current.delete(id)
    },
    [clearTimers],
  )

  const enqueue = useCallback(
    (interruption: Interruption) => {
      if (seen.current.has(interruption.id)) return
      seen.current.add(interruption.id)
      if (interruption.source === 'proaction' && chatOpenRef.current) return
      setQueue((prev) => [...prev, interruption])

      // Whether shown, waiting its turn or postponed, it goes when it expires.
      const expiresAt = interruption.source === 'notification' ? interruption.expiresAt : undefined
      if (expiresAt === undefined) return
      const delay = expiresAt - Date.now()
      if (delay > MAX_TIMEOUT_MS) return
      expiryTimers.current.set(
        interruption.id,
        window.setTimeout(() => withdraw(interruption.id), Math.max(delay, 0)),
      )
    },
    [withdraw],
  )

  const ring = useCallback((message: { id: string; ref: string; createdAt: number | null }) => {
    if (seen.current.has(message.id)) return
    seen.current.add(message.id)
    if (chatFocusedRef.current) {
      markRead(message.ref)
      return
    }
    if (message.createdAt !== null && Date.now() - message.createdAt > MESSAGE_CHIME_WINDOW_MS) return
    playChime()
  }, [])

  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return

    return subscribeAgentFeed(userId, (raw) => {
      const event = readFeedMessage(raw)
      if (!event) return
      if (event.kind === 'withdraw') withdraw(event.id)
      else if (event.kind === 'message') ring(event)
      else enqueue(event.interruption)
    })
  }, [enqueue, ring, withdraw])

  // The questions already waiting when the admin opens: nothing is published for them.
  useEffect(() => {
    if (!getStoredUserId()) return
    const controller = new AbortController()

    fetch('/agent/approvals?status=pending', { headers: authHeaders(), signal: controller.signal })
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

    // The notifications left unread while the admin was closed: Mercure does not replay them.
    fetch('/api/notifications?exists[readAt]=false&order[createdAt]=asc&itemsPerPage=30', {
      headers: { Accept: 'application/ld+json', ...authHeaders() },
      signal: controller.signal,
    })
      .then((response) => (response.ok ? response.json() : null))
      .then((body: unknown) => {
        const member = (body as { member?: unknown } | null)?.member
        if (!Array.isArray(member)) return
        for (const item of member) {
          const event = readFeedMessage(JSON.stringify(item))
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

  useEffect(() => {
    const expiries = expiryTimers.current
    return () => {
      clearTimers()
      expiries.forEach((timer) => window.clearTimeout(timer))
      expiries.clear()
    }
  }, [clearTimers])

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

  return { current, dismiss, openChat, withdraw }
}
