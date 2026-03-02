import { useState, useEffect, useRef, useCallback, forwardRef, useImperativeHandle, Fragment } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import InputAdornment from '@mui/material/InputAdornment'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import SendIcon from '@mui/icons-material/Send'
import CloseIcon from '@mui/icons-material/Close'
import MicIcon from '@mui/icons-material/Mic'
import StopIcon from '@mui/icons-material/Stop'
import SearchIcon from '@mui/icons-material/Search'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import PersonIcon from '@mui/icons-material/Person'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import CircularProgress from '@mui/material/CircularProgress'
import { useVoiceRecorder } from '../../hooks/useVoiceRecorder'
import { useTranscription } from '../../hooks/useTranscription'
import { useAgUiStream } from '../../hooks/useAgUiStream'
import { ActivityPulse } from '../mind/ActivityPulse'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

const MESSAGES_URL = '/agent/messages'
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
const SIDEBAR_WIDTH = 380
const PAGE_SIZE = 20

interface ChatWidgetProps {
  open: boolean
  onClose: () => void
  onUnread: () => void
  agentState: AgentState
  onAgentStateChange: (state: AgentState) => void
  onContextsChange: (contexts: ContextState[]) => void
  onToolCallsChange: (toolCalls: ToolCallState[]) => void
}

