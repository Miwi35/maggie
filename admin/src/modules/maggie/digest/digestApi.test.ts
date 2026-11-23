import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { digestMoment, digestBlockOf, fetchDigestMessage, fetchLatestDigest, fetchWeather, weatherLabels } from './digestApi'

const block = { type: 'digest', day: '2026-10-27', text: 'Bonjour', expiresAt: '2026-10-27T14:00:00', openedAt: null }
const reply = (status: number, body: unknown = null) => ({ ok: status < 400, status, json: async () => body }) as Response

beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
afterEach(() => vi.unstubAllGlobals())

describe('digestApi', () => {
  it('reads the linked message out of its context', async () => {
    vi.mocked(fetch).mockResolvedValue(reply(200, { messages: [{ id: 'A', blocks: [] }, { id: 'B', blocks: [block] }] }))
    const message = await fetchDigestMessage('B')
    expect(message?.id).toBe('B')
    expect(digestBlockOf(message)?.day).toBe('2026-10-27')
    expect(String(vi.mocked(fetch).mock.calls[0][0])).toContain('/agent/messages/context?around=B')
  })

  it('the latest digest is null without one', async () => {
    vi.mocked(fetch).mockResolvedValue(reply(200, []))
    expect(await fetchLatestDigest()).toBeNull()
  })

  it('a 404 weather is no card', async () => {
    vi.mocked(fetch).mockResolvedValue(reply(404))
    expect(await fetchWeather('2026-10-27')).toBeNull()
  })

  it('the moment says the day and the length, long and short', () => {
    expect(digestMoment('2026-10-27', 72_000, 'long')).toBe('Mardi 27 octobre · 1:12')
    expect(digestMoment('2026-10-27', 72_000, 'short')).toMatch(/^mar\. 27 oct\.? · 1:12$/)
  })

  it('snow wins over rain in the quantity', () => {
    expect(weatherLabels({ condition: 'partly_cloudy', rainMm: 4.2 })).toEqual({ condition: 'Éclaircies', quantity: '4 mm de pluie' })
    expect(weatherLabels({ rainMm: 4, snowCm: 3 }).quantity).toBe('3 cm de neige')
    expect(weatherLabels({ rainMm: 0.4, condition: 'unknown' })).toEqual({ condition: null, quantity: null })
  })
})
