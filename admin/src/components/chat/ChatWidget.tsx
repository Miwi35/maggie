import { useState, useEffect, useLayoutEffect, useRef, useCallback, forwardRef, useImperativeHandle, Fragment } from 'react'
import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import InputAdornment from '@mui/material/InputAdornment'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Portal from '@mui/material/Portal'
import Snackbar from '@mui/material/Snackbar'
import Divider from '@mui/material/Divider'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import SendIcon from '@mui/icons-material/Send'
import CloseIcon from '@mui/icons-material/Close'
import MicIcon from '@mui/icons-material/Mic'
import StopIcon from '@mui/icons-material/Stop'
import SearchIcon from '@mui/icons-material/Search'
import ForumOutlinedIcon from '@mui/icons-material/ForumOutlined'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import PersonIcon from '@mui/icons-material/Person'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import ChatIcon from '@mui/icons-material/Chat'
import PsychologyIcon from '@mui/icons-material/Psychology'
import CircularProgress from '@mui/material/CircularProgress'
import { alpha, keyframes } from '@mui/material/styles'
import { useVoiceRecorder } from '../../hooks/useVoiceRecorder'
import { useTranscription } from '../../hooks/useTranscription'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { subscribeAgentFeed } from '../../hooks/agentFeed'
import { AGENT_STREAMS, agentTopic, getStoredUserId } from '../../hooks/agentTopics'
import { mercureUrl } from '../../hooks/mercureUrl'
import { saidInMessage } from '../../screenContext'
import { useAgUiStream } from '../../hooks/useAgUiStream'
import { newMessageKey } from '../../hooks/messageKey'
import { ActivityPulse } from '../mind/ActivityPulse'
import { MaggieAvatar } from '../maggie/MaggieAvatar'
import { ToolCallList } from '../mind/ToolCallList'
import { ChatBubble } from './ChatBubble'
import { ThreadsDialog } from './ThreadsDialog'
import { VoiceOrb } from './VoiceOrb'
import { NARROW_QUERY } from '../../breakpoints'
import type { SxProps, Theme } from '@mui/material/styles'
import type { SidebarTab } from '../layout/ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  /** The thread it belongs to — what a thread's deletion takes out of the chat (MAG-342). */
  contextId?: string | null
}

/** A deletion the owner can still take back: nothing is sent to the server before the delay is over. */
type PendingDeletion =
  | { kind: 'thread'; context: ContextState; index: number; messages: ChatMessage[]; timer: ReturnType<typeof setTimeout> }
  | { kind: 'message'; message: ChatMessage; timer: ReturnType<typeof setTimeout> }

interface Notice {
  text: string
  /** True while « Annuler » is on offer, i.e. while a deletion is pending. */
  undo: boolean
}

const UNDO_DELAY_MS = 6000
const DELETE_FAILED = 'La suppression a échoué. Réessayez.'

const MESSAGES_URL = '/agent/messages'
// May be relative in production ('/.well-known/mercure'): mercureUrl() resolves
// it against the current origin — a bare `new URL()` throws and the subscription
// dies, which is how b16916d reached production once already.
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
const SIDEBAR_WIDTH = 380
const PAGE_SIZE = 20
const PANEL_RADIUS = 20
const REDUCED_MOTION = '@media (prefers-reduced-motion: reduce)'

const panelIn = keyframes`
  from { transform: translateX(32px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
`

const pillFieldSx = (theme: Theme) => ({
  borderRadius: '999px',
  bgcolor: theme.palette.veilleuse.raised,
  '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' },
  '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: alpha(theme.palette.primary.main, 0.3) },
  '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: theme.palette.primary.main, borderWidth: 1 },
})

const STATE_LABELS = { idle: 'En ligne', thinking: 'Réfléchit…', acting: 'Agit…' } as const

// Round, quiet buttons for the header and the input row; 44px under `md` for a finger.
const roundButtonSx = (raised: boolean) => (theme: Theme) => ({
  width: 36,
  height: 36,
  bgcolor: raised ? theme.palette.veilleuse.raised : 'transparent',
  color: 'text.secondary',
  '&:hover': { bgcolor: theme.palette.veilleuse.raised, color: 'text.primary' },
  '&.Mui-disabled': { bgcolor: raised ? theme.palette.veilleuse.raised : 'transparent', opacity: 0.5 },
  [`@media ${NARROW_QUERY}`]: { width: 44, height: 44 },
})

interface ChatWidgetProps {
  open: boolean
  sidebarTab: SidebarTab
  onTabChange: (event: unknown, newTab: SidebarTab) => void
  onClose: () => void
  onUnread: () => void
  agentState: AgentState
  onAgentStateChange: (state: AgentState) => void
  contexts: ContextState[]
  onContextsChange: (contexts: ContextState[]) => void
  toolCalls: ToolCallState[]
  onToolCallsChange: (toolCalls: ToolCallState[]) => void
}

export interface ChatWidgetRef {
  sendMessage: (text: string) => void
}

/** Messages back in their place, whatever arrived meanwhile; each id once. */
function mergeMessages(current: ChatMessage[], restored: ChatMessage[]): ChatMessage[] {
  const known = new Set(current.map((m) => m.id))
  const missing = restored.filter((m) => !known.has(m.id))
  if (missing.length === 0) return current
  return [...current, ...missing].sort((a, b) => {
    const delta = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
    return Number.isNaN(delta) ? 0 : delta
  })
}

/** A thread updated: its position and its message count are kept, the rest is replaced. */
function upsertContext(list: ContextState[], ctx: ContextState): ContextState[] {
  const previous = list.find((c) => c.id === ctx.id)
  if (!previous) return [ctx, ...list]
  // Updates published over Mercure and the stream carry no count; the one fetched stays.
  return list.map((c) => (c.id === ctx.id ? { ...ctx, messageCount: ctx.messageCount ?? previous.messageCount } : c))
}

function getAuthHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return ''
  }
}

