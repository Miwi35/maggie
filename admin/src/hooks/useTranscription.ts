import { useState, useCallback } from 'react'

const TRANSCRIBE_URL = '/agent/transcribe'

/**
 * What the transcript is for (MAG-222). A message on its way to Maggie is never
 * cleaned by the model — she reads through a hesitation herself — while a dictation
 * dropped into a field is, with the fast model and only when it needs it.
 */
export type CleanupMode = 'none' | 'auto'

/**
 * What the owner is told when the server found no speech behind the recording and
 * refused its transcript. Whisper answers the subtitle boilerplate it was trained on
 * when it is given silence — « Thank you for watching » reached the chat that way
 * (retour de recette MAG-222) — so an empty answer is a result, not a failure.
 */
export const NOTHING_HEARD = "Je n'ai rien entendu"

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
      const text = data.clean || data.raw || null
      if (!text) {
        setError(NOTHING_HEARD)
      }
      return text
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
