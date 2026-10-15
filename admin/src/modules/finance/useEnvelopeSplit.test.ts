import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useEnvelopeSplit } from './useEnvelopeSplit'

const children = [
  { envelopeId: 'bars', amountCents: 12000 },
  { envelopeId: 'cinema', amountCents: 3000 },
]

describe('useEnvelopeSplit', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('puts the shares on the mother and reports success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }))
    const { result } = renderHook(() => useEnvelopeSplit())

    let outcome
    await act(async () => {
      outcome = await result.current.save('loisirs', children)
    })

    expect(outcome).toEqual({ ok: true })
    expect(result.current.saving).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/finance/envelopes/loisirs/split', {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
      body: JSON.stringify({ children }),
    })
  })

  test('tells a refusal (400) from a failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400 }))
    const { result } = renderHook(() => useEnvelopeSplit())

    let outcome
    await act(async () => {
      outcome = await result.current.save('loisirs', children)
    })
    expect(outcome).toEqual({ ok: false, refused: true })

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
    await act(async () => {
      outcome = await result.current.save('loisirs', children)
    })
    expect(outcome).toEqual({ ok: false, refused: false })
  })
})
