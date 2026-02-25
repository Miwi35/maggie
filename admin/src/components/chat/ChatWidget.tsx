import { useState, useEffect, useRef, useCallback, forwardRef, useImperativeHandle } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import InputAdornment from '@mui/material/InputAdornment'
import Chip from '@mui/material/Chip'
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

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

const AGENT_URL = '/agent/chat'
const MESSAGES_URL = '/agent/messages'
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
const SIDEBAR_WIDTH = 380
const PAGE_SIZE = 20

interface ChatWidgetProps {
  open: boolean
  onClose: () => void
  onUnread: () => void
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

export const ChatWidget = forwardRef<ChatWidgetRef, ChatWidgetProps>(
  ({ open, onClose, onUnread }, ref) => {
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [input, setInput] = useState('')
    const [loading, setLoading] = useState(false)
    const [loadingHistory, setLoadingHistory] = useState(false)
    const [hasMore, setHasMore] = useState(true)
    const [highlightId, setHighlightId] = useState<string | null>(null)
    const [isNearBottom, setIsNearBottom] = useState(true)

    // Search state
    const [searchMode, setSearchMode] = useState(false)
    const [searchQuery, setSearchQuery] = useState('')
    const [searchResults, setSearchResults] = useState<ChatMessage[]>([])
    const [searchLoading, setSearchLoading] = useState(false)

    const messagesEndRef = useRef<HTMLDivElement>(null)
    const messagesContainerRef = useRef<HTMLDivElement>(null)
    const historyLoadedRef = useRef(false)
    const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    const recorder = useVoiceRecorder()
    const transcription = useTranscription()

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

    // --- Send message ---

    const sendMessageDirect = useCallback(
      async (text: string) => {
        if (!text.trim() || loading) return

        const userMessage = text.trim()
        const tmpId = `tmp-${Date.now()}`
        setMessages((prev) => [
          ...prev,
          { id: tmpId, role: 'user', content: userMessage, createdAt: new Date().toISOString() },
        ])
        setLoading(true)
        setIsNearBottom(true)

        try {
          const res = await fetch(AGENT_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
            body: JSON.stringify({ message: userMessage }),
          })

          if (res.ok) {
            const data = await res.json()
            // Replace temp user message and add server messages (with dedup)
            setMessages((prev) => {
              const withoutTmp = prev.filter((m) => m.id !== tmpId)
              const existingIds = new Set(withoutTmp.map((m) => m.id))
              const newMsgs = (data.messages as ChatMessage[]).filter(
                (m) => !existingIds.has(m.id),
              )
              return [...withoutTmp, ...newMsgs]
            })
          }
        } catch (error) {
          console.error('Chat error:', error)
          setMessages((prev) => [
            ...prev,
            {
              id: `err-${Date.now()}`,
              role: 'assistant',
              content: "Erreur : impossible de contacter l'agent.",
              createdAt: new Date().toISOString(),
            },
          ])
        } finally {
          setLoading(false)
        }
      },
      [loading],
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
              if (prev.some((m) => m.id === data.id)) return prev
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
                {messages.map((msg) => (
                  <Box
                    key={msg.id}
                    id={`msg-${msg.id}`}
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
                ))}
                {loading && (
                  <Box
                    sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary', px: 1 }}
                  >
                    <CircularProgress size={16} />
                    <Typography variant="body2">Maggie réfléchit...</Typography>
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
                    disabled={isTranscribing || loading}
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
              <IconButton color="primary" onClick={sendMessage} disabled={loading || !input.trim()}>
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
