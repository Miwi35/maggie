import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { NOTHING_HEARD, useTranscription } from './useTranscription'

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

  test('asks for no cleanup by default, and for one when dictating into a field', async () => {
    const sentModes: (FormDataEntryValue | null)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        sentModes.push((init.body as FormData).get('cleanup'))
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ raw: 'euh oui', clean: 'oui' }) })
      }),
    )

    const { result } = renderHook(() => useTranscription())
    const blob = new Blob(['audio'], { type: 'audio/webm' })

    await act(async () => {
      await result.current.transcribe(blob)
      await result.current.transcribe(blob, 'auto')
    })

    expect(sentModes).toEqual(['none', 'auto'])
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

  test('an empty transcript is « nothing heard », not a silent failure', async () => {
    // The server refused the transcript because the clip held no speech — Whisper
    // invents subtitle credits over silence (retour de recette MAG-222). The owner
    // must be told rather than watch his dictation vanish.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ raw: '', clean: '' }),
      }),
    )

    const { result } = renderHook(() => useTranscription())
    let text: string | null = null

    await act(async () => {
      text = await result.current.transcribe(new Blob(['audio'], { type: 'audio/webm' }))
    })

    expect(text).toBeNull()
    expect(result.current.error).toBe(NOTHING_HEARD)
  })
})
