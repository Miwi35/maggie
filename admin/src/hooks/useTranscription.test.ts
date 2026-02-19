import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useTranscription } from './useTranscription'

describe('useTranscription', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('starts with loading false and no error', () => {
    const { result } = renderHook(() => useTranscription())
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  test('returns clean text on successful transcription', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ raw: 'euh bonjour', clean: 'Bonjour.' }),
      }),
    )

    const { result } = renderHook(() => useTranscription())
    let text: string | null = null

    await act(async () => {
      text = await result.current.transcribe(new Blob(['audio'], { type: 'audio/webm' }))
    })

    expect(text).toBe('Bonjour.')
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()

    expect(fetch).toHaveBeenCalledWith('/agent/transcribe', {
      method: 'POST',
      headers: { Authorization: 'Bearer test-jwt-token' },
      body: expect.any(FormData),
    })
  })

  test('returns null and sets error on failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        text: () => Promise.resolve('Internal server error'),
      }),
    )

    const { result } = renderHook(() => useTranscription())
    let text: string | null = null

    await act(async () => {
      text = await result.current.transcribe(new Blob(['audio'], { type: 'audio/webm' }))
    })

    expect(text).toBeNull()
    expect(result.current.error).toBeTruthy()
  })

  test('returns null and sets error on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network error')))

    const { result } = renderHook(() => useTranscription())
    let text: string | null = null

    await act(async () => {
      text = await result.current.transcribe(new Blob(['audio'], { type: 'audio/webm' }))
    })

    expect(text).toBeNull()
    expect(result.current.error).toBe('Network error')
  })
})
