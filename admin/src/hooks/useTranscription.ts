import { useState, useCallback } from 'react'

const TRANSCRIBE_URL = '/agent/transcribe'

export function useTranscription() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const transcribe = useCallback(async (blob: Blob): Promise<string | null> => {
    setLoading(true)
    setError(null)

    try {
      const formData = new FormData()
      formData.append('audio', blob, 'recording.webm')

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