function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function formatDayLabel(date: Date): string {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const diffDays = Math.round((today.getTime() - target.getTime()) / (1000 * 60 * 60 * 24))

  if (diffDays === 0) return "Aujourd'hui"
  if (diffDays === 1) return 'Hier'
  if (diffDays < 7) {
    return date.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })
  }
  return date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' })
}

function getTimeSeparatorLabel(prev: ChatMessage | null, current: ChatMessage): string | null {
  const currentDate = new Date(current.createdAt)
  if (isNaN(currentDate.getTime())) return null

  if (!prev) {
    return formatDayLabel(currentDate) + ' ' + formatTime(currentDate)
  }

  const prevDate = new Date(prev.createdAt)
  if (isNaN(prevDate.getTime())) return null

  if (!isSameDay(prevDate, currentDate)) {
    return formatDayLabel(currentDate) + ' ' + formatTime(currentDate)
  }

  const diffMs = currentDate.getTime() - prevDate.getTime()
  if (diffMs > 15 * 60 * 1000) {
    return formatTime(currentDate)
  }

  return null
}

export const ChatWidget = forwardRef<ChatWidgetRef, ChatWidgetProps>(
  ({ open, sidebarTab, onTabChange, onClose, onUnread, agentState, onAgentStateChange, contexts, onContextsChange, toolCalls, onToolCallsChange }, ref) => {
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [input, setInput] = useState('')
    const [loadingHistory, setLoadingHistory] = useState(false)
    const [hasMore, setHasMore] = useState(true)
    const [highlightId, setHighlightId] = useState<string | null>(null)
    // « The list follows the latest message »: true until the user scrolls up on purpose.
    const [following, setFollowingState] = useState(true)
    const followingRef = useRef(true)
    const setFollowing = useCallback((value: boolean) => {
      followingRef.current = value
      setFollowingState(value)
    }, [])
    const [unreadFromId, setUnreadFromId] = useState<string | null>(null)
    const [tappedId, setTappedId] = useState<string | null>(null)
    const [streamedId, setStreamedId] = useState<string | null>(null)

    // Threads list and deferred deletions (MAG-342)
    const [threadsOpen, setThreadsOpen] = useState(false)
    const [notice, setNotice] = useState<Notice | null>(null)
    const pendingRef = useRef<PendingDeletion | null>(null)

    // Streaming state
    const [streamingText, setStreamingText] = useState('')
    const [streamingMsgId, setStreamingMsgId] = useState<string | null>(null)
    const streamedMessageIdRef = useRef<string | null>(null)
    const streamingTextRef = useRef('')

    // The last message sent, with its key; and, when the agent could not be reached, the
    // same pair as a state of that message — not a message of Maggie's (MAG-363).
    const lastSendRef = useRef<{ text: string; key: string } | null>(null)
    const [failedSend, setFailedSend] = useState<{ text: string; key: string } | null>(null)

    // Search state
    const [searchMode, setSearchMode] = useState(false)
    const [searchQuery, setSearchQuery] = useState('')
    const [searchResults, setSearchResults] = useState<ChatMessage[]>([])
    const [searchLoading, setSearchLoading] = useState(false)

    const messagesContainerRef = useRef<HTMLDivElement | null>(null)
    // The list can mount after the panel does (a narrow screen's Drawer mounts its
    // content a render late), so the effects below also wait for the node itself.
    const [listNode, setListNode] = useState<HTMLDivElement | null>(null)
    const attachList = useCallback((node: HTMLDivElement | null) => {
      messagesContainerRef.current = node
      setListNode(node)
    }, [])
    const historyLoadedRef = useRef(false)
    const lastScrollRef = useRef({ top: 0, height: 0 })
    const skipOpenPinRef = useRef(false)
    const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    const prevOpenRef = useRef(open)

    const recorder = useVoiceRecorder()
    const transcription = useTranscription()
    const isNarrow = useNarrowScreen()

    // AG-UI stream callbacks
    const toolCallsRef = useRef<ToolCallState[]>([])
    const contextsRef = useRef<ContextState[]>([])
    // The thread the run in progress was routed to: what the messages it produces belong
    // to, which the history only says after a reload (MAG-342).
    const runContextIdRef = useRef<string | null>(null)

    const agUiStream = useAgUiStream({
      onRunStarted: () => {
        runContextIdRef.current = null
        onAgentStateChange('thinking')
        // Keep last 10 completed tool calls as activity history
        const recent = toolCallsRef.current
          .filter((tc) => tc.status !== 'running')
          .slice(-10)
        toolCallsRef.current = recent
        onToolCallsChange(recent)
      },
      onRunFinished: () => {
        onAgentStateChange('idle')
      },
      onTextStart: (messageId) => {
        setStreamingMsgId(messageId)
        setStreamingText('')
        streamingTextRef.current = ''
        streamedMessageIdRef.current = messageId
        onAgentStateChange('thinking')
      },
      onTextDelta: (_mid, delta) => {
        streamingTextRef.current += delta
        setStreamingText((prev) => prev + delta)
      },
      onTextEnd: (messageId) => {
        // Finalize: move streaming text into messages array
        // Read from ref to avoid impure side effects in state updaters (breaks in StrictMode)
        const finalText = streamingTextRef.current
        if (finalText.trim()) {
          // The agent stores the answer under the id it streamed it with, so the
          // Mercure echo — which may land before this point — is the same message.
          setMessages((prev) =>
            prev.some((m) => m.id === messageId)
              ? prev
              : [
                  ...prev,
                  {
                    id: messageId,
                    role: 'assistant',
                    content: finalText,
                    createdAt: new Date().toISOString(),
                    contextId: runContextIdRef.current,
                  },
                ],
          )
        }
        streamingTextRef.current = ''
        setStreamingText('')
        setStreamingMsgId(null)
        setStreamedId(messageId)
      },
      onToolCallStart: (toolCallId, toolName) => {
        onAgentStateChange('acting')
        const newTc: ToolCallState = { toolCallId, toolName, status: 'running' }
        toolCallsRef.current = [...toolCallsRef.current, newTc]
        onToolCallsChange(toolCallsRef.current)
      },
      onToolCallEnd: (toolCallId) => {
        // Status will be updated by onToolResult
        toolCallsRef.current = toolCallsRef.current.map((tc) =>
          tc.toolCallId === toolCallId && tc.status === 'running' ? { ...tc, status: 'success' } : tc,
        )
        onToolCallsChange(toolCallsRef.current)
        onAgentStateChange('thinking')
      },
      onToolResult: (toolCallId, _toolName, status) => {
        toolCallsRef.current = toolCallsRef.current.map((tc) =>
          tc.toolCallId === toolCallId ? { ...tc, status: status as ToolCallState['status'] } : tc,
        )
        onToolCallsChange(toolCallsRef.current)
      },
      onContextUpdate: (value) => {
        // The question just sent was stored before it was routed, so nothing has told this
        // tab which thread it joined — and a thread's deletion has to take it out.
        const routedTo = (value.id as string | undefined) ?? null
        runContextIdRef.current = routedTo
        if (routedTo) {
          setMessages((prev) => {
            const at = prev.findLastIndex((m) => m.role === 'user' && !m.contextId)
            return at === -1 ? prev : prev.map((m, i) => (i === at ? { ...m, contextId: routedTo } : m))
          })
        }
        const ctx: ContextState = {
          id: value.id as string,
          label: value.label as string,
          status: value.status as ContextState['status'],
          // Carried by the event rather than kept from what is on screen: the whole
          // context is replaced below, so a missing summary here erases the line the
          // panel is showing (MAG-11).
          summary: (value.summary as string | null) ?? null,
        }
        // A thread whose deletion is waiting for its delay stays out of the list.
        const pending = pendingRef.current
        if (pending?.kind === 'thread' && pending.context.id === ctx.id) return
        contextsRef.current = upsertContext(contextsRef.current, ctx)
        onContextsChange([...contextsRef.current])
      },
      onError: (message) => {
        console.error('Stream error:', message)
        setFailedSend(lastSendRef.current)
        onAgentStateChange('idle')
      },
    })

    // --- Fetch contexts on mount ---

    // Closed threads included: without them there would be no way to clean one up.
    const loadContexts = useCallback(async () => {
      try {
        const res = await fetch('/agent/contexts?includeClosed=true', { headers: getAuthHeaders() })
        if (res.ok) {
          const data = await res.json()
          const pending = pendingRef.current
          const waiting = pending?.kind === 'thread' ? pending.context.id : null
          const mapped: ContextState[] = data
            .map(
              (c: { id: string; label: string; status: string; summary?: string | null; messageCount?: number }) => ({
                id: c.id,
                label: c.label,
                status: c.status as ContextState['status'],
                summary: c.summary ?? null,
                messageCount: c.messageCount,
              }),
            )
            .filter((c: ContextState) => c.id !== waiting)
          contextsRef.current = mapped
          onContextsChange(mapped)
        }
      } catch (e) {
        console.error('Failed to load contexts:', e)
      }
    }, [onContextsChange])

    useEffect(() => {
      loadContexts()
    }, [loadContexts])

    // --- Fetch helpers ---

    const fetchMessages = useCallback(async (params: Record<string, string> = {}) => {
      const url = new URL(MESSAGES_URL, window.location.origin)
      for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v)
      const res = await fetch(url.toString(), { headers: getAuthHeaders() })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return (await res.json()) as ChatMessage[]
    }, [])

    // --- Initial history load ---

    useEffect(() => {
      if (!open || historyLoadedRef.current) return
      historyLoadedRef.current = true

      const load = async () => {
        setLoadingHistory(true)
        try {
          const history = await fetchMessages({ limit: String(PAGE_SIZE) })
          setMessages(history)
          setHasMore(history.length >= PAGE_SIZE)

          const lastReadId = localStorage.getItem('chat_lastReadMessageId')
          if (lastReadId) {
            const idx = history.findIndex((m) => m.id === lastReadId)
            if (idx >= 0 && idx < history.length - 1) {
              setUnreadFromId(history[idx + 1].id)
            }
          }
        } catch (e) {
          console.error('Failed to load chat history:', e)
        } finally {
          setLoadingHistory(false)
        }
      }
      load()
    }, [open, fetchMessages])

    // --- Keep the list on the latest message ---

    const pinToBottom = useCallback(() => {
      const container = messagesContainerRef.current
      if (!container) return
      container.scrollTop = container.scrollHeight
      lastScrollRef.current = { top: container.scrollTop, height: container.scrollHeight }
    }, [])

    // Opening the panel, coming back to the Chat tab or leaving the search all start
    // from the bottom, except a jump to a quoted message (it set `skipOpenPinRef`).
    useLayoutEffect(() => {
      if (!open || sidebarTab !== 'chat' || searchMode) return
      if (skipOpenPinRef.current) {
        skipOpenPinRef.current = false
        return
      }
      setFollowing(true)
      pinToBottom()
    }, [open, sidebarTab, searchMode, pinToBottom, setFollowing])

    // A list that mounts after the open-pin ran (a narrow screen's Drawer) still gets
    // its pin, unless the user is not following: a jump to a quoted message is not.
    useLayoutEffect(() => {
      if (listNode && followingRef.current && !searchMode) pinToBottom()
    }, [listNode, searchMode, pinToBottom])

    // Whatever grows the list — a message, the answer line by line, the history —
    // keeps the end in view while the user has not scrolled up. Instant, never smooth:
    // an animation still running when the next line lands is what left the answer
    // below the fold.
    useLayoutEffect(() => {
      if (followingRef.current && !searchMode) pinToBottom()
    }, [messages, streamingText, agentState, loadingHistory, searchMode, pinToBottom])

    // A keyboard, a card or a resized window changes the height without any message.
    useEffect(() => {
      const container = listNode
      if (!container || typeof ResizeObserver === 'undefined') return
      const observer = new ResizeObserver(() => {
        if (followingRef.current) pinToBottom()
      })
      observer.observe(container)
      return () => observer.disconnect()
    }, [open, sidebarTab, searchMode, listNode, pinToBottom])

    // --- Infinite scroll: load older messages ---

    const loadOlderMessages = useCallback(async () => {
      if (loadingHistory || !hasMore || messages.length === 0) return
      const oldestId = messages[0]?.id
      if (!oldestId || oldestId.startsWith('tmp-')) return

      setLoadingHistory(true)
      const container = messagesContainerRef.current
      const prevScrollHeight = container?.scrollHeight ?? 0

      try {
        const older = await fetchMessages({ before: oldestId, limit: String(PAGE_SIZE) })
        if (older.length < PAGE_SIZE) setHasMore(false)
        if (older.length > 0) {
          setMessages((prev) => [...older, ...prev])
          // Restore scroll position after prepend
          requestAnimationFrame(() => {
            if (container) {
              container.scrollTop += container.scrollHeight - prevScrollHeight
            }
          })
        }
      } catch (e) {
        console.error('Failed to load older messages:', e)
      } finally {
        setLoadingHistory(false)
      }
    }, [loadingHistory, hasMore, messages, fetchMessages])

    const handleScroll = useCallback(() => {
      const container = messagesContainerRef.current
      if (!container) return

      // Only the user moves the list up (our own scrolls go down, and a list that
      // shrinks lowers scrollTop by itself): that is what stops the following.
      const distFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight
      const last = lastScrollRef.current
      const movedUp = container.scrollTop < last.top - 1 && container.scrollHeight >= last.height
      lastScrollRef.current = { top: container.scrollTop, height: container.scrollHeight }
      if (movedUp) setFollowing(false)
      else if (distFromBottom < 100) setFollowing(true)

      // Load older when near top
      if (container.scrollTop < 50 && !searchMode) {
        loadOlderMessages()
      }
    }, [loadOlderMessages, searchMode, setFollowing])

    // --- Track chat close → save last read to localStorage ---

    useEffect(() => {
      if (prevOpenRef.current && !open && messages.length > 0) {
        const lastMsg = messages[messages.length - 1]
        if (lastMsg && !lastMsg.id.startsWith('tmp-')) {
          localStorage.setItem('chat_lastReadMessageId', lastMsg.id)
        }
        setUnreadFromId(null)
      }
      prevOpenRef.current = open
    }, [open, messages])

    // --- Clear unread breakline when user scrolls to bottom ---

    useEffect(() => {
      if (following && unreadFromId) {
        setUnreadFromId(null)
        const lastMsg = messages[messages.length - 1]
        if (lastMsg && !lastMsg.id.startsWith('tmp-')) {
          localStorage.setItem('chat_lastReadMessageId', lastMsg.id)
        }
      }
    }, [following, unreadFromId, messages])

    // --- Send message ---

    const sendMessageDirect = useCallback(
      async (text: string) => {
        if (!text.trim() || agUiStream.isStreaming) return

        const userMessage = text.trim()
        const tmpId = `tmp-${Date.now()}`
        setMessages((prev) => [
          ...prev,
          { id: tmpId, role: 'user', content: userMessage, createdAt: new Date().toISOString() },
        ])
        setFollowing(true)

        const key = newMessageKey()
        lastSendRef.current = { text: userMessage, key }
        setFailedSend(null)
        await agUiStream.send(userMessage, key)
      },
      [agUiStream, setFollowing],
    )

    const retryFailedSend = useCallback(async () => {
      if (!failedSend || agUiStream.isStreaming) return
      lastSendRef.current = failedSend
      setFailedSend(null)
      setFollowing(true)
      onAgentStateChange('thinking')
      await agUiStream.send(failedSend.text, failedSend.key)
    }, [failedSend, agUiStream, setFollowing, onAgentStateChange])

    useImperativeHandle(
      ref,
      () => ({
        sendMessage: (text: string) => {
          sendMessageDirect(text)
        },
      }),
      [sendMessageDirect],
    )

    // --- Mercure SSE subscription (with dedup) ---

    useEffect(() => {
      const userId = getStoredUserId()
      if (!userId) return
      const url = mercureUrl(MERCURE_URL, [agentTopic(AGENT_STREAMS.chat, userId)])

      const eventSource = new EventSource(url.toString(), { withCredentials: true })
      eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.deleted) {
            // A deletion made here, on the phone or by a clean-up: the optimistic removal
            // of our own already took them out, so this is a no-op for it.
            const ids: string[] = Array.isArray(data.messageIds) ? data.messageIds : []
            const contextId: string | null = data.contextId ?? null
            setMessages((prev) => {
              const kept = prev.filter((m) => !ids.includes(m.id) && !(contextId && m.contextId === contextId))
              return kept.length === prev.length ? prev : kept
            })
            return
          }
          if (data.id && data.content) {
            setMessages((prev) => {
              // Dedup: skip if already in list by real ID. A message this tab streamed
              // learns its thread from the echo, which is what a thread's deletion needs.
              const known = prev.findIndex((m) => m.id === data.id)
              if (known !== -1) {
                if (prev[known].contextId || !data.contextId) return prev
                return prev.map((m, i) => (i === known ? { ...m, contextId: data.contextId } : m))
              }
              // An answer streamed here carries its stored id already; only the question
              // this tab sent holds a temp id (tmp-*) until its echo gives it the real one.
              const tempIdx =
                data.role === 'user'
                  ? prev.findIndex((m) => m.id.startsWith('tmp-') && m.role === 'user' && m.content.trim() === data.content.trim())
                  : -1
              if (tempIdx !== -1) {
                // Replace the temp id with the real persisted id
                return prev.map((m, i) =>
                  i === tempIdx ? { ...m, id: data.id, contextId: data.contextId ?? m.contextId ?? null } : m,
                )
              }
              // Genuinely new message (other device or proactive agent)
              return [
                ...prev,
                {
                  id: data.id,
                  role: data.role || 'assistant',
                  content: data.content,
                  createdAt: data.createdAt || new Date().toISOString(),
                  contextId: data.contextId ?? null,
                },
              ]
            })
            if (data.role !== 'user' && (!open || sidebarTab !== 'chat')) {
              onUnread()
            }
          }
        } catch {
          // Ignore malformed messages
        }
      }

      return () => eventSource.close()
    }, [open, sidebarTab, onUnread])

    // --- Mercure SSE subscription for context updates ---

    useEffect(() => {
      const userId = getStoredUserId()
      if (!userId) return

      return subscribeAgentFeed(userId, (raw) => {
        try {
          const data = JSON.parse(raw)
          if (data.deleted && data.id) {
            if (contextsRef.current.some((c) => c.id === data.id)) {
              contextsRef.current = contextsRef.current.filter((c) => c.id !== data.id)
              onContextsChange([...contextsRef.current])
            }
            return
          }
          if (data.id && data.label) {
            const ctx: ContextState = {
              id: data.id,
              label: data.label,
              status: data.status as ContextState['status'],
              // A summary is written in the background, after the stream closed — so
              // this is the only path that ever brings one to an open panel (MAG-11).
              summary: data.summary ?? null,
            }
            const pending = pendingRef.current
            if (pending?.kind === 'thread' && pending.context.id === ctx.id) return
            contextsRef.current = upsertContext(contextsRef.current, ctx)
            onContextsChange([...contextsRef.current])
          }
        } catch {
          // Ignore malformed messages
        }
      })
    }, [onContextsChange])

    // --- Deleting a thread or a message, undoable (MAG-342) ---
    //
    // The row leaves the screen at once and « Annuler » stays on offer for a few seconds;
    // only when that delay is over is the DELETE sent (react-admin's undoable mode). One
    // deletion waits at a time: starting another sends the previous one first.

    const restorePending = useCallback(
      (pending: PendingDeletion) => {
        if (pending.kind === 'thread') {
          if (!contextsRef.current.some((c) => c.id === pending.context.id)) {
            const next = [...contextsRef.current]
            next.splice(Math.min(pending.index, next.length), 0, pending.context)
            contextsRef.current = next
            onContextsChange([...next])
          }
          setMessages((prev) => mergeMessages(prev, pending.messages))
        } else {
          setMessages((prev) => mergeMessages(prev, [pending.message]))
        }
      },
      [onContextsChange],
    )

    const commitPending = useCallback(async () => {
      const pending = pendingRef.current
      if (!pending) return
      pendingRef.current = null
      clearTimeout(pending.timer)
      setNotice((current) => (current?.undo ? null : current))

      const path =
        pending.kind === 'thread'
          ? `/agent/contexts/${encodeURIComponent(pending.context.id)}`
          : `/agent/messages/${encodeURIComponent(pending.message.id)}`
      try {
        const res = await fetch(path, { method: 'DELETE', headers: getAuthHeaders() })
        // 404: already gone — deleted from another device in the meantime.
        if (!res.ok && res.status !== 404) throw new Error(`HTTP ${res.status}`)
      } catch (e) {
        console.error('Failed to delete', path, e)
        restorePending(pending)
        setNotice({ text: DELETE_FAILED, undo: false })
      }
    }, [restorePending])

    const commitPendingRef = useRef(commitPending)
    useEffect(() => {
      commitPendingRef.current = commitPending
    }, [commitPending])

    // Leaving the page must not drop a deletion the owner asked for.
    useEffect(() => () => void commitPendingRef.current(), [])

    const scheduleDeletion = (
      draft: { kind: 'thread'; context: ContextState; index: number; messages: ChatMessage[] } | { kind: 'message'; message: ChatMessage },
      text: string,
    ) => {
      void commitPending()
      const timer = setTimeout(() => void commitPending(), UNDO_DELAY_MS)
      pendingRef.current = { ...draft, timer }
      setNotice({ text, undo: true })
    }

    const undoDeletion = () => {
      const pending = pendingRef.current
      if (!pending) return
      pendingRef.current = null
      clearTimeout(pending.timer)
      restorePending(pending)
      setNotice(null)
    }

    const deleteThread = (context: ContextState) => {
      const index = contextsRef.current.findIndex((c) => c.id === context.id)
      const removed = messages.filter((m) => m.contextId === context.id)
      contextsRef.current = contextsRef.current.filter((c) => c.id !== context.id)
      onContextsChange([...contextsRef.current])
      setMessages((prev) => prev.filter((m) => m.contextId !== context.id))
      scheduleDeletion({ kind: 'thread', context, index: Math.max(index, 0), messages: removed }, 'Fil supprimé')
    }

    const deleteMessage = (message: ChatMessage) => {
      setMessages((prev) => prev.filter((m) => m.id !== message.id))
      scheduleDeletion({ kind: 'message', message }, 'Message supprimé')
    }

    const openThreads = () => {
      setThreadsOpen(true)
      // The counts the confirmation quotes have to be fresh: Mercure updates carry none.
      void loadContexts()
    }

    // --- Search ---

    useEffect(() => {
      if (!searchMode || !searchQuery.trim()) {
        setSearchResults([])
        return
      }

      if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current)

      searchTimeoutRef.current = setTimeout(async () => {
        setSearchLoading(true)
        try {
          const url = new URL(`${MESSAGES_URL}/search`, window.location.origin)
          url.searchParams.set('q', searchQuery.trim())
          url.searchParams.set('limit', '20')
          const res = await fetch(url.toString(), { headers: getAuthHeaders() })
          if (res.ok) {
            setSearchResults(await res.json())
          }
        } catch (e) {
          console.error('Search error:', e)
        } finally {
          setSearchLoading(false)
        }
      }, 300)

      return () => {
        if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current)
      }
    }, [searchQuery, searchMode])

    const navigateToMessage = useCallback(
      async (messageId: string) => {
        try {
          const url = new URL(`${MESSAGES_URL}/context`, window.location.origin)
          url.searchParams.set('around', messageId)
          const res = await fetch(url.toString(), { headers: getAuthHeaders() })
          if (!res.ok) return

          const data = await res.json()
          setMessages(data.messages)
          setFollowing(false)
          skipOpenPinRef.current = true
          setSearchMode(false)
          setSearchQuery('')
          setSearchResults([])
          setHasMore(true) // Context may not start from the beginning

          // Highlight and scroll to target
          setHighlightId(messageId)
          setTimeout(() => setHighlightId(null), 2000)

          requestAnimationFrame(() => {
            const el = document.getElementById(`msg-${messageId}`)
            el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
          })
        } catch (e) {
          console.error('Navigate to message error:', e)
        }
      },
      [setFollowing],
    )

    const scrollToBottom = useCallback(() => {
      setFollowing(true)
      pinToBottom()
    }, [setFollowing, pinToBottom])

    // --- UI handlers ---

    const sendMessage = useCallback(() => {
      sendMessageDirect(input)
      setInput('')
    }, [input, sendMessageDirect])

    const handleKeyDown = (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendMessage()
      }
    }

    const handleMicClick = async () => {
      if (recorder.state === 'recording') {
        const blob = await recorder.stopRecording()
        // Into the input field, where the owner reads and edits it before sending:
        // a dictation meant to be written, so the cleanup is allowed (MAG-222).
        const text = await transcription.transcribe(blob, 'auto')
        if (text) {
          setInput((prev) => (prev ? prev + ' ' + text : text))
        }
        recorder.resetState()
      } else {
        await recorder.startRecording()
      }
    }

    const openSearch = () => {
      setSearchMode(true)
      setSearchQuery('')
      setSearchResults([])
    }

    const closeSearch = () => {
      setSearchMode(false)
      setSearchQuery('')
      setSearchResults([])
    }

    const isTranscribing = recorder.state === 'processing' || transcription.loading
    const isLoading = agUiStream.isStreaming

    // The message left without an answer: the mention goes under it, and goes away as soon
    // as something from Maggie lands after it.
    const unansweredId = (() => {
      if (!failedSend) return null
      const at = messages.map((m) => m.role === 'user' && m.content.trim() === failedSend.text).lastIndexOf(true)
      if (at === -1 || messages.slice(at + 1).some((m) => m.role !== 'user')) return null
      return messages[at].id
    })()
    const voiceError = recorder.error || transcription.error

    // --- Render ---

    // Below `md` the conversation is a sheet over the whole window instead of
    // a column beside the page (MAG-38): 380px of chat next to a 393px phone
    // leaves nothing left to talk *about*. Only the two wrappers change —
    // same panel, same tabs, same `chat-panel` handle the journeys scope to —
    // and the app bar's chat button is what brings the sheet back.
    //
    // The wide pair is the original: a column in the flex row that collapses
    // to nothing, with the panel itself fixed to the right edge. Translated
    // off that edge is not gone, though — the panel kept a box and a place in
    // the focus order — so it is hidden outright once it has slid away.
    const outerSx: SxProps<Theme> = isNarrow
      ? { height: '100%', display: 'flex', flexDirection: 'column' }
      : {
          width: open ? SIDEBAR_WIDTH : 0,
          flexShrink: 0,
          transition: 'width 225ms cubic-bezier(0, 0, 0.2, 1)',
        }

    const innerSx: SxProps<Theme> = isNarrow
      ? { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', bgcolor: 'maggie.panel' }
      : (theme: Theme) => ({
          width: SIDEBAR_WIDTH,
          position: 'fixed',
          top: theme.mixins.toolbar.minHeight,
          right: 0,
          bottom: 0,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          borderRadius: `${PANEL_RADIUS}px 0 0 ${PANEL_RADIUS}px`,
          bgcolor: theme.palette.maggie.panel,
          boxShadow: `inset 0 0 0 1px ${alpha(theme.palette.primary.main, 0.2)}`,
          transform: open ? 'translateX(0)' : `translateX(${SIDEBAR_WIDTH}px)`,
          visibility: open ? 'visible' : 'hidden',
          transition: open
            ? 'transform 225ms cubic-bezier(0, 0, 0.2, 1)'
            : 'transform 225ms cubic-bezier(0, 0, 0.2, 1), visibility 0s 225ms',
          ...(open && { animation: `${panelIn} 460ms cubic-bezier(0.2, 0.9, 0.3, 1) both` }),
          [REDUCED_MOTION]: { animation: 'none' },
        })

    const panel = (
      <Box data-testid="chat-panel" sx={outerSx}>
        <Box sx={innerSx}>
          {/* Header */}
          <Box sx={{ flexShrink: 0 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: '12px', px: '16px', py: '12px' }}>
              {searchMode ? (
                <TextField
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Rechercher..."
                  size="small"
                  autoFocus
                  fullWidth
                  slotProps={{
                    htmlInput: { sx: { fontSize: 14 } },
                    input: {
                      startAdornment: (
                        <InputAdornment position="start">
                          <SearchIcon sx={{ color: 'text.secondary' }} />
                        </InputAdornment>
                      ),
                      sx: pillFieldSx,
                    },
                  }}
                />
              ) : (
                <>
                  <MaggieAvatar size={36} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography component="span" sx={{ display: 'block', fontWeight: 600, lineHeight: 1.2 }}>
                      Maggie
                    </Typography>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <Box
                        data-testid="chat-status-dot"
                        sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'success.main', flexShrink: 0 }}
                      />
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {STATE_LABELS[agentState]}
                      </Typography>
                    </Box>
                  </Box>
                </>
              )}
              <Box sx={{ display: 'flex', gap: '4px', ml: 'auto', flexShrink: 0 }}>
                {/* Neither carried a name, so a screen reader announced them both
                    as "button" — and once the panel is a full-screen sheet, its
                    own close button is the only way back to the page. */}
                {!searchMode && sidebarTab === 'chat' && (
                  <IconButton
                    size="small"
                    aria-label="Fils de discussion"
                    onClick={openThreads}
                    sx={roundButtonSx(false)}
                  >
                    <ForumOutlinedIcon />
                  </IconButton>
                )}
                {!searchMode && sidebarTab === 'chat' && (
                  <IconButton
                    size="small"
                    aria-label="Rechercher dans la conversation"
                    onClick={openSearch}
                    sx={roundButtonSx(false)}
                  >
                    <SearchIcon />
                  </IconButton>
                )}
                <IconButton
                  size="small"
                  aria-label={searchMode ? 'Fermer la recherche' : 'Fermer la conversation'}
                  onClick={searchMode ? closeSearch : onClose}
                  sx={roundButtonSx(false)}
                >
                  <CloseIcon />
                </IconButton>
              </Box>
            </Box>
            {!searchMode && (
              <Tabs
                value={sidebarTab}
                onChange={onTabChange}
                sx={{
                  minHeight: 44,
                  px: '8px',
                  borderBottom: 1,
                  borderColor: 'divider',
                  '& .MuiTab-root': {
                    minHeight: 44,
                    color: 'text.secondary',
                    fontSize: 13,
                    textTransform: 'none',
                    '&.Mui-selected': { color: 'text.primary' },
                  },
                  '& .MuiTabs-indicator': { bgcolor: 'primary.main', borderRadius: 2 },
                }}
              >
                <Tab value="chat" label="Chat" icon={<ChatIcon sx={{ fontSize: 18 }} />} iconPosition="start" />
                <Tab value="mind" label="Mind" icon={<PsychologyIcon sx={{ fontSize: 18 }} />} iconPosition="start" />
              </Tabs>
            )}
          </Box>

          {/* Chat tab content */}
          {sidebarTab === 'chat' && (
            <>
              {/* Search Results */}
              {searchMode ? (
                <Box sx={{ flex: 1, overflowY: 'auto', p: 1.5 }}>
                  {searchLoading ? (
                    <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
                      <CircularProgress size={24} />
                    </Box>
                  ) : searchQuery.trim() && searchResults.length === 0 ? (
                    <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
                      Aucun message trouvé
                    </Typography>
                  ) : (
                    searchResults.map((result) => (
                      <Box
                        key={result.id}
                        onClick={() => navigateToMessage(result.id)}
                        sx={{
                          p: 1.5,
                          mb: 1,
                          borderRadius: '16px',
                          cursor: 'pointer',
                          bgcolor: 'veilleuse.raised',
                          '&:hover': { bgcolor: 'action.selected' },
                          display: 'flex',
                          flexDirection: 'column',
                          gap: 0.5,
                        }}
                      >
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          {result.role === 'user' ? (
                            <PersonIcon sx={{ fontSize: 16, color: 'primary.main' }} />
                          ) : (
                            <SmartToyIcon sx={{ fontSize: 16, color: 'secondary.main' }} />
                          )}
                          <Typography variant="caption" color="text.secondary">
                            {formatDate(result.createdAt)}
                          </Typography>
                        </Box>
                        <Typography
                          variant="body2"
                          sx={{
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                          }}
                          dangerouslySetInnerHTML={{
                            __html: highlightSearchTerm(saidInMessage(result.content), searchQuery),
                          }}
                        />
                      </Box>
                    ))
                  )}
                </Box>
              ) : (
                <>
                  {/* Messages */}
                  <Box
                    ref={attachList}
                    onScroll={handleScroll}
                    sx={{
                      flex: 1,
                      overflowY: 'auto',
                      p: 1.5,
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 1,
                      position: 'relative',
                    }}
                  >
                    {loadingHistory && (
                      <Box sx={{ display: 'flex', justifyContent: 'center', py: 1 }}>
                        <CircularProgress size={20} />
                      </Box>
                    )}
                    {messages.map((msg, index) => {
                      const prev = index > 0 ? messages[index - 1] : null
                      const separator = getTimeSeparatorLabel(prev, msg)
                      const showUnreadLine = msg.id === unreadFromId

                      return (
                        <Fragment key={msg.id}>
                          {separator && (
                            <Typography
                              variant="caption"
                              sx={{ alignSelf: 'center', color: 'text.secondary', py: 0.5 }}
                            >
                              {separator}
                            </Typography>
                          )}
                          {showUnreadLine && (
                            <Divider
                              sx={{ my: 0.5, '&::before, &::after': { borderColor: 'warning.main' } }}
                            >
                              <Chip
                                label="Messages non lus"
                                size="small"
                                sx={{
                                  bgcolor: 'warning.main',
                                  color: 'warning.contrastText',
                                  fontSize: 11,
                                  height: 20,
                                }}
                              />
                            </Divider>
                          )}
                          {tappedId === msg.id && !separator && (
                            <Typography
                              variant="caption"
                              sx={{ alignSelf: 'center', color: 'text.secondary', py: 0.5 }}
                            >
                              {formatDayLabel(new Date(msg.createdAt)) + ' ' + formatTime(new Date(msg.createdAt))}
                            </Typography>
                          )}
                          <ChatBubble
                            id={`msg-${msg.id}`}
                            role={msg.role}
                            highlighted={highlightId === msg.id}
                            animate={msg.id !== streamedId}
                            onClick={() => setTappedId((prev) => (prev === msg.id ? null : msg.id))}
                            // A message the server does not know yet (sent from here, echo not
                            // back) or one made up by the panel has nothing to delete.
                            onDelete={
                              msg.id.startsWith('tmp-') || msg.id.startsWith('err-')
                                ? undefined
                                : () => deleteMessage(msg)
                            }
                          >
                            {saidInMessage(msg.content)}
                          </ChatBubble>
                          {msg.id === unansweredId && (
                            <Box
                              data-testid="chat-send-failed"
                              sx={{ alignSelf: 'flex-end', display: 'flex', alignItems: 'center', gap: 0.5, px: 1 }}
                            >
                              <Typography variant="caption" color="error">
                                Maggie n'a pas pu être jointe
                              </Typography>
                              <Button size="small" onClick={retryFailedSend}>
                                Réessayer
                              </Button>
                            </Box>
                          )}
                        </Fragment>
                      )
                    })}
                    {/* Streaming bubble */}
                    {streamingMsgId && streamingText && (
                      <ChatBubble role="assistant">{streamingText}</ChatBubble>
                    )}
                    {agentState !== 'idle' && !streamingText && (
                      <Box
                        sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary', px: 1 }}
                      >
                        <ActivityPulse state={agentState} />
                        <Typography variant="body2">
                          {agentState === 'acting' ? 'Maggie agit...' : 'Maggie réfléchit...'}
                        </Typography>
                      </Box>
                    )}
                  </Box>

                  {/* Back to latest button */}
                  {!following && (
                    <Chip
                      icon={<ArrowDownwardIcon />}
                      label="Derniers messages"
                      size="small"
                      onClick={scrollToBottom}
                      sx={{
                        position: 'absolute',
                        bottom: 80,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        zIndex: 1,
                        bgcolor: 'veilleuse.raised',
                        boxShadow: 2,
                      }}
                    />
                  )}
                </>
              )}

              {/* Dictation: the orb takes the place of the old recording bar */}
              {(recorder.state === 'recording' || isTranscribing) && (
                <VoiceOrb
                  phase={recorder.state === 'recording' ? 'listening' : 'transcribing'}
                  duration={recorder.duration}
                  onFinish={handleMicClick}
                  onCancel={recorder.cancelRecording}
                />
              )}

              {/* Voice error */}
              {voiceError && (
                <Box sx={{ px: 1.5, py: 0.5 }}>
                  <Typography variant="caption" color="error">
                    {voiceError}
                  </Typography>
                </Box>
              )}

              {/* Input */}
              {!searchMode && (
                <Box sx={{ px: '16px', py: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <TextField
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Demande à Maggie..."
                    size="small"
                    fullWidth
                    slotProps={{ htmlInput: { sx: { fontSize: 14 } }, input: { sx: pillFieldSx } }}
                  />
                  <Tooltip title={recorder.state === 'recording' ? 'Arrêter la dictée' : 'Dicter'}>
                    <span>
                      {/* A tooltip is not an accessible name: MUI renders it in
                          a portal and points at it with aria-describedby, so
                          these two buttons had none at all — unreachable to a
                          screen reader, and to the chat journey's dictation
                          step (MAG-99). */}
                      <IconButton
                        aria-label={recorder.state === 'recording' ? 'Arrêter la dictée' : 'Dicter'}
                        onClick={handleMicClick}
                        disabled={isTranscribing || isLoading}
                        sx={[
                          roundButtonSx(true),
                          recorder.state === 'recording' && { color: 'error.main' },
                        ]}
                      >
                        {isTranscribing ? (
                          <CircularProgress size={20} />
                        ) : recorder.state === 'recording' ? (
                          <StopIcon />
                        ) : (
                          <MicIcon />
                        )}
                      </IconButton>
                    </span>
                  </Tooltip>
                  <IconButton
                    aria-label="Envoyer"
                    onClick={sendMessage}
                    disabled={isLoading || !input.trim()}
                    sx={(theme) => ({
                      ...roundButtonSx(true)(theme),
                      bgcolor: 'primary.main',
                      color: 'primary.contrastText',
                      '&:hover': { bgcolor: 'primary.light' },
                      '&.Mui-disabled': { bgcolor: theme.palette.veilleuse.raised, color: 'text.disabled' },
                    })}
                  >
                    <SendIcon sx={{ fontSize: 20 }} />
                  </IconButton>
                </Box>
              )}
            </>
          )}

          {/* Mind tab content */}
          {sidebarTab === 'mind' && (
            <Box sx={{ flex: 1, overflowY: 'auto' }}>
              {agentState !== 'idle' && (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.5 }}>
                  <ActivityPulse state={agentState} />
                  <Typography variant="body2" color="text.secondary">
                    {agentState === 'acting' ? 'Maggie agit...' : 'Maggie réfléchit...'}
                  </Typography>
                </Box>
              )}
              <Box sx={{ py: 1, flex: 1 }}>
                <Typography
                  variant="overline"
                  sx={{ px: 2, color: 'text.secondary', fontSize: 10, letterSpacing: 1 }}
                >
                  Activité
                </Typography>
                <ToolCallList toolCalls={toolCalls} />
              </Box>
            </Box>
          )}
        </Box>
      </Box>
    )

    // Beside the panel rather than inside it: both are modal layers of their own, and
    // under `md` the panel is itself a drawer.
    const overlays = (
      <>
        <ThreadsDialog
          open={threadsOpen}
          onClose={() => setThreadsOpen(false)}
          contexts={contexts}
          onDelete={deleteThread}
        />
        {/* Portaled: a modal marks everything already beside it aria-hidden, and « Annuler »
            has to stay reachable while the threads dialog is open. */}
        <Portal>
          <Snackbar
            open={notice !== null}
            message={notice?.text}
            autoHideDuration={notice?.undo ? null : UNDO_DELAY_MS}
            onClose={(_event, reason) => {
              // « Annuler » stays until the delay is over, however the snackbar is poked.
              if (reason === 'timeout') setNotice(null)
            }}
            action={
              notice?.undo ? (
                <Button color="primary" size="small" onClick={undoDeletion}>
                  Annuler
                </Button>
              ) : undefined
            }
          />
        </Portal>
      </>
    )

    if (!isNarrow) {
      return (
        <>
          {panel}
          {overlays}
        </>
      )
    }

    return (
      <>
        <Drawer
          anchor="right"
          open={open}
          onClose={onClose}
          slotProps={{ paper: { sx: { width: '100%', bgcolor: 'maggie.panel', backgroundImage: 'none' } } }}
        >
          {panel}
        </Drawer>
        {overlays}
      </>
    )
  },
)

function highlightSearchTerm(text: string, query: string): string {
  if (!query.trim()) return escapeHtml(text)
  const escaped = escapeRegex(query.trim())
  const regex = new RegExp(`(${escaped})`, 'gi')
  return escapeHtml(text.substring(0, 200)).replace(regex, '<mark>$1</mark>')
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
