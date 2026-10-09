import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { fetchApiDocumentation } from './apiDocumentation'

const parsed = { api: { resources: [] }, response: new Response(), status: 200 }
const noWait = [0, 0, 0]

describe('fetchApiDocumentation', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  test.each([
    ['the API restarts (503)', { status: 503 }],
    ['the proxy answers 502', { status: 502 }],
    ['the request gets no answer', { error: new TypeError('Failed to fetch') }],
  ])('waits and retries when %s', async (_label, failure) => {
    const parse = vi.fn().mockRejectedValueOnce(failure).mockRejectedValueOnce(failure).mockResolvedValue(parsed)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).resolves.toBe(parsed)
    expect(parse).toHaveBeenCalledTimes(3)
  })

  test('gives up with the parser failure once the retries are spent', async () => {
    const failure = { status: 503 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(noWait.length + 1)
  })

  test('does not retry an answer that will not change (404)', async () => {
    const failure = { status: 404 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(1)
  })

  test('drops the session and reloads on a 401 instead of retrying', async () => {
    localStorage.setItem('token', 'stale')
    const reload = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, reload } as Location)
    const failure = { status: 401 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('token')).toBeNull()
    expect(reload).toHaveBeenCalled()
  })
})
