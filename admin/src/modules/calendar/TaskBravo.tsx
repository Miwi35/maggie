import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import Snackbar from '@mui/material/Snackbar'
import SnackbarContent from '@mui/material/SnackbarContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import StarIcon from '@mui/icons-material/Star'
import { Constellation } from './Constellation'
import type { DayTask } from './dayPlan'
import { motion } from './motion'
import type { Reward } from './reward'
import type { Stars } from './stars'

const popIn = keyframes`
  0% { opacity: 0; transform: scale(0.3); }
  70% { opacity: 1; transform: scale(1.12); }
  100% { opacity: 1; transform: scale(1); }
`
const twinkle = keyframes`
  0%, 100% { opacity: 0.2; transform: scale(0.6); }
  50% { opacity: 1; transform: scale(1); }
`
const shoot = keyframes`
  0% { opacity: 0; transform: translate(-140px, 60px) rotate(-20deg); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: translate(140px, -60px) rotate(-20deg); }
`

/** Where the small stars sit around the circle, and when they twinkle. */
const STARS = [
  { top: 4, left: 18, delay: 0 },
  { top: 12, right: 14, delay: 250 },
  { bottom: 6, left: 34, delay: 500 },
  { bottom: 14, right: 30, delay: 120 },
]

/** The day as five segments, the done ones filled: « 3 sur 5 aujourd'hui ». */
export const DaySegments = ({ done, total }: { done: number; total: number }) => (
  <Stack spacing={0.5} alignItems="center" sx={{ width: '100%' }}>
    <Stack direction="row" spacing={0.5} aria-hidden sx={{ width: '100%', maxWidth: 240 }}>
      {Array.from({ length: total }, (_, i) => (
        <Box
          key={i}
          data-testid="bravo-segment"
          data-done={i < done}
          sx={{ flex: 1, height: 8, borderRadius: 4, bgcolor: i < done ? 'success.main' : 'action.disabledBackground' }}
        />
      ))}
    </Stack>
    <Typography variant="body2" color="text.secondary">
      {done} sur {total} aujourd'hui
    </Typography>
  </Stack>
)

interface TaskBravoProps {
  /** The title of the task just done. */
  title: string
  reward: Reward
  /** The day after this tick; none when the task was not in it. */
  done: number
  total: number
  /** The day's next task, « Maintenant »; none when the day is done. */
  next: DayTask | null
  /** The animations, as the settings say. */
  animate: boolean
  /** The month's constellation, this star counted; drawn under the message once read. */
  stars?: Stars | null
  onNext: () => void
  onPause: () => void
}

/**
 * The bravo of a task done (MAG-508), over the day: a green circle that pops,
 * small stars, the message of the reward, the day so far, then the next task
 * or a well-earned pause. A surprise crosses with a shooting star.
 */
export const TaskBravo = ({ title, reward, done, total, next, animate, stars = null, onNext, onPause }: TaskBravoProps) => (
  <Dialog open onClose={onPause} fullWidth maxWidth="xs" aria-labelledby="bravo-title">
    <Stack data-testid="bravo" data-animated={animate} alignItems="center" spacing={2} sx={{ p: 4, textAlign: 'center', overflow: 'hidden', position: 'relative' }}>
      {reward.surprise && (
        <Box
          data-testid="bravo-shooting-star"
          aria-hidden
          sx={{
            position: 'absolute',
            top: 40,
            left: '50%',
            color: 'warning.main',
            ...motion(animate, `${shoot} 1400ms ease-out 200ms both`),
          }}
        >
          <StarIcon />
        </Box>
      )}
      <Box sx={{ position: 'relative', width: 120, height: 120 }}>
        {STARS.map(({ delay, ...place }, i) => (
          <StarIcon
            key={i}
            aria-hidden
            sx={{
              position: 'absolute',
              ...place,
              fontSize: 16,
              color: 'warning.light',
              ...motion(animate, `${twinkle} 1200ms ease-in-out ${delay}ms infinite`),
            }}
          />
        ))}
        <Box
          data-testid="bravo-circle"
          sx={{
            position: 'absolute',
            inset: 20,
            borderRadius: '50%',
            bgcolor: 'success.main',
            color: 'success.contrastText',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            ...motion(animate, `${popIn} 450ms cubic-bezier(0.2, 0.8, 0.3, 1) both`),
          }}
        >
          <CheckIcon sx={{ fontSize: 44 }} />
        </Box>
      </Box>

      <Typography id="bravo-title" variant="h6" component="h2">
        {title} · faite
      </Typography>
      <Typography variant="body1" data-testid="bravo-message">
        {reward.message}
      </Typography>

      {stars && (
        <Box sx={{ width: '100%', maxWidth: 280 }}>
          <Constellation stars={stars} animate={animate} />
        </Box>
      )}

      {total > 0 && <DaySegments done={done} total={total} />}

      <Stack spacing={1} sx={{ width: '100%', pt: 1 }}>
        {next && (
          <Button variant="contained" onClick={onNext}>
            Suivante : {next.title}
            {next.estimatedMinutes != null && ` · ${next.estimatedMinutes} min`}
          </Button>
        )}
        <Button variant={next ? 'text' : 'contained'} onClick={onPause}>
          Pause méritée
        </Button>
      </Stack>
    </Stack>
  </Dialog>
)

