import { useState, useRef, useCallback, useEffect } from 'react'

export type RecorderState = 'idle' | 'recording' | 'processing'

const MAX_DURATION_S = 120

export function useVoiceRecorder() {
  const [state, setState] = useState<RecorderState>('idle')
  const [duration, setDuration] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const resolveRef = useRef<((blob: Blob) => void) | null>(null)

  const cleanup = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
    if (mediaRecorderRef.current?.state === 'recording') {
      mediaRecorderRef.current.stop()
    }
    mediaRecorderRef.current?.stream.getTracks().forEach((t) => t.stop())
    mediaRecorderRef.current = null
    chunksRef.current = []
  }, [])

  useEffect(() => cleanup, [cleanup])

  const startRecording = useCallback(async () => {
    setError(null)
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      setError("Accès au microphone refusé. Vérifie les permissions de ton navigateur.")
      return
    }

    const recorder = new MediaRecorder(stream, {
      mimeType: MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : 'audio/webm',
    })
    mediaRecorderRef.current = recorder
    chunksRef.current = []

    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunksRef.current.push(e.data)
    }

    recorder.onstop = () => {
      const blob = new Blob(chunksRef.current, { type: recorder.mimeType })
      if (resolveRef.current) {
        resolveRef.current(blob)
        resolveRef.current = null
      }
    }

    recorder.start(250)
    setState('recording')
    setDuration(0)
    timerRef.current = setInterval(() => {
      setDuration((d) => {
        if (d + 1 >= MAX_DURATION_S) {
          // Auto-stop at max duration — handled via stopRecording below
          mediaRecorderRef.current?.stop()
        }
        return d + 1
      })
    }, 1000)
  }, [])

  const stopRecording = useCallback((): Promise<Blob> => {
    return new Promise((resolve) => {
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
      resolveRef.current = resolve
      setState('processing')
      if (mediaRecorderRef.current?.state === 'recording') {
        mediaRecorderRef.current.stop()
      }
      mediaRecorderRef.current?.stream.getTracks().forEach((t) => t.stop())
    })
  }, [])

  const cancelRecording = useCallback(() => {
    cleanup()
    resolveRef.current = null
    setState('idle')
    setDuration(0)
  }, [cleanup])

  const resetState = useCallback(() => {
    setState('idle')
    setDuration(0)
    setError(null)
  }, [])

  return { state, duration, error, startRecording, stopRecording, cancelRecording, resetState }
}
