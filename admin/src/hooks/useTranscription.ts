import { useState, useCallback } from 'react'

const TRANSCRIBE_URL = '/agent/transcribe'

/**
 * What the transcript is for (MAG-222). A message on its way to Maggie is never
 * cleaned by the model — she reads through a hesitation herself — while a dictation
 * dropped into a field is, with the fast model and only when it needs it.
 */
export type CleanupMode = 'none' | 'auto'

export function useTranscription() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const transcribe = useCallback(async (blob: Blob, cleanup: CleanupMode = 'none'): Promise<string | null> => {
    setLoading(true)
    setError(null)

    try {
      const formData = new FormData()
      formData.append('audio', blob, 'recording.webm')
      formData.append('cleanup', cleanup)

      const token = localStorage.getItem('token')
      const response = await fetch(TRANSCRIBE_URL, {
        method: 'POST',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: formData,
      })

      if (!response.ok) {
        const detail = await response.text()
        throw new Error(detail || `Erreur ${response.status}`)
      }

      const data = await response.json()
      return data.clean || data.raw || null
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Erreur de transcription'
      setError(message)
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  return { transcribe, loading, error }
}
