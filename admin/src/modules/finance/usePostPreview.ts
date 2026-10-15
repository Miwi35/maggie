import { useEffect, useState } from 'react'

export const PREVIEW_DELAY_MS = 300

export interface PreviewState<R> {
  /** `loading` keeps the previous result on screen until the new one lands. */
  status: 'idle' | 'loading' | 'ready' | 'error'
  result: R | null
  error: string | null
}

interface Settled<R> {
  key: string
  result: R | null
  error: string | null
}

export const PREVIEW_UNAVAILABLE = "L'aperçu est indisponible pour le moment."

/** A refusal whose message came from the API and can be shown as is. */
class PreviewRefused extends Error {}

async function requestPreview<R>(url: string, body: string, signal: AbortSignal): Promise<R> {
  const token = localStorage.getItem('token')
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body,
    signal,
  })

  if (!res.ok) {
    const detail = await res.json().then(
      (data: { error?: unknown }) => (typeof data.error === 'string' ? data.error : null),
      () => null,
    )
    throw new PreviewRefused(detail ?? PREVIEW_UNAVAILABLE)
  }

  return (await res.json()) as R
}

/**
 * What a POST preview endpoint answers for the criteria being typed.
 *
 * A request leaves `delayMs` after the last change, and the previous one is
 * aborted: a slow answer to an older criteria must never overwrite the newer
 * one. `null` criteria means there is nothing to ask yet.
 */
export function usePostPreview<R>(url: string, criteria: object | null, delayMs: number): PreviewState<R> {
  const [settled, setSettled] = useState<Settled<R> | null>(null)
  const key = JSON.stringify(criteria)
  const blank = criteria === null

  useEffect(() => {
    if (blank) {
      return
    }

    const controller = new AbortController()
    const timer = setTimeout(() => {
      requestPreview<R>(url, key, controller.signal).then(
        (result) => {
          if (!controller.signal.aborted) {
            setSettled({ key, result, error: null })
          }
        },
        (e: unknown) => {
          if (!controller.signal.aborted) {
            setSettled({ key, result: null, error: e instanceof PreviewRefused ? e.message : PREVIEW_UNAVAILABLE })
          }
        },
      )
    }, delayMs)

    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [url, key, blank, delayMs])

  if (blank) {
    return { status: 'idle', result: null, error: null }
  }

  if (settled?.key === key) {
    return settled.error === null
      ? { status: 'ready', result: settled.result, error: null }
      : { status: 'error', result: null, error: settled.error }
  }

  return { status: 'loading', result: settled?.result ?? null, error: null }
}
