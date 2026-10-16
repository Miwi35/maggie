/** The Focus timer lasts ten minutes (MAG-499). */
export const FOCUS_MS = 10 * 60 * 1000

/**
 * What the timer remembers: a deadline while it runs, the time left while it is
 * paused. Never a count of ticks, so a re-render or a tab put in the background
 * cannot make it drift: the time left is always read from the clock.
 */
export type TimerState =
  | { status: 'idle' }
  | { status: 'running'; endsAt: number }
  | { status: 'paused'; remainingMs: number }

export interface TimerView {
  status: 'idle' | 'running' | 'paused' | 'finished'
  remainingMs: number
}

export const IDLE: TimerState = { status: 'idle' }

export const startTimer = (now: number): TimerState => ({ status: 'running', endsAt: now + FOCUS_MS })

export const pauseTimer = (state: TimerState, now: number): TimerState =>
  state.status === 'running' ? { status: 'paused', remainingMs: Math.max(0, state.endsAt - now) } : state

export const resumeTimer = (state: TimerState, now: number): TimerState =>
  state.status === 'paused' ? { status: 'running', endsAt: now + state.remainingMs } : state

/** The timer as it stands at `now`: a running one that reached its deadline is finished. */
export function viewTimer(state: TimerState, now: number): TimerView {
  if (state.status === 'idle') return { status: 'idle', remainingMs: FOCUS_MS }
  if (state.status === 'paused') return { status: 'paused', remainingMs: state.remainingMs }
  const remainingMs = Math.max(0, state.endsAt - now)

  return { status: remainingMs === 0 ? 'finished' : 'running', remainingMs }
}

/** `mm:ss`, rounding up: the clock reads 00:00 only once the time is really up. */
export function formatClock(remainingMs: number): string {
  const seconds = Math.ceil(Math.max(0, remainingMs) / 1000)
  const pad = (value: number) => String(value).padStart(2, '0')

  return `${pad(Math.floor(seconds / 60))}:${pad(seconds % 60)}`
}
