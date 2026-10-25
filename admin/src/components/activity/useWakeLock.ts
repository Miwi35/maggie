import { useEffect } from 'react'

/**
 * Keeps the screen on while an activity runs: the lock is released on leaving, and taken again
 * when the tab comes back (the browser drops it whenever the tab is hidden). A browser without
 * the API, or one that refuses, leaves the screen to its own timeout.
 */
export const useWakeLock = (): void => {
  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.wakeLock) return

    let sentinel: WakeLockSentinel | null = null
    let leaving = false

    const acquire = async () => {
      if (document.visibilityState !== 'visible' || (sentinel && !sentinel.released)) return
      try {
        const lock = await navigator.wakeLock.request('screen')
        if (leaving) {
          void lock.release()

          return
        }
        sentinel = lock
      } catch {
        // Refused (battery saver, permissions policy): the screen follows its own timeout.
      }
    }

    const onVisible = () => {
      if (document.visibilityState === 'visible') void acquire()
    }

    void acquire()
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      leaving = true
      document.removeEventListener('visibilitychange', onVisible)
      void sentinel?.release()
    }
  }, [])
}
