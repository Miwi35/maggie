const SOUND_KEY = 'maggie.sound'

const NOTES_HZ = [880, 1318.5]
const NOTE_GAP_S = 0.14
const FADE_S = 0.9

export function isSoundEnabled(): boolean {
  try {
    return localStorage.getItem(SOUND_KEY) !== 'off'
  } catch {
    return true
  }
}

export function setSoundEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(SOUND_KEY, enabled ? 'on' : 'off')
  } catch {
    // Storage blocked: the setting simply does not persist.
  }
}

type AudioContextCtor = typeof AudioContext

/** Two soft sine notes announcing Maggie's interruption; silent when muted or unsupported. */
export function playChime(): void {
  if (!isSoundEnabled()) return
  const Ctor: AudioContextCtor | undefined =
    window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext
  if (!Ctor) return

  try {
    const ctx = new Ctor()
    void ctx.resume?.()
    NOTES_HZ.forEach((frequency, index) => {
      const start = ctx.currentTime + index * NOTE_GAP_S
      const oscillator = ctx.createOscillator()
      const gain = ctx.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = frequency
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.12, start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + FADE_S)
      oscillator.connect(gain).connect(ctx.destination)
      oscillator.start(start)
      oscillator.stop(start + FADE_S)
    })
    window.setTimeout(() => void ctx.close?.(), (NOTES_HZ.length * NOTE_GAP_S + FADE_S) * 1000 + 100)
  } catch {
    // A browser refusing audio must never break the interruption.
  }
}
