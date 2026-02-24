import { useCallback, useEffect, useRef, useState } from 'react'

// @ts-expect-error — openwakeword-wasm-browser has no type declarations
import { WakeWordEngine } from 'openwakeword-wasm-browser'

interface UseWakeWordOptions {
  onDetected: () => void
}

export function useWakeWord({ onDetected }: UseWakeWordOptions) {
  const [enabled, setEnabled] = useState(() => localStorage.getItem('wakeWordEnabled') === 'true')
  const [isLoaded, setIsLoaded] = useState(false)
  const [isListening, setIsListening] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [paused, setPaused] = useState(false)
  const onDetectedRef = useRef(onDetected)
  const engineRef = useRef<InstanceType<typeof WakeWordEngine> | null>(null)

  useEffect(() => {
    onDetectedRef.current = onDetected
  }, [onDetected])

  // Initialize engine when enabled
  useEffect(() => {
    if (!enabled || engineRef.current) return

    let cancelled = false

    const engine = new WakeWordEngine({
      baseAssetUrl: '/admin/openwakeword',
      keywords: ['maggie'],
      modelFiles: { maggie: 'maggie.onnx' },
      detectionThreshold: 0.5,
      cooldownMs: 2000,
    })

    engine.on('detect', () => {
      onDetectedRef.current()
    })

    engine.on('error', (err: unknown) => {
      console.error('OpenWakeWord error:', err)
      setError(err instanceof Error ? err : new Error(String(err)))
    })

    engine
      .load()
      .then(() => {
        if (cancelled) return
        engineRef.current = engine
        setIsLoaded(true)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        console.error('OpenWakeWord init error:', err)
        setError(err instanceof Error ? err : new Error(String(err)))
      })

    return () => {
      cancelled = true
      engine.stop()
      engineRef.current = null
      setIsLoaded(false)
      setIsListening(false)
    }
  }, [enabled])

  // Auto-start listening once loaded (and not paused)
  useEffect(() => {
    const engine = engineRef.current
    if (!engine || !isLoaded || !enabled || paused || isListening) return

    engine
      .start()
      .then(() => setIsListening(true))
      .catch((err: unknown) => console.error('OpenWakeWord start error:', err))
  }, [isLoaded, enabled, paused, isListening])

  const toggleEnabled = useCallback(
    (value?: boolean) => {
      const next = value ?? !enabled
      setEnabled(next)
      localStorage.setItem('wakeWordEnabled', String(next))
      if (!next && engineRef.current) {
        engineRef.current.stop()
        setIsListening(false)
      }
    },
    [enabled],
  )

  const pause = useCallback(() => {
    setPaused(true)
    if (engineRef.current && isListening) {
      engineRef.current.stop()
      setIsListening(false)
    }
  }, [isListening])

  const resume = useCallback(() => {
    setPaused(false)
  }, [])

  return {
    enabled,
    isLoaded,
    isListening,
    error,
    toggleEnabled,
    pause,
    resume,
  }
}
