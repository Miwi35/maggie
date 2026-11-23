import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import PauseIcon from '@mui/icons-material/Pause'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { stripDeliveryTags } from './deliveryTags'
import { Linkified } from './Linkified'

/** The audio attachment of a voice message (MAG-685). `url` answers a short signed URL to the owner alone. */
export interface VoiceAttachment {
  type: string
  durationMs: number
  size: number
  url: string
  transcript: string
}

/** The text a message shows beside its voice bubble: none when it is the bubble's own transcript. */
export function textBesideVoice(content: string, audio: VoiceAttachment): string | null {
  const text = stripDeliveryTags(content).trim()
  return text !== stripDeliveryTags(audio.transcript).trim() ? text : null
}

const SPEEDS = [1, 1.5, 2] as const
const nextSpeed = (speed: number): number => SPEEDS[(SPEEDS.indexOf(speed as (typeof SPEEDS)[number]) + 1) % SPEEDS.length]
const speedLabel = (speed: number): string => `×${String(speed).replace('.', ',')}`

function formatClock(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

export type VoiceBubbleSize = 'chat' | 'large'

const BARS: Record<VoiceBubbleSize, { count: number; height: number }> = {
  chat: { count: 28, height: 24 },
  large: { count: 34, height: 32 },
}
const SEEK_STEP = 5

/** Fixed, wave-like heights (share of the bar's box): the same bubble always draws the same bars. */
const barShare = (index: number): number => 0.3 + 0.7 * Math.abs(Math.sin(index * 1.7) * Math.cos(index * 0.6))

type PlayerState = 'idle' | 'loading' | 'playing' | 'paused' | 'error'

/**
 * Maggie's voice message in the thread, WhatsApp-style (MAG-686): play and pause, the duration, a
 * progress the owner can drag, the speed ×1 → ×1,5 → ×2 and the transcript unfolding below. The
 * transcript is shown without voice tags; the audio was spoken with them. A missing audio says so
 * in the bubble and leaves the thread as it is.
 */
export function VoiceBubble({
  audio,
  authHeaders,
  size = 'chat',
  autoPlay = false,
  transcriptToggle = true,
}: {
  audio: VoiceAttachment
  authHeaders: () => Record<string, string>
  size?: VoiceBubbleSize
  /** Plays once on mount; a browser that refuses leaves the button. */
  autoPlay?: boolean
  /** False when the text is written beside the bubble: no « Transcription » then. */
  transcriptToggle?: boolean
}) {
  const [state, setState] = useState<PlayerState>('idle')
  const [position, setPosition] = useState(0)
  const [speed, setSpeed] = useState<number>(1)
  const [unfolded, setUnfolded] = useState(false)
  const element = useRef<HTMLAudioElement | null>(null)
  const duration = audio.durationMs / 1000

  useEffect(
    () => () => {
      element.current?.pause()
      element.current = null
    },
    [],
  )

  const load = async (rate: number): Promise<HTMLAudioElement> => {
    const res = await fetch(audio.url, { headers: authHeaders() })
    if (!res.ok) throw new Error(`audio ${res.status}`)
    const { url } = (await res.json()) as { url: string }
    const el = new Audio(url)
    el.playbackRate = rate
    el.addEventListener('timeupdate', () => setPosition(el.currentTime))
    el.addEventListener('ended', () => {
      el.currentTime = 0
      setPosition(0)
      setState('paused')
    })
    el.addEventListener('error', () => setState('error'))
    element.current = el
    return el
  }

  const toggle = async () => {
    if (state === 'loading') return
    if (state === 'playing') {
      element.current?.pause()
      setState('paused')
      return
    }
    try {
      let el = element.current
      if (!el || state === 'error') {
        setState('loading')
        el = await load(speed)
      }
      await el.play()
      setState('playing')
    } catch (e) {
      if (e instanceof DOMException && e.name === 'NotAllowedError') {
        setState('paused')
        return
      }
      element.current = null
      setState('error')
    }
  }

  const autoPlayed = useRef(false)
  useEffect(() => {
    if (!autoPlay || autoPlayed.current) return
    autoPlayed.current = true
    void toggle()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const cycleSpeed = () => {
    const next = nextSpeed(speed)
    setSpeed(next)
    if (element.current) element.current.playbackRate = next
  }

  const seek = (seconds: number) => {
    setPosition(seconds)
    if (element.current) element.current.currentTime = seconds
  }

  const playing = state === 'playing'
  const bars = BARS[size]
  const total = duration > 0 ? duration : 1
  const clockLabel =
    size === 'large' || playing || position > 0 ? `${formatClock(position)} / ${formatClock(duration)}` : formatClock(duration)

  return (
    <Box data-testid="voice-bubble" sx={{ minWidth: size === 'large' ? 420 : 220 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <IconButton
          size="small"
          color="primary"
          sx={size === 'large' ? { width: 48, height: 48, bgcolor: 'primary.main', color: 'primary.contrastText', '&:hover': { bgcolor: 'primary.dark' } } : undefined}
          aria-label={playing ? 'Pause' : 'Lecture'}
          onClick={(e) => {
            e.stopPropagation()
            void toggle()
          }}
        >
          {playing ? <PauseIcon /> : <PlayArrowIcon />}
        </IconButton>
        <Box
          role="slider"
          tabIndex={0}
          aria-label="Progression"
          aria-valuemin={0}
          aria-valuemax={Math.round(total)}
          aria-valuenow={Math.round(Math.min(position, total))}
          aria-valuetext={`Lu ${formatClock(position)} sur ${formatClock(duration)}`}
          onClick={(e) => e.stopPropagation()}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight') seek(Math.min(total, position + SEEK_STEP))
            else if (e.key === 'ArrowLeft') seek(Math.max(0, position - SEEK_STEP))
            else return
            e.preventDefault()
          }}
          sx={{ flex: 1, display: 'flex', alignItems: 'center', gap: '2px', height: bars.height, cursor: 'pointer', borderRadius: 1 }}
        >
          {Array.from({ length: bars.count }, (_, i) => (
            <Box
              key={i}
              data-testid="voice-bar"
              data-played={(i + 0.5) / bars.count <= position / total}
              onClick={() => seek(((i + 0.5) / bars.count) * total)}
              sx={(theme) => ({
                flex: 1,
                height: `${Math.round(barShare(i) * 100)}%`,
                borderRadius: '2px',
                bgcolor: (i + 0.5) / bars.count <= position / total ? 'primary.main' : alpha(theme.palette.primary.light, 0.32),
              })}
            />
          ))}
        </Box>
        <Typography variant="caption" data-testid="voice-duration" sx={{ minWidth: 32, textAlign: 'right', whiteSpace: 'nowrap' }}>
          {clockLabel}
        </Typography>
        <Button
          size="small"
          aria-label={`Vitesse ${speedLabel(speed)}`}
          onClick={(e) => {
            e.stopPropagation()
            cycleSpeed()
          }}
          sx={{ minWidth: 40, px: 0.5 }}
        >
          {speedLabel(speed)}
        </Button>
      </Box>
      {state === 'error' && (
        <Typography role="alert" variant="caption" color="error" component="div">
          Audio introuvable
        </Typography>
      )}
      {transcriptToggle && (
        <Button
          size="small"
          aria-expanded={unfolded}
          onClick={(e) => {
            e.stopPropagation()
            setUnfolded((open) => !open)
          }}
          sx={{ px: 0.5, textTransform: 'none' }}
        >
          {unfolded ? 'Masquer la transcription' : 'Transcription'}
        </Button>
      )}
      {transcriptToggle && unfolded && (
        <Typography variant="body2" component="div" data-testid="voice-transcript">
          <Linkified text={stripDeliveryTags(audio.transcript)} />
        </Typography>
      )}
    </Box>
  )
}
