import { useEffect, useRef, useState } from 'react'
import { localDay } from '../../dates'
import { digestBlockOf, fetchLatestDigest } from '../maggie/digest/digestApi'
import type { DigestBlock, DigestMessage } from '../maggie/digest/digestApi'
import type { HomeDay } from './homeDay'

export interface DigestTileState {
  id: string
  day: string
  durationMs: number
}

type Read = { message: DigestMessage; block: DigestBlock } | null

const isShown = (read: Read, now: Date): boolean =>
  !!read && read.block.day === localDay(now) && read.block.openedAt === null && now.getTime() < new Date(read.block.expiresAt).getTime()

/**
 * The tile of the unopened digest (MAG-960): read at mount and each time the window comes back, no Mercure stream;
 * visible the day of the digest, while `openedAt` is null and before `expiresAt`, as on the phone (MAG-808).
 * A failed read keeps the last answer.
 */
export function useDigestTile(day: HomeDay): DigestTileState | null {
  const latest = useRef<Read>(null)
  const [read, setRead] = useState<Read>(null)
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const message = await fetchLatestDigest()
        const block = digestBlockOf(message)
        latest.current = message && block ? { message, block } : null
      } catch {
        // keep the last answer
      }
      if (!alive) return
      setRead(latest.current)
      setNow(new Date())
    }
    void load()
    const onFocus = () => void load()
    window.addEventListener('focus', onFocus)

    return () => {
      alive = false
      window.removeEventListener('focus', onFocus)
    }
  }, [])

  const expiresAt = read ? new Date(read.block.expiresAt).getTime() : null
  useEffect(() => {
    if (expiresAt === null) return
    const wait = expiresAt - Date.now()
    if (wait <= 0) return
    const timer = setTimeout(() => setNow(new Date()), Math.min(wait, 2 ** 31 - 1))

    return () => clearTimeout(timer)
  }, [expiresAt])

  if (day !== 'today' || !read || !isShown(read, now)) return null

  return { id: read.message.id, day: read.block.day, durationMs: read.message.audio?.durationMs ?? 0 }
}
