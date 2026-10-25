import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive'
import { COOKING_COLOR } from './cookingSession'
import { clockOf, doneTitle } from './cookingTimers'
import type { TimerView } from './cookingTimers'

const TRACK = '#282534'
const ROW = '#1A1824'

/** A rung timer calls until « OK »: its row and its alert pulse. */
const pulse = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 rgba(143, 184, 255, 0.55); }
  50% { box-shadow: 0 0 0 12px rgba(143, 184, 255, 0); }
`

const pulsing = { '@media (prefers-reduced-motion: no-preference)': { animation: `${pulse} 1.4s ease-in-out infinite` } }

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

/** « étape 1 · en fond », « étape 4 · en pause », « étape 3 · terminé ». */
const subOf = (timer: TimerView, background: boolean): string =>
  [
    `étape ${timer.stepPosition}`,
    timer.status === 'done' ? 'terminé' : timer.status === 'paused' ? 'en pause' : background ? 'en fond' : null,
  ]
    .filter(Boolean)
    .join(' · ')

/** The « Minuteurs » column (tablet mockup): every other timer, the rung one pulsing; « Aucun minuteur » when empty. */
export const TimerColumn = ({ timers, isBackground }: { timers: TimerView[]; isBackground: (timer: TimerView) => boolean }) => (
  <Box component="aside" aria-label="Minuteurs" data-testid="cooking-timers" sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, width: '100%', maxWidth: 380 }}>
    <Typography variant="overline" color="text.secondary" component="h2" sx={{ letterSpacing: '0.14em', lineHeight: 1.5 }}>
      Minuteurs
    </Typography>
    {timers.length === 0 ? (
      <Box data-testid="cooking-timers-empty" sx={{ display: 'flex', alignItems: 'center', minHeight: 72, px: 1.75, borderRadius: '20px', bgcolor: ROW, color: 'text.secondary', fontSize: 15 }}>
        Aucun minuteur
      </Box>
    ) : (
      timers.map((timer) => {
        const done = timer.status === 'done'

        return (
          <Box
            key={timer.id}
            data-testid="cooking-timer"
            data-status={timer.status}
            role="timer"
            aria-label={`${timer.label} : ${clockOf(timer.remaining)}`}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.75,
              minHeight: 72,
              px: 1.75,
              py: 1.25,
              borderRadius: '20px',
              bgcolor: done ? 'rgba(143,184,255,0.18)' : ROW,
              ...(done ? pulsing : {}),
            }}
          >
            <Box sx={{ width: 56, height: 56, flex: 'none' }}>
              <Ring size={56} stroke={5} fraction={fractionOf(timer.remaining, Math.max(timer.durationSeconds, timer.remaining))} />
            </Box>
            <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 0.25 }}>
              <Typography component="span" noWrap sx={{ fontSize: 16, fontWeight: 500 }}>
                {timer.label}
              </Typography>
              <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                {subOf(timer, isBackground(timer))}
              </Typography>
            </Box>
            <Typography component="span" sx={{ fontSize: 26, fontWeight: 300, fontVariantNumeric: 'tabular-nums', color: done ? 'primary.main' : 'text.primary' }}>
              {clockOf(timer.remaining)}
            </Typography>
          </Box>
        )
      })
    )}
  </Box>
)

/** « Pâtes : c'est prêt · minuteur de l'étape 3 · OK », over the step until « OK ». One at a time. */
export const TimerAlert = ({ timer, onDismiss }: { timer: TimerView; onDismiss: () => void }) => (
  <Box
    role="alert"
    data-testid="cooking-timer-alert"
    sx={{
      position: 'absolute',
      left: '50%',
      top: 96,
      transform: 'translateX(-50%)',
      zIndex: 4,
      display: 'flex',
      alignItems: 'center',
      gap: 1.75,
      minHeight: 76,
      py: 1.25,
      pr: 1.25,
      pl: 2.5,
      borderRadius: '24px',
      bgcolor: '#22304A',
      color: 'text.primary',
      boxShadow: '0 18px 48px rgba(0,0,0,0.5)',
      ...pulsing,
    }}
  >
    <NotificationsActiveIcon sx={{ color: COOKING_COLOR, fontSize: 26 }} />
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, minWidth: 220 }}>
      <Typography component="span" sx={{ fontSize: 18, fontWeight: 500 }}>
        {doneTitle(timer)}
      </Typography>
      <Typography component="span" sx={{ fontSize: 14, color: COOKING_COLOR }}>
        minuteur de l'étape {timer.stepPosition}
      </Typography>
    </Box>
    <Button
      variant="contained"
      onClick={onDismiss}
      sx={{ minHeight: 52, px: 2.75, borderRadius: 999, fontSize: 16, bgcolor: COOKING_COLOR, color: '#0F0E17' }}
    >
      OK
    </Button>
  </Box>
)

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
