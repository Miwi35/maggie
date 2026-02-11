import { useState, useEffect, useRef } from 'react'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

const AGENT_URL = '/agent/chat'
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

export const ChatWidget = () => {
  const [isOpen, setIsOpen] = useState(false)
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
        if (data.response) {
          setMessages((prev) => [...prev, { role: 'assistant', content: data.response }])
        }
      } catch {
        // ignore parse errors
      }
    }

    return () => eventSource.close()
  }, [])

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = async () => {
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
      setMessages((prev) => [...prev, { role: 'assistant', content: 'Error: Could not reach the agent.' }])
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  if (!isOpen) {
    return (
      <button
        onClick={() => setIsOpen(true)}
        style={{
          position: 'fixed', bottom: 20, right: 20,
          width: 56, height: 56, borderRadius: '50%',
          backgroundColor: '#1976d2', color: 'white',
          border: 'none', cursor: 'pointer', fontSize: 24,
          boxShadow: '0 2px 10px rgba(0,0,0,0.3)',
          zIndex: 1000,
        }}
      >
        💬
      </button>
    )
  }

  return (
    <div style={{
      position: 'fixed', bottom: 20, right: 20,
      width: 380, height: 500, display: 'flex', flexDirection: 'column',
      backgroundColor: 'white', borderRadius: 12,
      boxShadow: '0 4px 20px rgba(0,0,0,0.2)',
      zIndex: 1000, overflow: 'hidden',
    }}>
      {/* Header */}
      <div style={{
        padding: '12px 16px', backgroundColor: '#1976d2', color: 'white',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <strong>Maggie</strong>
        <button onClick={() => setIsOpen(false)} style={{
          background: 'none', border: 'none', color: 'white', cursor: 'pointer', fontSize: 18,
        }}>✕</button>
      </div>

      {/* Messages */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 12 }}>
        {messages.map((msg, i) => (
          <div key={i} style={{
            marginBottom: 8, padding: '8px 12px', borderRadius: 8,
            backgroundColor: msg.role === 'user' ? '#e3f2fd' : '#f5f5f5',
            marginLeft: msg.role === 'user' ? 40 : 0,
            marginRight: msg.role === 'assistant' ? 40 : 0,
          }}>
            {msg.content}
          </div>
        ))}
        {loading && (
          <div style={{ padding: '8px 12px', color: '#999' }}>Maggie is thinking...</div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div style={{ padding: 12, borderTop: '1px solid #eee', display: 'flex', gap: 8 }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Ask Maggie..."
          style={{ flex: 1, padding: '8px 12px', borderRadius: 8, border: '1px solid #ddd' }}
        />
        <button
          onClick={sendMessage}
          disabled={loading}
          style={{
            padding: '8px 16px', borderRadius: 8,
            backgroundColor: '#1976d2', color: 'white',
            border: 'none', cursor: 'pointer',
          }}
        >
          Send
        </button>
      </div>
    </div>
  )
}
