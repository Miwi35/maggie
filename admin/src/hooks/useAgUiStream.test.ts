import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAgUiStream } from './useAgUiStream'

function createMockResponse(events: Record<string, unknown>[]) {
  const sseData = events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join('')
  const encoder = new TextEncoder()
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(sseData))
      controller.close()
    },
  })
  return {
    ok: true,
    status: 200,
    body: stream,
  }
}

describe('useAgUiStream', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  test('send triggers fetch and parses events', async () => {
    const onRunStarted = vi.fn()
    const onRunFinished = vi.fn()
    const onTextStart = vi.fn()
    const onTextDelta = vi.fn()
    const onTextEnd = vi.fn()

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        createMockResponse([
          { type: 'RUN_STARTED', runId: 'r1' },
          { type: 'TEXT_MESSAGE_START', messageId: 'm1', role: 'assistant' },
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm1', delta: 'Hello' },
          { type: 'TEXT_MESSAGE_END', messageId: 'm1' },
          { type: 'RUN_FINISHED', runId: 'r1' },
        ]),
      ),
    )

    const { result } = renderHook(() =>
      useAgUiStream({
        onRunStarted,
        onRunFinished,
        onTextStart,
        onTextDelta,
        onTextEnd,
      }),
    )

    await act(async () => {
      await result.current.send('test message')
    })

    expect(onRunStarted).toHaveBeenCalledTimes(1)
    expect(onTextStart).toHaveBeenCalledWith('m1')
    expect(onTextDelta).toHaveBeenCalledWith('m1', 'Hello')
    expect(onTextEnd).toHaveBeenCalledWith('m1')
    expect(onRunFinished).toHaveBeenCalledTimes(1)
  })

  test('dispatches tool call events', async () => {
    const onToolCallStart = vi.fn()
    const onToolCallEnd = vi.fn()
    const onToolResult = vi.fn()

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        createMockResponse([
          { type: 'RUN_STARTED', runId: 'r1' },
          { type: 'TOOL_CALL_START', toolCallId: 'tc1', toolName: 'add_item' },
          { type: 'TOOL_CALL_END', toolCallId: 'tc1', toolName: 'add_item' },
          {
            type: 'CUSTOM',
            name: 'tool_result',
            value: { toolCallId: 'tc1', toolName: 'add_item', status: 'success' },
          },
          { type: 'RUN_FINISHED', runId: 'r1' },
        ]),
      ),
    )

    const { result } = renderHook(() =>
      useAgUiStream({ onToolCallStart, onToolCallEnd, onToolResult }),
    )

    await act(async () => {
      await result.current.send('add milk')
    })

    expect(onToolCallStart).toHaveBeenCalledWith('tc1', 'add_item')
    expect(onToolCallEnd).toHaveBeenCalledWith('tc1', 'add_item')
    expect(onToolResult).toHaveBeenCalledWith('tc1', 'add_item', 'success')
  })

  test('dispatches context update events', async () => {
    const onContextUpdate = vi.fn()

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        createMockResponse([
          { type: 'RUN_STARTED', runId: 'r1' },
          {
            type: 'CUSTOM',
            name: 'context_update',
            value: { id: 'ctx1', label: 'Shopping', status: 'active', action: 'created' },
          },
          { type: 'RUN_FINISHED', runId: 'r1' },
        ]),
      ),
    )

    const { result } = renderHook(() => useAgUiStream({ onContextUpdate }))

    await act(async () => {
      await result.current.send('courses')
    })

    expect(onContextUpdate).toHaveBeenCalledWith({
      id: 'ctx1',
      label: 'Shopping',
      status: 'active',
      action: 'created',
    })
  })

  test('calls onError on HTTP failure', async () => {
    const onError = vi.fn()

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 500 }),
    )

    const { result } = renderHook(() => useAgUiStream({ onError }))

    await act(async () => {
      await result.current.send('test')
    })

    expect(onError).toHaveBeenCalledWith('HTTP 500')
  })

  test('calls onError on network failure', async () => {
    const onError = vi.fn()

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))

    const { result } = renderHook(() => useAgUiStream({ onError }))

    await act(async () => {
      await result.current.send('test')
    })

    expect(onError).toHaveBeenCalledWith('Network error')
  })
})
