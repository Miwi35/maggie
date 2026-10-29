import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Slider from '@mui/material/Slider'
import Typography from '@mui/material/Typography'
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

const SPEEDS = [1, 1.5, 2] as const
const nextSpeed = (speed: number): number => SPEEDS[(SPEEDS.indexOf(speed as (typeof SPEEDS)[number]) + 1) % SPEEDS.length]
const speedLabel = (speed: number): string => `×${String(speed).replace('.', ',')}`

function formatClock(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

type PlayerState = 'idle' | 'loading' | 'playing' | 'paused' | 'error'

/**
 * Maggie's voice message in the thread, WhatsApp-style (MAG-686): play and pause, the duration, a
 * progress the owner can drag, the speed ×1 → ×1,5 → ×2 and the transcript unfolding below. The
 * transcript is shown without voice tags; the audio was spoken with them. A missing audio says so
 * in the bubble and leaves the thread as it is.
 */
export function VoiceBubble({ audio, authHeaders }: { audio: VoiceAttachment; authHeaders: () => Record<string, string> }) {
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
    } catch {
      element.current = null
      setState('error')
    }
  }

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

  return (
    <Box data-testid="voice-bubble" sx={{ minWidth: 220 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <IconButton
          size="small"
          aria-label={playing ? 'Pause' : 'Lecture'}
          onClick={(e) => {
            e.stopPropagation()
            void toggle()
          }}
        >
          {playing ? <PauseIcon /> : <PlayArrowIcon />}
        </IconButton>
        <Slider
          size="small"
          aria-label="Progression"
          min={0}
          max={duration > 0 ? duration : 1}
          step={0.1}
          value={Math.min(position, duration > 0 ? duration : 1)}
          onChange={(_e, value) => seek(value as number)}
          onClick={(e) => e.stopPropagation()}
          sx={{ flex: 1 }}
        />
        <Typography variant="caption" data-testid="voice-duration" sx={{ minWidth: 32, textAlign: 'right' }}>
          {formatClock(playing || position > 0 ? position : duration)}
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
      {unfolded && (
        <Typography variant="body2" component="div" data-testid="voice-transcript">
          <Linkified text={stripDeliveryTags(audio.transcript)} />
        </Typography>
      )}
    </Box>
  )
}
