import { useEffect, useRef } from 'react'
import { keyBelongsElsewhere } from '../../../components/activity/keyBelongsElsewhere'

interface StepKeys {
  /** → or Espace; undefined when the next step is held back. */
  onNext?: () => void
  /** ←; undefined when there is nowhere to go back to. */
  onPrevious?: () => void
}

/** → / Espace go on, ← goes back. A field, a dialog or a focused button keeps its own keys. */
export const useStepKeys = ({ onNext, onPrevious }: StepKeys): void => {
  const handlers = useRef<StepKeys>({})
  useEffect(() => {
    handlers.current = { onNext, onPrevious }
  })

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat || event.altKey || event.ctrlKey || event.metaKey) return
      const next = event.key === 'ArrowRight' || event.key === ' '
      const previous = event.key === 'ArrowLeft'
      if (!next && !previous) return
      if (keyBelongsElsewhere(event)) return
      // Espace on a focused button or link presses it: that is its own key.
      const target = event.target as HTMLElement | null
      if (event.key === ' ' && target?.closest?.('button, a, [role="button"]')) return
      const handler = next ? handlers.current.onNext : handlers.current.onPrevious
      if (!handler) return
      event.preventDefault()
      handler()
    }
    window.addEventListener('keydown', onKey)

    return () => window.removeEventListener('keydown', onKey)
  }, [])
}
