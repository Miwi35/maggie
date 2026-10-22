import { useCallback, useEffect, useRef } from 'react'

export type LiveKind = 'events' | 'tasks' | 'meals' | 'proactions'

export const ALL_LIVE_KINDS: LiveKind[] = ['events', 'tasks', 'meals', 'proactions']

/** A burst of messages (a recurring series saved, a day planned) is read once, after the burst. */
export const LIVE_DEBOUNCE_MS = 1000
/** The read that found nothing new may have come before the worker indexed the row: one more. */
export const LIVE_RETRY_MS = 4000

const TOPIC = /^\/api\/(events|tasks|meals|proactions)\//

/** What a message touched, from the IRI it carries; everything when it names nothing. */
export function touchedKinds(data?: string): LiveKind[] {
  try {
    const iri = (JSON.parse(data ?? '') as { '@id'?: unknown })['@id']
    const kind = typeof iri === 'string' ? TOPIC.exec(iri)?.[1] : undefined
    if (kind) return [kind as LiveKind]
  } catch {
    // Not JSON: nothing says what changed.
  }

  return ALL_LIVE_KINDS
}

/**
 * Turns Mercure messages into reads: one read per burst, of what the burst touched.
 *
 * The collections are served from Elasticsearch, indexed after the message leaves, so
 * the read waits for the burst to end; if it brings nothing new, it is made once more.
 * `read` resolves `true` when what it fetched differs from what the screen holds.
 */
export function useLiveRead(read: (kinds: LiveKind[]) => Promise<boolean>): (data?: string) => void {
  const readRef = useRef(read)
  useEffect(() => {
    readRef.current = read
  })

  const pending = useRef(new Set<LiveKind>())
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)
  const retries = useRef<ReturnType<typeof setTimeout>[]>([])
  useEffect(
    () => () => {
      if (debounce.current) clearTimeout(debounce.current)
      retries.current.forEach(clearTimeout)
    },
    [],
  )

  return useCallback((data?: string) => {
    for (const kind of touchedKinds(data)) pending.current.add(kind)
    if (debounce.current) clearTimeout(debounce.current)
    debounce.current = setTimeout(() => {
      const kinds = [...pending.current]
      pending.current.clear()
      void readRef.current(kinds).then((changed) => {
        if (!changed) retries.current.push(setTimeout(() => void readRef.current(kinds), LIVE_RETRY_MS))
      })
    }, LIVE_DEBOUNCE_MS)
  }, [])
}
