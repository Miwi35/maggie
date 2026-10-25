import { useCallback, useRef, useState } from 'react'
import { newMessageKey } from './messageKey'

const STREAM_URL = '/agent/chat/stream'
const MAX_RECONNECTS = 3
const RECONNECT_DELAY_MS = 500

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
  // [idempotencyKey] is one per message composed: a retry passes the key of the first send, so an
  // agent that already received the message answers it once (MAG-363).
  // [replaces] is the id of the last message of the user when this one corrects it (MAG-320).
  send: (message: string, idempotencyKey?: string, replaces?: string) => Promise<void>
  /** Closes the stream being read; the run is not an error. */
  stop: () => void
  isStreaming: boolean
}

export function useAgUiStream(callbacks: AgUiCallbacks): UseAgUiStreamReturn {
  const [isStreaming, setIsStreaming] = useState(false)
  const callbacksRef = useRef(callbacks)
  callbacksRef.current = callbacks
  const controllerRef = useRef<AbortController | null>(null)

  const stop = useCallback(() => controllerRef.current?.abort(), [])

  const send = useCallback(async (message: string, idempotencyKey: string = newMessageKey(), replaces?: string) => {
    setIsStreaming(true)
    const controller = new AbortController()
    controllerRef.current = controller

    const body = JSON.stringify({ message, idempotency_key: idempotencyKey, replaces })
    let finished = false
    let opened = false
    const handle = (event: Record<string, unknown>) => {
      if (event.type === 'RUN_FINISHED') finished = true
      dispatchEvent(event, callbacksRef.current)
    }

    try {
      // A stream cut before RUN_FINISHED (a proxy idle timeout, a network blip) is not a failed
      // turn: the agent keeps running it, so the same request — same key — follows it and brings
      // back the answer. Only a refusal (HTTP error) or a stream that stays down is a failure (MAG-638).
      for (let reconnects = 0; ; reconnects++) {
        try {
          const response = await fetch(STREAM_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
            body,
            signal: controller.signal,
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

          opened = true
          await readEvents(reader, handle)
          if (finished) return
          throw new Error('Stream ended before the run finished')
        } catch (error) {
          if (controller.signal.aborted || finished) return
          // No stream ever opened: the agent cannot be reached, which is a failure right away.
          if (!opened || reconnects >= MAX_RECONNECTS) {
            callbacksRef.current.onError?.(error instanceof Error ? error.message : 'Stream failed')
            return
          }
          await pause(RECONNECT_DELAY_MS * (reconnects + 1), controller.signal)
        }
      }
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null
      setIsStreaming(false)
    }
  }, [])

  return { send, stop, isStreaming }
}

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        resolve()
      },
      { once: true },
    )
  })
}

async function readEvents(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  handle: (event: Record<string, unknown>) => void,
): Promise<void> {
  const decoder = new TextDecoder()
  let buffer = ''

  const parse = (line: string) => {
    if (!line.startsWith('data: ')) return
    const jsonStr = line.slice(6).trim()
    if (!jsonStr) return
    try {
      handle(JSON.parse(jsonStr))
    } catch {
      // Skip malformed events
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    // Keep the last incomplete line in buffer
    buffer = lines.pop() || ''
    lines.forEach(parse)
  }

  parse(buffer)
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