export interface ChatWidgetRef {
  sendMessage: (text: string) => void
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
  ({ open, onClose, onUnread, agentState, onAgentStateChange, onContextsChange, onToolCallsChange }, ref) => {
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [input, setInput] = useState('')
    const [loadingHistory, setLoadingHistory] = useState(false)
    const [hasMore, setHasMore] = useState(true)
    const [highlightId, setHighlightId] = useState<string | null>(null)
    const [isNearBottom, setIsNearBottom] = useState(true)
    const [unreadFromId, setUnreadFromId] = useState<string | null>(null)
    const [tappedId, setTappedId] = useState<string | null>(null)

    // Streaming state
    const [streamingText, setStreamingText] = useState('')
    const [streamingMsgId, setStreamingMsgId] = useState<string | null>(null)
    const streamedMessageIdRef = useRef<string | null>(null)

    // Search state
    const [searchMode, setSearchMode] = useState(false)
    const [searchQuery, setSearchQuery] = useState('')
    const [searchResults, setSearchResults] = useState<ChatMessage[]>([])
    const [searchLoading, setSearchLoading] = useState(false)

    const messagesEndRef = useRef<HTMLDivElement>(null)
    const messagesContainerRef = useRef<HTMLDivElement>(null)
    const historyLoadedRef = useRef(false)
    const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    const prevOpenRef = useRef(open)

    const recorder = useVoiceRecorder()
    const transcription = useTranscription()

    // AG-UI stream callbacks
    const toolCallsRef = useRef<ToolCallState[]>([])
    const contextsRef = useRef<ContextState[]>([])

    const agUiStream = useAgUiStream({
      onRunStarted: () => {
        onAgentStateChange('thinking')
        toolCallsRef.current = []
        onToolCallsChange([])
      },
      onRunFinished: () => {
        onAgentStateChange('idle')
      },
      onTextStart: (messageId) => {
        setStreamingMsgId(messageId)
        setStreamingText('')
        streamedMessageIdRef.current = messageId
        onAgentStateChange('thinking')
      },
      onTextDelta: (_messageId, delta) => {
        setStreamingText((prev) => prev + delta)
      },
      onTextEnd: (_messageId) => {
        // Finalize: move streaming text into messages array
        setStreamingText((finalText) => {
          if (finalText.trim()) {
            setMessages((prev) => [
              ...prev,
              {
                id: `stream-${Date.now()}`,
                role: 'assistant',
                content: finalText,
                createdAt: new Date().toISOString(),
              },
            ])
          }
          return ''
        })
        setStreamingMsgId(null)
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
        const action = value.action as string
        const ctx: ContextState = {
          id: value.id as string,
          label: value.label as string,
          status: value.status as ContextState['status'],
        }
        if (action === 'created') {
          contextsRef.current = [ctx, ...contextsRef.current]
        } else {
          contextsRef.current = contextsRef.current.map((c) => (c.id === ctx.id ? ctx : c))
        }
        onContextsChange(contextsRef.current)
      },
      onError: (message) => {
        console.error('Stream error:', message)
        setMessages((prev) => [
          ...prev,
          {
            id: `err-${Date.now()}`,
            role: 'assistant',
            content: "Erreur : impossible de contacter l'agent.",
            createdAt: new Date().toISOString(),
          },
        ])
        onAgentStateChange('idle')
      },
    })

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

    // --- Scroll to bottom on initial load ---

    useEffect(() => {
      if (!loadingHistory && historyLoadedRef.current) {
        messagesEndRef.current?.scrollIntoView({ behavior: 'instant' })
      }
    }, [loadingHistory])

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

      // Check if near bottom
      const distFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight
      setIsNearBottom(distFromBottom < 100)

      // Load older when near top
      if (container.scrollTop < 50 && !searchMode) {
        loadOlderMessages()
      }
    }, [loadOlderMessages, searchMode])

    // --- Auto-scroll to bottom for new messages (only if near bottom) ---

    useEffect(() => {
      if (isNearBottom && !searchMode) {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
      }
    }, [messages, isNearBottom, searchMode])

    // --- Track chat close → save last read to localStorage ---

    useEffect(() => {
      if (prevOpenRef.current && !open && messages.length > 0) {
        const lastMsg = messages[messages.length - 1]
        if (lastMsg && !lastMsg.id.startsWith('tmp-') && !lastMsg.id.startsWith('err-')) {
          localStorage.setItem('chat_lastReadMessageId', lastMsg.id)
        }
        setUnreadFromId(null)
      }
      prevOpenRef.current = open
    }, [open, messages])

    // --- Clear unread breakline when user scrolls to bottom ---

    useEffect(() => {
      if (isNearBottom && unreadFromId) {
        setUnreadFromId(null)
        const lastMsg = messages[messages.length - 1]
        if (lastMsg && !lastMsg.id.startsWith('tmp-') && !lastMsg.id.startsWith('err-')) {
          localStorage.setItem('chat_lastReadMessageId', lastMsg.id)
        }
      }
    }, [isNearBottom, unreadFromId, messages])

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
        setIsNearBottom(true)

        await agUiStream.send(userMessage)
      },
      [agUiStream],
    )

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
      const userStr = localStorage.getItem('user')
      const userId = userStr ? JSON.parse(userStr).id : 'default'
      const url = new URL(MERCURE_URL)
      url.searchParams.append('topic', `/agent/chat/${userId}`)

      const eventSource = new EventSource(url.toString())
      eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.id && data.content) {
            setMessages((prev) => {
              // Dedup: skip if already in list or if content matches just-streamed message
              if (prev.some((m) => m.id === data.id)) return prev
              // If the content matches a recent streamed message, skip it (Mercure echo)
              if (
                data.role === 'assistant' &&
                prev.length > 0 &&
                prev[prev.length - 1].role === 'assistant' &&
                prev[prev.length - 1].id.startsWith('stream-') &&
                prev[prev.length - 1].content === data.content
              ) {
                // Replace the stream-* id with the real persisted id
                return prev.map((m, i) => (i === prev.length - 1 ? { ...m, id: data.id } : m))
              }
              return [
                ...prev,
                {
                  id: data.id,
                  role: data.role || 'assistant',
                  content: data.content,
                  createdAt: data.createdAt || new Date().toISOString(),
                },
              ]
            })
            if (!open) {
              onUnread()
            }
          }
        } catch {
          // Ignore malformed messages
        }
      }

      return () => eventSource.close()
    }, [open, onUnread])

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
      [],
    )

    const scrollToBottom = useCallback(() => {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
      setIsNearBottom(true)
    }, [])

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
        const text = await transcription.transcribe(blob)
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
    const voiceError = recorder.error || transcription.error

    // --- Render ---

    return (
      <Box
        sx={{
          width: open ? SIDEBAR_WIDTH : 0,
          flexShrink: 0,
          transition: 'width 225ms cubic-bezier(0, 0, 0.2, 1)',
        }}
      >
        <Box
          sx={{
            width: SIDEBAR_WIDTH,
            position: 'fixed',
            top: (theme) => theme.mixins.toolbar.minHeight,
            right: 0,
            bottom: 0,
            display: 'flex',
            flexDirection: 'column',
            borderLeft: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            transform: open ? 'translateX(0)' : `translateX(${SIDEBAR_WIDTH}px)`,
            transition: 'transform 225ms cubic-bezier(0, 0, 0.2, 1)',
          }}
        >
          {/* Header */}
          <Box
            sx={{
              px: 2,
              py: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              borderBottom: 1,
              borderColor: 'divider',
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
            }}
          >
            {searchMode ? (
              <TextField
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Rechercher..."
                size="small"
                autoFocus
                fullWidth
                slotProps={{
                  htmlInput: { sx: { color: 'primary.contrastText', fontSize: 14 } },
                  input: {
                    startAdornment: (
                      <InputAdornment position="start">
                        <SearchIcon sx={{ color: 'primary.contrastText', opacity: 0.7 }} />
                      </InputAdornment>
                    ),
                    sx: {
                      '& .MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.3)' },
                      '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.5)' },
                      '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.7)' },
                    },
                  },
                }}
              />
            ) : (
              <Typography variant="subtitle1" fontWeight={600}>
                Maggie
              </Typography>
            )}
            <Box sx={{ display: 'flex', gap: 0.5, ml: 1 }}>
              {!searchMode && (
                <IconButton size="small" onClick={openSearch} sx={{ color: 'inherit' }}>
                  <SearchIcon />
                </IconButton>
              )}
              <IconButton
                size="small"
                onClick={searchMode ? closeSearch : onClose}
                sx={{ color: 'inherit' }}
              >
                <CloseIcon />
              </IconButton>
            </Box>
          </Box>

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
                      borderRadius: 1,
                      cursor: 'pointer',
                      bgcolor: 'action.hover',
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
                        __html: highlightSearchTerm(result.content, searchQuery),
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
                ref={messagesContainerRef}
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
                      <Box
                        id={`msg-${msg.id}`}
                        onClick={() => setTappedId((prev) => (prev === msg.id ? null : msg.id))}
                        sx={{
                          alignSelf: msg.role === 'user' ? 'flex-end' : 'flex-start',
                          maxWidth: '85%',
                          px: 1.5,
                          py: 1,
                          borderRadius: 2,
                          bgcolor: msg.role === 'user' ? 'primary.main' : 'grey.800',
                          color: msg.role === 'user' ? 'primary.contrastText' : 'grey.100',
                          fontSize: 14,
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                          transition: 'box-shadow 0.3s ease',
                          ...(highlightId === msg.id && {
                            boxShadow: '0 0 0 2px #ff9800',
                            animation: 'highlight-fade 2s ease-out',
                            '@keyframes highlight-fade': {
                              '0%': { boxShadow: '0 0 0 3px #ff9800' },
                              '100%': { boxShadow: '0 0 0 0px transparent' },
                            },
                          }),
                        }}
                      >
                        {msg.content}
                      </Box>
                    </Fragment>
                  )
                })}
                {/* Streaming bubble */}
                {streamingMsgId && streamingText && (
                  <Box
                    sx={{
                      alignSelf: 'flex-start',
                      maxWidth: '85%',
                      px: 1.5,
                      py: 1,
                      borderRadius: 2,
                      bgcolor: 'grey.800',
                      color: 'grey.100',
                      fontSize: 14,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                    }}
                  >
                    {streamingText}
                  </Box>
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
                <div ref={messagesEndRef} />
              </Box>

              {/* Back to latest button */}
              {!isNearBottom && (
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
                    bgcolor: 'background.paper',
                    boxShadow: 2,
                  }}
                />
              )}
            </>
          )}

          {/* Recording indicator */}
          {recorder.state === 'recording' && (
            <Box
              sx={{
                px: 1.5,
                py: 0.75,
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                bgcolor: 'error.50',
                borderTop: 1,
                borderColor: 'divider',
              }}
            >
              <Box
                sx={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  bgcolor: 'error.main',
                  animation: 'pulse 1.5s ease-in-out infinite',
                  '@keyframes pulse': {
                    '0%, 100%': { opacity: 1 },
                    '50%': { opacity: 0.3 },
                  },
                }}
              />
              <Typography variant="caption" sx={{ flex: 1 }}>
                {recorder.duration}s
              </Typography>
              <Typography
                variant="caption"
                sx={{ cursor: 'pointer', color: 'error.main', fontWeight: 600 }}
                onClick={() => {
                  recorder.cancelRecording()
                }}
              >
                Annuler
              </Typography>
            </Box>
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
            <Box sx={{ p: 1.5, borderTop: 1, borderColor: 'divider', display: 'flex', gap: 1 }}>
              <TextField
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Demande à Maggie..."
                size="small"
                fullWidth
                slotProps={{ htmlInput: { sx: { fontSize: 14 } } }}
              />
              <Tooltip title={recorder.state === 'recording' ? 'Arrêter' : 'Dicter'}>
                <span>
                  <IconButton
                    onClick={handleMicClick}
                    disabled={isTranscribing || isLoading}
                    color={recorder.state === 'recording' ? 'error' : 'default'}
                  >
                    {isTranscribing ? (
                      <CircularProgress size={24} />
                    ) : recorder.state === 'recording' ? (
                      <StopIcon />
                    ) : (
                      <MicIcon />
                    )}
                  </IconButton>
                </span>
              </Tooltip>
              <IconButton color="primary" onClick={sendMessage} disabled={isLoading || !input.trim()}>
                <SendIcon />
              </IconButton>
            </Box>
          )}
        </Box>
      </Box>
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
