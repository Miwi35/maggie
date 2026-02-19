import { useState, useEffect, useRef, useCallback, forwardRef, useImperativeHandle } from 'react'
import Drawer from '@mui/material/Drawer'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import SendIcon from '@mui/icons-material/Send'
import CloseIcon from '@mui/icons-material/Close'
import MicIcon from '@mui/icons-material/Mic'
import StopIcon from '@mui/icons-material/Stop'
import CircularProgress from '@mui/material/CircularProgress'
import { useVoiceRecorder } from '../../hooks/useVoiceRecorder'
import { useTranscription } from '../../hooks/useTranscription'

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

export interface ChatWidgetRef {
  sendMessage: (text: string) => void
}

export const ChatWidget = forwardRef<ChatWidgetRef, ChatWidgetProps>(
  ({ open, onClose, onUnread }, ref) => {
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [input, setInput] = useState('')
    const [loading, setLoading] = useState(false)
    const messagesEndRef = useRef<HTMLDivElement>(null)

    const recorder = useVoiceRecorder()
    const transcription = useTranscription()

    const sendMessageDirect = useCallback(
      async (text: string) => {
        if (!text.trim() || loading) return

        const userMessage = text.trim()
        setMessages((prev) => [...prev, { role: 'user', content: userMessage }])
        setLoading(true)

        try {
          const token = localStorage.getItem('token')
          const response = await fetch(AGENT_URL, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              ...(token ? { Authorization: `Bearer ${token}` } : {}),
            },
            body: JSON.stringify({ message: userMessage }),
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
      },
      [loading],
    )

    useImperativeHandle(ref, () => ({
      sendMessage: (text: string) => {
        sendMessageDirect(text)
      },
    }), [sendMessageDirect])

    // Subscribe to Mercure SSE for real-time responses
    useEffect(() => {
      const userStr = localStorage.getItem('user')
      const userId = userStr ? JSON.parse(userStr).id : 'default'
      const url = new URL(MERCURE_URL)
      url.searchParams.append('topic', `/agent/chat/${userId}`)

      const eventSource = new EventSource(url.toString())
      eventSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.response) {
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

    const isTranscribing = recorder.state === 'processing' || transcription.loading
    const voiceError = recorder.error || transcription.error

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
      </Drawer>
    )
  },
)
