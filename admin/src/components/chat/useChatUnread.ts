import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeAgentFeed } from '../../hooks/agentFeed'
import { getStoredUserId } from '../../hooks/agentTopics'

const UNREAD_URL = '/agent/chat/unread'
const READ_URL = '/agent/chat/read'

export interface ChatUnread {
  lastReadMessageId: string | null
  unreadCount: number
}

export interface ChatUnreadState extends ChatUnread {
  /** True once the server has answered: before that the count says nothing. */
  loaded: boolean
  /** Moves the read position to this message, for every device (monotone on the server). */
  markRead: (messageId: string) => void
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** `{lastReadMessageId, unreadCount}`, or null for any other payload of the shared feed. */
export function readUnread(data: unknown): ChatUnread | null {
  if (typeof data !== 'object' || data === null || Array.isArray(data)) return null
  const { lastReadMessageId, unreadCount } = data as Record<string, unknown>
  if (typeof unreadCount !== 'number') return null
  return { lastReadMessageId: typeof lastReadMessageId === 'string' ? lastReadMessageId : null, unreadCount }
}

/**
 * Where the owner stopped reading, kept by the server so the phone and the admin agree:
 * loaded once, then followed over Mercure (`chat_read`).
 */
export function useChatUnread(): ChatUnreadState {
  const [state, setState] = useState<ChatUnread & { loaded: boolean }>({
    lastReadMessageId: null,
    unreadCount: 0,
    loaded: false,
  })
  const sentRef = useRef<string | null>(null)

  useEffect(() => {
    let live = true
    fetch(UNREAD_URL, { headers: authHeaders() })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        const unread = readUnread(body)
        // A Mercure update that came first is newer than this read.
        if (live && unread) setState((current) => (current.loaded ? current : { ...unread, loaded: true }))
      })
      .catch(() => {
        // The badge stays hidden: nothing worth stopping for.
      })
    return () => {
      live = false
    }
  }, [])

  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return
    return subscribeAgentFeed(userId, (raw) => {
      try {
        const unread = readUnread(JSON.parse(raw))
        if (unread) setState({ ...unread, loaded: true })
      } catch {
        // Not ours.
      }
    })
  }, [])

  const markRead = useCallback((messageId: string) => {
    if (sentRef.current === messageId) return
    sentRef.current = messageId
    fetch(READ_URL, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ messageId }),
    })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        const unread = readUnread(body)
        if (unread) setState({ ...unread, loaded: true })
      })
      .catch(() => {
        // Retried on the next message.
        sentRef.current = null
      })
  }, [])

  return { ...state, markRead }
}
