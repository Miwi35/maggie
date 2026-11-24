import { afterEach, describe, expect, test, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { stubAislesApi } from '../../test/aislesApi'
import { useAislesByStore } from './useStoreAisles'

const AISLES = [
  { id: 'u-fresh', store: 'u', name: 'Frais', position: 1 },
  { id: 'b-bulk', store: 'b', name: 'Vrac', position: 1 },
]

describe('useAislesByStore', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('reads the aisles of each store once, and an empty list for a store that fails', async () => {
    const { fetchMock } = stubAislesApi(AISLES)
    const { result } = renderHook(() => useAislesByStore(['u', 'b', 'u']))

    await waitFor(() => expect(result.current.size).toBe(2))
    expect(result.current.get('u')?.map((a) => a.name)).toEqual(['Frais'])
    expect(result.current.get('b')?.map((a) => a.name)).toEqual(['Vrac'])
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fetchMock.mockImplementation(() => Promise.reject(new Error('down')))
    const failing = renderHook(() => useAislesByStore(['x']))
    await waitFor(() => expect(failing.result.current.get('x')).toEqual([]))
  })
})
