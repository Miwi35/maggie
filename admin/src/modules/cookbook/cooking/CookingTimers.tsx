import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { COOKING_COLOR } from './cookingSession'
import { clockOf } from './cookingTimers'
import type { TimerView } from './cookingTimers'

const TRACK = '#282534'

/** The share of the timer still to run, for the ring. */
const fractionOf = (remaining: number, duration: number): number => (duration > 0 ? Math.min(1, remaining / duration) : 0)

const Ring = ({ size, stroke, fraction }: { size: number; stroke: number; fraction: number }) => {
  const radius = size / 2 - stroke
  const circumference = 2 * Math.PI * radius

  return (
    <svg width="100%" height="100%" viewBox={`0 0 ${size} ${size}`} fill="none" aria-hidden style={{ display: 'block', transform: 'rotate(-90deg)' }}>
      <circle cx={size / 2} cy={size / 2} r={radius} stroke={TRACK} strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        stroke={COOKING_COLOR}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={circumference.toFixed(2)}
        strokeDashoffset={(circumference * (1 - fraction)).toFixed(2)}
        style={{ transition: 'stroke-dashoffset 400ms linear' }}
      />
    </svg>
  )
}

const ROUND = { minWidth: 64, width: 64, height: 64, borderRadius: '50%', fontSize: '1.125rem', bgcolor: 'rgba(255,255,255,0.08)', color: 'text.primary' }

/**
 * The current step's timer (admin mockup): the big ring, the rest, its name, and −1 / Démarrer-Pause / +1.
 * Not started, it shows the step's whole duration.
 */
export const MainTimer = ({
  label,
  seconds,
  timer,
  busy,
  onStart,
  onToggle,
  onAdd,
}: {
  label: string
  /** The step's duration. */
  seconds: number
  /** Its timer, once started. */
  timer: TimerView | undefined
  busy: boolean
  onStart: () => void
  onToggle: () => void
  onAdd: (seconds: number) => void
}) => {
  const live = timer?.status === 'running' || timer?.status === 'paused'
  const remaining = timer ? timer.remaining : seconds
  const duration = timer ? Math.max(timer.durationSeconds, remaining) : seconds
  const toggleLabel = timer?.status === 'running' ? 'Pause' : timer?.status === 'paused' ? 'Démarrer' : timer ? 'Relancer' : 'Démarrer'

  return (
    <Box data-testid="cooking-main-timer" data-status={timer?.status ?? 'idle'} sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3 }}>
      <Box sx={{ position: 'relative', width: { xs: 220, md: 300, lg: 380 }, aspectRatio: '1' }}>
        <Ring size={380} stroke={14} fraction={fractionOf(remaining, duration)} />
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1 }}>
          <Typography
            component="span"
            role="timer"
            aria-label={`${label} : ${clockOf(remaining)}`}
            data-testid="cooking-main-timer-clock"
            sx={{ fontSize: { xs: 56, md: 76, lg: 96 }, lineHeight: 1, fontWeight: 300, letterSpacing: '-0.04em', fontVariantNumeric: 'tabular-nums' }}
          >
            {clockOf(remaining)}
          </Typography>
          <Typography component="span" data-testid="cooking-main-timer-label" sx={{ fontSize: 20, color: 'text.secondary' }}>
            {label}
          </Typography>
        </Box>
      </Box>

      {timer?.status === 'done' ? (
        <Box
          data-testid="cooking-timer-notified"
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2.5, py: 1.75, borderRadius: '18px', bgcolor: 'rgba(166,139,255,0.14)', fontSize: 17 }}
        >
          <Box component="span" sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: '#A68BFF', boxShadow: '0 0 14px #A68BFF' }} />
          Minuteur terminé : notifié sur tous tes appareils.
        </Box>
      ) : (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
          <Button aria-label="Retirer une minute" disabled={!live || busy} onClick={() => onAdd(-60)} sx={ROUND}>
            −1
          </Button>
          <Button
            variant="contained"
            disabled={busy}
            onClick={live ? onToggle : onStart}
            sx={{ minWidth: 220, minHeight: 64, borderRadius: 999, fontSize: '1.25rem', bgcolor: '#F3F1F8', color: '#0F0E17', '&:hover': { bgcolor: '#FFFFFF' } }}
          >
            {toggleLabel}
          </Button>
          <Button aria-label="Ajouter une minute" disabled={!live || busy} onClick={() => onAdd(60)} sx={ROUND}>
            +1
          </Button>
        </Box>
      )}
    </Box>
  )
}

/** A start or a change the API did not take: said once, the step stays where it is. */
export const TimerError = ({ message, onClose }: { message: string; onClose: () => void }) => (
  <Alert
    severity="error"
    data-testid="cooking-timer-error"
    onClose={onClose}
    sx={{ position: 'absolute', left: '50%', bottom: 112, transform: 'translateX(-50%)', zIndex: 4, maxWidth: 560, width: 'calc(100% - 32px)' }}
  >
    {message}
  </Alert>
)
