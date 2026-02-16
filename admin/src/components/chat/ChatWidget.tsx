import { useState, useEffect, useRef, useCallback } from 'react'
import Drawer from '@mui/material/Drawer'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import SendIcon from '@mui/icons-material/Send'
import CloseIcon from '@mui/icons-material/Close'
import CircularProgress from '@mui/material/CircularProgress'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

const AGENT_URL = '/agent/chat'
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
const SIDEBAR_WIDTH = 380

interface ChatWidgetProps {
  open: boolean
  onClose: () => void
  onUnread: () => void
}

export const ChatWidget = ({ open, onClose, onUnread }: ChatWidgetProps) => {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Subscribe to Mercure SSE for real-time responses
  useEffect(() => {
    const url = new URL(MERCURE_URL)
    url.searchParams.append('topic', '/agent/chat/default')

    const eventSource = new EventSource(url.toString())
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        // Only handle proaction messages from Mercure (chat uses HTTP response)
        if (data.proaction && data.response) {
          setMessages((prev) => [...prev, { role: 'assistant', content: data.response }])
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

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = useCallback(async () => {
    if (!input.trim() || loading) return

    const userMessage = input.trim()
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', content: userMessage }])
    setLoading(true)

    try {
      const response = await fetch(AGENT_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: userMessage, user_id: 'default' }),
      })

      if (response.ok) {
        const data = await response.json()
        setMessages((prev) => [...prev, { role: 'assistant', content: data.response }])
      }
    } catch (error) {
      console.error('Chat error:', error)
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: "Erreur : impossible de contacter l'agent." },
      ])
    } finally {
      setLoading(false)
    }
  }, [input, loading])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  return (
    <Drawer
      variant="persistent"
      anchor="right"
      open={open}
      sx={{
        width: open ? SIDEBAR_WIDTH : 0,
        flexShrink: 0,
        '& .MuiDrawer-paper': {
          width: SIDEBAR_WIDTH,
          boxSizing: 'border-box',
          top: '48px',
          height: 'calc(100% - 48px)',
        },
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
        <Typography variant="subtitle1" fontWeight={600}>
          Maggie
        </Typography>
        <IconButton size="small" onClick={onClose} sx={{ color: 'inherit' }}>
          <CloseIcon />
        </IconButton>
      </Box>

      {/* Messages */}
      <Box sx={{ flex: 1, overflowY: 'auto', p: 1.5, display: 'flex', flexDirection: 'column', gap: 1 }}>
        {messages.map((msg, i) => (
          <Box
            key={i}
            sx={{
              alignSelf: msg.role === 'user' ? 'flex-end' : 'flex-start',
              maxWidth: '85%',
              px: 1.5,
              py: 1,
              borderRadius: 2,
              bgcolor: msg.role === 'user' ? 'primary.main' : 'grey.100',
              color: msg.role === 'user' ? 'primary.contrastText' : 'text.primary',
              fontSize: 14,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {msg.content}
          </Box>
        ))}
        {loading && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary', px: 1 }}>
            <CircularProgress size={16} />
            <Typography variant="body2">Maggie réfléchit...</Typography>
          </Box>
        )}
        <div ref={messagesEndRef} />
      </Box>

      {/* Input */}
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
        <IconButton color="primary" onClick={sendMessage} disabled={loading || !input.trim()}>
          <SendIcon />
        </IconButton>
      </Box>
    </Drawer>
  )
}
