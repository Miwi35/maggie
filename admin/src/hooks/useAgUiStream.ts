import { useCallback, useRef, useState } from 'react'

const STREAM_URL = '/agent/chat/stream'

function getAuthHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

interface AgUiCallbacks {
  onRunStarted?: () => void
  onRunFinished?: () => void
  onTextStart?: (messageId: string) => void
  onTextDelta?: (messageId: string, delta: string) => void
  onTextEnd?: (messageId: string) => void
  onToolCallStart?: (toolCallId: string, toolName: string) => void
  onToolCallEnd?: (toolCallId: string, toolName: string) => void
  onToolResult?: (toolCallId: string, toolName: string, status: string) => void
  onContextUpdate?: (value: Record<string, unknown>) => void
  onError?: (message: string) => void
}

interface UseAgUiStreamReturn {
  send: (message: string) => Promise<void>
  isStreaming: boolean
}

export function useAgUiStream(callbacks: AgUiCallbacks): UseAgUiStreamReturn {
  const [isStreaming, setIsStreaming] = useState(false)
  const callbacksRef = useRef(callbacks)
  callbacksRef.current = callbacks

  const send = useCallback(async (message: string) => {
    setIsStreaming(true)

    try {
      const response = await fetch(STREAM_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
        body: JSON.stringify({ message }),
      })

      if (!response.ok) {
        callbacksRef.current.onError?.(`HTTP ${response.status}`)
        return
      }

      const reader = response.body?.getReader()
      if (!reader) {
        callbacksRef.current.onError?.('No response body')
        return
      }

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        // Keep the last incomplete line in buffer
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue
          const jsonStr = line.slice(6).trim()
          if (!jsonStr) continue

          try {
            const event = JSON.parse(jsonStr)
            dispatchEvent(event, callbacksRef.current)
          } catch {
            // Skip malformed events
          }
        }
      }

      // Process any remaining buffer
      if (buffer.startsWith('data: ')) {
        const jsonStr = buffer.slice(6).trim()
        if (jsonStr) {
          try {
            const event = JSON.parse(jsonStr)
            dispatchEvent(event, callbacksRef.current)
          } catch {
            // Skip
          }
        }
      }
    } catch (error) {
      callbacksRef.current.onError?.(error instanceof Error ? error.message : 'Stream failed')
    } finally {
      setIsStreaming(false)
    }
  }, [])

  return { send, isStreaming }
}

function dispatchEvent(event: Record<string, unknown>, cb: AgUiCallbacks): void {
  switch (event.type) {
    case 'RUN_STARTED':
      cb.onRunStarted?.()
      break
    case 'RUN_FINISHED':
      cb.onRunFinished?.()
      break
    case 'TEXT_MESSAGE_START':
      cb.onTextStart?.(event.messageId as string)
      break
    case 'TEXT_MESSAGE_CONTENT':
      cb.onTextDelta?.(event.messageId as string, event.delta as string)
      break
    case 'TEXT_MESSAGE_END':
      cb.onTextEnd?.(event.messageId as string)
      break
    case 'TOOL_CALL_START':
      cb.onToolCallStart?.(event.toolCallId as string, event.toolName as string)
      break
    case 'TOOL_CALL_END':
      cb.onToolCallEnd?.(event.toolCallId as string, event.toolName as string)
      break
    case 'CUSTOM':
      if (event.name === 'context_update') {
        cb.onContextUpdate?.(event.value as Record<string, unknown>)
      } else if (event.name === 'tool_result') {
        const val = event.value as Record<string, string>
        cb.onToolResult?.(val.toolCallId, val.toolName, val.status)
      }
      break
  }
}
