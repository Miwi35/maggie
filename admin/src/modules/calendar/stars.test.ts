import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { fetchStars, ofMonth, shiftMonth } from './stars'

describe('shiftMonth', () => {
  test.each([
    ['2026-10', -1, '2026-09'],
    ['2026-10', 1, '2026-11'],
    ['2026-01', -1, '2025-12'],
    ['2026-12', 1, '2027-01'],
    ['2026-03', -14, '2025-01'],
    ['2026-10', 0, '2026-10'],
  ])('%s moved by %i is %s', (month, n, expected) => {
    expect(shiftMonth(month, n)).toBe(expected)
  })
})

describe('ofMonth', () => {
  test('elides before a vowel and writes « de » before a consonant', () => {
    expect(ofMonth('2026-10')).toBe("d'octobre")
    expect(ofMonth('2026-04')).toBe("d'avril")
    expect(ofMonth('2026-08')).toBe("d'août")
    expect(ofMonth('2026-11')).toBe('de novembre')
    expect(ofMonth('2026-02')).toBe('de février')
  })
})

describe('fetchStars', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    mockFetch.mockReset()
  })

  test('asks for the month given, or for none to get the current one', async () => {
    const body = { month: '2026-09', count: 7, total: 31, best: null, latestAt: null }
    mockFetch.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(body), { status: 200 })))

    await expect(fetchStars('2026-09')).resolves.toEqual(body)
    await fetchStars()

    expect(String(mockFetch.mock.calls[0][0])).toMatch(/\/stars\?month=2026-09$/)
    expect(String(mockFetch.mock.calls[1][0])).toMatch(/\/stars$/)
  })

  test('refuses an answer in error', async () => {
    mockFetch.mockResolvedValue(new Response('{}', { status: 400 }))

    await expect(fetchStars('nope')).rejects.toThrow('Étoiles : 400')
  })
})
