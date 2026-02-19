import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useVoiceRecorder } from './useVoiceRecorder'

// Mock MediaRecorder
class MockMediaRecorder {
  state = 'inactive'
  ondataavailable: ((e: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  stream: { getTracks: () => { stop: () => void }[] }

  static isTypeSupported = vi.fn().mockReturnValue(true)

  constructor(stream: MediaStream) {
    this.stream = { getTracks: () => stream.getTracks() }
  }

  start = vi.fn().mockImplementation(() => {
    this.state = 'recording'
  })

  stop = vi.fn().mockImplementation(() => {
    this.state = 'inactive'
    this.ondataavailable?.({ data: new Blob(['audio-data'], { type: 'audio/webm' }) })
    this.onstop?.()
  })
}

vi.stubGlobal('MediaRecorder', MockMediaRecorder)

const mockGetUserMedia = vi.fn().mockResolvedValue({
  getTracks: () => [{ stop: vi.fn() }],
} as unknown as MediaStream)

Object.defineProperty(navigator, 'mediaDevices', {
  value: { getUserMedia: mockGetUserMedia },
  writable: true,
})

describe('useVoiceRecorder', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockGetUserMedia.mockResolvedValue({
      getTracks: () => [{ stop: vi.fn() }],
    } as unknown as MediaStream)
    vi.stubGlobal('MediaRecorder', MockMediaRecorder)
  })

  test('starts in idle state', () => {
    const { result } = renderHook(() => useVoiceRecorder())
    expect(result.current.state).toBe('idle')
    expect(result.current.duration).toBe(0)
    expect(result.current.error).toBeNull()
  })

  test('transitions to recording state', async () => {
    const { result } = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await result.current.startRecording()
    })

    expect(result.current.state).toBe('recording')
    expect(mockGetUserMedia).toHaveBeenCalledWith({ audio: true })
  })

  test('transitions to processing then returns blob on stop', async () => {
    const { result } = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await result.current.startRecording()
    })

    let blob: Blob | undefined
    await act(async () => {
      blob = await result.current.stopRecording()
    })

    expect(blob).toBeInstanceOf(Blob)
    expect(result.current.state).toBe('processing')
  })

  test('sets error on permission denied', async () => {
    mockGetUserMedia.mockRejectedValueOnce(new DOMException('Permission denied'))

    const { result } = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await result.current.startRecording()
    })

    expect(result.current.state).toBe('idle')
    expect(result.current.error).toContain('microphone')
  })

  test('cancel returns to idle', async () => {
    const { result } = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await result.current.startRecording()
    })

    act(() => {
      result.current.cancelRecording()
    })

    expect(result.current.state).toBe('idle')
    expect(result.current.duration).toBe(0)
  })
})