/** The same bravo, short, for a task ticked away from the day: « À venir », a list. */
export const TaskBravoBanner = ({ title, reward, onClose }: { title: string; reward: Reward | null; onClose: () => void }) => (
  <Snackbar open={reward !== null} autoHideDuration={4000} onClose={onClose} anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
    <SnackbarContent
      role="status"
      sx={{ bgcolor: 'success.main', color: 'success.contrastText' }}
      message={
        <Stack direction="row" spacing={1} alignItems="center">
          {reward?.surprise ? <StarIcon fontSize="small" aria-hidden /> : <CheckIcon fontSize="small" aria-hidden />}
          <span>
            <strong>{title} · faite</strong> — {reward?.message}
          </span>
        </Stack>
      }
    />
  </Snackbar>
)

const PALIER_GOLD = '#F2C46D'

const burst = keyframes`
  0% { opacity: 0; transform: scale(0.2) rotate(0deg); }
  60% { opacity: 1; }
  100% { opacity: 0.7; transform: scale(1) rotate(25deg); }
`

/** Rays and stars around the badge of a palier: more of them than a task's bravo. */
const PALIER_STARS = Array.from({ length: 10 }, (_, i) => ({ angle: i * 36, delay: i * 70 }))

/**
 * The bravo of a big moment (MAG-509), a chapter finished: the gold banner with
 * its title, rays and more stars than a task's, the message of the palier bank.
 * No star is added to the month, no next task — a pause is all it offers.
 */
export const TaskPalier = ({ reward, animate, onClose }: { reward: Reward; animate: boolean; onClose: () => void }) => (
  <Dialog open onClose={onClose} fullWidth maxWidth="xs" aria-labelledby="palier-title">
    <Stack data-testid="palier" data-animated={animate} alignItems="center" spacing={2} sx={{ p: 4, textAlign: 'center', overflow: 'hidden', position: 'relative' }}>
      <Box sx={{ position: 'relative', width: 160, height: 160 }}>
        {PALIER_STARS.map(({ angle, delay }) => (
          <Box
            key={angle}
            data-testid="palier-ray"
            aria-hidden
            sx={{
              position: 'absolute',
              top: '50%',
              left: '50%',
              width: 0,
              height: 0,
              transform: `rotate(${angle}deg) translateY(-70px)`,
            }}
          >
            <StarIcon
              sx={{
                position: 'absolute',
                left: -9,
                top: -9,
                fontSize: 18,
                color: PALIER_GOLD,
                ...motion(animate, `${twinkle} 1000ms ease-in-out ${delay}ms infinite`),
              }}
            />
          </Box>
        ))}
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            inset: 30,
            borderRadius: '50%',
            bgcolor: PALIER_GOLD,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            ...motion(animate, `${burst} 700ms cubic-bezier(0.2, 0.8, 0.3, 1) both`),
          }}
        >
          <StarIcon sx={{ fontSize: 56, color: '#fff' }} />
        </Box>
      </Box>

      <Box data-testid="palier-banner" sx={{ bgcolor: PALIER_GOLD, color: '#3b2a00', px: 3, py: 1, borderRadius: 1, width: '100%' }}>
        <Typography id="palier-title" variant="h6" component="h2">
          {reward.title}
        </Typography>
      </Box>
      <Typography variant="body1" data-testid="palier-message">
        {reward.message}
      </Typography>

      <Button variant="contained" onClick={onClose}>
        Pause méritée
      </Button>
    </Stack>
  </Dialog>
)
