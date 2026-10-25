import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
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

// A stream a proxy cuts during a silence: it delivers what was sent, then breaks.
function createCutResponse(events: Record<string, unknown>[]) {
  const encoder = new TextEncoder()
  const sseData = events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join('')
  return {
    ok: true,
    status: 200,
    body: new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(sseData))
        controller.error(new TypeError('network error'))
      },
    }),
  }
}

describe('useAgUiStream', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
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

  test('each message is sent with its own idempotency key', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => createMockResponse([{ type: 'RUN_FINISHED', runId: 'r1' }]))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAgUiStream({}))

    await act(async () => {
      await result.current.send('un')
      await result.current.send('deux')
    })

    const keys = fetchMock.mock.calls.map(([, init]) => JSON.parse(init.body).idempotency_key)
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[0]).not.toBe(keys[1])
  })

  test('a key passed to send is the one on the wire, so a retry is the same message', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => createMockResponse([{ type: 'RUN_FINISHED', runId: 'r1' }]))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAgUiStream({}))

    await act(async () => {
      await result.current.send('un', 'k-1')
      await result.current.send('un', 'k-1')
    })

    const bodies = fetchMock.mock.calls.map(([, init]) => JSON.parse(init.body))
    expect(bodies).toEqual([
      { message: 'un', idempotency_key: 'k-1' },
      { message: 'un', idempotency_key: 'k-1' },
    ])
  })

  test('replaces is on the wire when the message corrects the last one', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => createMockResponse([{ type: 'RUN_FINISHED', runId: 'r1' }]))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAgUiStream({}))

    await act(async () => {
      await result.current.send('corrigé', 'k-2', 'm-9')
    })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      message: 'corrigé',
      idempotency_key: 'k-2',
      replaces: 'm-9',
    })
  })

  test('stop closes the stream without calling it an error', async () => {
    const onError = vi.fn()
    const fetchMock = vi.fn((_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(new DOMException('The operation was aborted.', 'AbortError')))
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useAgUiStream({ onError }))

    let sending: Promise<void> = Promise.resolve()
    act(() => {
      sending = result.current.send('long')
    })
    expect(result.current.isStreaming).toBe(true)

    await act(async () => {
      result.current.stop()
      await sending
    })

    expect(onError).not.toHaveBeenCalled()
    expect(result.current.isStreaming).toBe(false)
  })

  // MAG-638: the agent keeps running a turn whose stream was cut during a slow tool; the same
  // request, same key, follows it and brings the answer back.
  describe('a stream cut while the turn goes on', () => {
    test('follows the turn again instead of reporting a failure', async () => {
      vi.useFakeTimers()
      const onError = vi.fn()
      const onTextEnd = vi.fn()
      const onRunFinished = vi.fn()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(
          createCutResponse([
            { type: 'RUN_STARTED', runId: 'r1' },
            { type: 'TOOL_CALL_START', toolCallId: 'tc1', toolName: 'get_upcoming_events' },
          ]),
        )
        .mockResolvedValueOnce(
          createMockResponse([
            { type: 'RUN_STARTED', runId: 'r1' },
            { type: 'TEXT_MESSAGE_START', messageId: 'm1', role: 'assistant' },
            { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm1', delta: 'Samedi' },
            { type: 'TEXT_MESSAGE_END', messageId: 'm1' },
            { type: 'RUN_FINISHED', runId: 'r1' },
          ]),
        )
      vi.stubGlobal('fetch', fetchMock)

      const { result } = renderHook(() => useAgUiStream({ onError, onTextEnd, onRunFinished }))

      await act(async () => {
        const sending = result.current.send('Quand Lubus ?', 'k-1')
        await vi.runAllTimersAsync()
        await sending
      })

      expect(onError).not.toHaveBeenCalled()
      expect(onTextEnd).toHaveBeenCalledWith('m1')
      expect(onRunFinished).toHaveBeenCalledTimes(1)
      const keys = fetchMock.mock.calls.map(([, init]) => JSON.parse(init.body).idempotency_key)
      expect(keys).toEqual(['k-1', 'k-1'])
    })

    test('a stream closed before the run finished is followed again too', async () => {
      vi.useFakeTimers()
      const onError = vi.fn()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(createMockResponse([{ type: 'RUN_STARTED', runId: 'r1' }]))
        .mockResolvedValueOnce(createMockResponse([{ type: 'RUN_STARTED', runId: 'r1' }, { type: 'RUN_FINISHED', runId: 'r1' }]))
      vi.stubGlobal('fetch', fetchMock)

      const { result } = renderHook(() => useAgUiStream({ onError }))

      await act(async () => {
        const sending = result.current.send('test')
        await vi.runAllTimersAsync()
        await sending
      })

      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(onError).not.toHaveBeenCalled()
    })

    test('still fails when the stream stays down', async () => {
      vi.useFakeTimers()
      const onError = vi.fn()
      const fetchMock = vi.fn().mockImplementation(async () => createCutResponse([{ type: 'RUN_STARTED', runId: 'r1' }]))
      vi.stubGlobal('fetch', fetchMock)

      const { result } = renderHook(() => useAgUiStream({ onError }))

      await act(async () => {
        const sending = result.current.send('test')
        await vi.runAllTimersAsync()
        await sending
      })

      expect(onError).toHaveBeenCalledTimes(1)
      expect(onError).toHaveBeenCalledWith('network error')
      expect(fetchMock).toHaveBeenCalledTimes(4)
    })

    test('an HTTP error while following is a failure, not a retry', async () => {
      vi.useFakeTimers()
      const onError = vi.fn()
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(createCutResponse([{ type: 'RUN_STARTED', runId: 'r1' }]))
        .mockResolvedValueOnce({ ok: false, status: 502 })
      vi.stubGlobal('fetch', fetchMock)

      const { result } = renderHook(() => useAgUiStream({ onError }))

      await act(async () => {
        const sending = result.current.send('test')
        await vi.runAllTimersAsync()
        await sending
      })

      expect(onError).toHaveBeenCalledWith('HTTP 502')
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })
  })
})
