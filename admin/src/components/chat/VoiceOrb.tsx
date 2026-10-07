import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import ButtonBase from '@mui/material/ButtonBase'
import { alpha, keyframes } from '@mui/material/styles'
import { NARROW_QUERY } from '../../breakpoints'

const ring = keyframes`
  0% { transform: scale(1); opacity: 0.6; }
  100% { transform: scale(1.9); opacity: 0; }
`
const bar = keyframes`
  0%, 100% { transform: scaleY(0.25); }
  50% { transform: scaleY(1); }
`
const breathe = keyframes`
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.06); }
`

const BAR_DELAYS = [0, 160, 320, 80, 240, 400, 120]
const REDUCED_MOTION = '@media (prefers-reduced-motion: reduce)'

interface VoiceOrbProps {
  /** `listening` while the microphone is open, `transcribing` once it has stopped. */
  phase: 'listening' | 'transcribing'
  duration: number
  onFinish: () => void
  onCancel: () => void
}

/** The dictation state, drawn where it already shows: Maggie's orb, two rings, a waveform and two pills. */
export const VoiceOrb = ({ phase, duration, onFinish, onCancel }: VoiceOrbProps) => {
  const listening = phase === 'listening'

  return (
    <Box
      data-testid="voice-orb"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '10px',
        px: '16px',
        py: '14px',
        borderTop: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '14px', maxWidth: '100%' }}>
        <Box sx={{ position: 'relative', width: 56, height: 56, flexShrink: 0 }}>
          {listening &&
            [0, 1].map((i) => (
              <Box
                key={i}
                data-testid="voice-orb-ring"
                sx={(theme) => ({
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: `1.5px solid ${alpha(theme.palette.maggie.avatarFrom, 0.6)}`,
                  animation: `${ring} 2.2s ease-out ${i * 1.1}s infinite`,
                  pointerEvents: 'none',
                  [REDUCED_MOTION]: { animation: 'none', opacity: i === 0 ? 0.4 : 0 },
                })}
              />
            ))}
          <Box
            sx={(theme) => ({
              position: 'absolute',
              inset: 0,
              borderRadius: '50%',
              background: `linear-gradient(145deg, ${theme.palette.maggie.avatarFrom}, ${theme.palette.maggie.avatarTo})`,
              boxShadow: `0 0 24px ${alpha(theme.palette.maggie.avatarFrom, 0.45)}`,
              animation: listening ? `${breathe} 2.4s ease-in-out infinite` : 'none',
              opacity: listening ? 1 : 0.6,
              [REDUCED_MOTION]: { animation: 'none' },
            })}
          />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {listening ? 'Je t’écoute…' : 'Transcription…'}
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: '10px', height: 20 }}>
            {listening && (
              <Box aria-hidden sx={{ display: 'flex', alignItems: 'center', gap: '3px', height: 16 }}>
                {BAR_DELAYS.map((delay, i) => (
                  <Box
                    key={i}
                    data-testid="voice-orb-bar"
                    sx={(theme) => ({
                      width: 3,
                      height: 16,
                      borderRadius: 2,
                      bgcolor: theme.palette.maggie.avatarFrom,
                      transformOrigin: 'center',
                      animation: `${bar} 1s ease-in-out ${delay}ms infinite`,
                      [REDUCED_MOTION]: { animation: 'none', transform: 'scaleY(0.6)' },
                    })}
                  />
                ))}
              </Box>
            )}
            {listening && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {duration}s
              </Typography>
            )}
          </Box>
        </Box>
      </Box>
      {listening && (
        <Box sx={{ display: 'flex', gap: '8px' }}>
          <PillButton onClick={onCancel}>Annuler</PillButton>
          <PillButton primary onClick={onFinish}>
            Terminer
          </PillButton>
        </Box>
      )}
    </Box>
  )
}

interface PillButtonProps {
  children: string
  onClick: () => void
  primary?: boolean
}

const PillButton = ({ children, onClick, primary = false }: PillButtonProps) => (
  <ButtonBase
    onClick={onClick}
    sx={(theme) => ({
      px: '18px',
      minHeight: 36,
      borderRadius: 999,
      typography: 'body2',
      fontWeight: 600,
      bgcolor: primary ? theme.palette.veilleuse.ink : theme.palette.veilleuse.raised,
      color: primary ? theme.palette.veilleuse.onInk : theme.palette.text.primary,
      '&.Mui-focusVisible': { outline: `2px solid ${theme.palette.primary.main}`, outlineOffset: 2 },
      [`@media ${NARROW_QUERY}`]: { minHeight: 44, minWidth: 44 },
    })}
  >
    {children}
  </ButtonBase>
)
