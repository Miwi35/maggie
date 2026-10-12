import { describe, expect, it, vi } from 'vitest'
import { loadWithReload } from './loadWithReload'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = { ...initial }
  return {
    getItem: (key: string) => data[key] ?? null,
    setItem: (key: string, value: string) => {
      data[key] = value
    },
    removeItem: (key: string) => {
      delete data[key]
    },
    data,
  }
}

const staleChunk = () =>
  new TypeError('Failed to fetch dynamically imported module: https://maggieai.fr/admin/assets/App-CA1cCT4_.js')

describe('loadWithReload', () => {
  it('returns the module when it loads', async () => {
    const reload = vi.fn()
    const storage = memoryStorage()

    await expect(loadWithReload(() => Promise.resolve('app'), { storage, reload, now: () => 1000 })).resolves.toBe('app')
    expect(reload).not.toHaveBeenCalled()
  })

  it('reloads the page once when the chunk is gone after a deploy', async () => {
    const reload = vi.fn()
    const storage = memoryStorage()

    const pending = loadWithReload(() => Promise.reject(staleChunk()), { storage, reload, now: () => 1000 })
    const settled = vi.fn()
    void pending.then(settled, settled)
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(reload).toHaveBeenCalledTimes(1)
    expect(settled).not.toHaveBeenCalled()
  })

  it('gives up with the error when the reload did not fix it', async () => {
    const reload = vi.fn()
    const storage = memoryStorage()
    const options = { storage, reload, now: () => 1000 }
    void loadWithReload(() => Promise.reject(staleChunk()), options)
    await new Promise((resolve) => setTimeout(resolve, 0))

    await expect(loadWithReload(() => Promise.reject(staleChunk()), { ...options, now: () => 5000 })).rejects.toThrow(
      'Failed to fetch dynamically imported module',
    )
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('reloads again for a later deploy, once the previous attempt is old', async () => {
    const reload = vi.fn()
    const storage = memoryStorage()
    void loadWithReload(() => Promise.reject(staleChunk()), { storage, reload, now: () => 1000 })
    await new Promise((resolve) => setTimeout(resolve, 0))

    void loadWithReload(() => Promise.reject(staleChunk()), { storage, reload, now: () => 1000 + 10 * 60_000 })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('does not reload for an unrelated error', async () => {
    const reload = vi.fn()
    const storage = memoryStorage()

    await expect(
      loadWithReload(() => Promise.reject(new Error('boom')), { storage, reload, now: () => 1000 }),
    ).rejects.toThrow('boom')
    expect(reload).not.toHaveBeenCalled()
  })
})
