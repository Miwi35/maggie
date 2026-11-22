import { useEffect, useRef, useState } from 'react'
import { digestBlockOf, fetchDigestMessage, fetchLatestDigest, fetchWeather, markDigestOpened } from './digestApi'
import type { DigestBlock, DigestMessage, Weather } from './digestApi'

/**
 * The digest of `id` (the latest one without it), its day's weather, and whether this reading is the first:
 * then it is marked opened, once, and the failure of that mark is swallowed (the screen is already shown).
 * A failed weather leaves the card out; a failed digest read is `error`.
 */
export function useDigest(id: string | null) {
  const [message, setMessage] = useState<DigestMessage | null>(null)
  const [block, setBlock] = useState<DigestBlock | null>(null)
  const [weather, setWeather] = useState<Weather | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [firstOpening, setFirstOpening] = useState(false)
  const latest = useRef(0)

  useEffect(() => {
    const request = ++latest.current
    const controller = new AbortController()
    setLoading(true)
    setError(false)
    setFirstOpening(false)

    void (async () => {
      try {
        const found = id ? await fetchDigestMessage(id, controller.signal) : await fetchLatestDigest(controller.signal)
        const digest = digestBlockOf(found)
        if (request !== latest.current) return
        if (!found || !digest) {
          setMessage(null)
          setBlock(null)
          setWeather(null)
          return
        }
        const first = digest.openedAt === null
        const read = await fetchWeather(digest.day, controller.signal).catch(() => null)
        if (request !== latest.current) return
        setMessage(found)
        setBlock(digest)
        setWeather(read)
        setFirstOpening(first)
        if (first) void markDigestOpened(found.id).catch(() => undefined)
      } catch {
        if (request === latest.current) setError(true)
      } finally {
        if (request === latest.current) setLoading(false)
      }
    })()

    return () => {
      latest.current++
      controller.abort()
    }
  }, [id])

  return { message, block, weather, loading, error, firstOpening }
}
