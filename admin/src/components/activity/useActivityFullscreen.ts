import { useCallback, useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'

export type FullscreenMode = 'pending' | 'fullscreen' | 'window'

/**
 * Takes the whole screen the window is on for the element, as soon as the activity opens
 * (the click that launched it still counts as the gesture the browser asks for). A browser
 * that refuses leaves the element covering the whole window: `mode` says which one it is.
 *
 * Leaving full screen from outside the activity (Échap) calls `onLeft`; leaving it through
 * `leave()` does not, so the activity's own exit is not taken for a second one.
 */
export const useActivityFullscreen = (target: RefObject<HTMLElement | null>, onLeft: () => void) => {
  const [mode, setMode] = useState<FullscreenMode>('pending')
  const entered = useRef(false)
  const leaving = useRef(false)
  const element = useRef<HTMLElement | null>(null)
  const onLeftRef = useRef(onLeft)

  useEffect(() => {
    onLeftRef.current = onLeft
  })

  useEffect(() => {
    const node = target.current
    if (!node) return
    element.current = node
    leaving.current = false

    const onChange = () => {
      if (document.fullscreenElement === node) {
        entered.current = true
        setMode('fullscreen')

        return
      }
      if (!entered.current) return
      entered.current = false
      setMode('window')
      if (!leaving.current) onLeftRef.current()
    }

    document.addEventListener('fullscreenchange', onChange)

    if (typeof node.requestFullscreen === 'function') {
      node.requestFullscreen().then(
        () => {
          entered.current = true
          setMode('fullscreen')
        },
        () => setMode('window'),
      )
    } else {
      setMode('window')
    }

    return () => {
      document.removeEventListener('fullscreenchange', onChange)
      leaving.current = true
      if (document.fullscreenElement === node) void document.exitFullscreen?.()
    }
  }, [target])

  /** Gives the screen back without telling `onLeft`. */
  const leave = useCallback(async () => {
    leaving.current = true
    entered.current = false
    if (element.current && document.fullscreenElement === element.current) {
      try {
        await document.exitFullscreen()
      } catch {
        // Already out.
      }
    }
  }, [])

  return { mode, leave }
}
