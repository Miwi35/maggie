import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { clockOf } from './cookingTimers'
import type { TimerView } from './cookingTimers'

const lit = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 rgba(143, 184, 255, 0.55); }
  50% { box-shadow: 0 0 0 8px rgba(143, 184, 255, 0); }
`

export interface ColumnRecipe {
  recipeId: string
  name: string
  steps: { position: number; text: string }[]
}

export type RowState = 'done' | 'current' | 'upcoming'

interface Props {
  recipes: ColumnRecipe[]
  /** The recipe and the step the screen shows; step 0 is the preparation. */
  current: { recipeIndex: number; step: number }
  isDone: (recipeId: string, position: number) => boolean
  timerOf: (recipeId: string, position: number) => TimerView | undefined
  done: number
  total: number
  /** A row touched: the screen goes to that step, and a rung timer is acknowledged. */
  onSelect: (recipeIndex: number, position: number, timer: TimerView | undefined) => void
}

/**
 * The steps of the whole meal, recipe after recipe, at the left of the current step: ticked as the cook
 * goes, a progress bar, and the timers on the line of their step. Below `md` it folds into a bar.
 */
export const CookingStepsColumn = ({ recipes, current, isDone, timerOf, done, total, onSelect }: Props) => {
  const [open, setOpen] = useState(false)
  const fraction = total > 0 ? (done / total) * 100 : 0
  const label = `${done} / ${total} faites`

  return (
    <Box
      component="aside"
      aria-label="Étapes"
      data-testid="cooking-steps-column"
      sx={{
        flex: 'none',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        width: { xs: '100%', md: 340, lg: 380 },
        borderRight: { md: 1 },
        borderBottom: { xs: 1, md: 0 },
        borderColor: 'divider',
      }}
    >
      <Box sx={{ px: 2, pt: 2, pb: 1.5 }}>
        <Typography data-testid="cooking-steps-progress" sx={{ fontWeight: 600, mb: 1 }}>
          {label}
        </Typography>
        <LinearProgress variant="determinate" value={fraction} aria-label="Avancement des étapes" sx={{ height: 8, borderRadius: 4 }} />
      </Box>

      <ButtonBase
        data-testid="cooking-steps-toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        sx={{ display: { xs: 'flex', md: 'none' }, justifyContent: 'space-between', width: '100%', px: 2, py: 1, textAlign: 'left' }}
      >
        <Typography component="span" color="text.secondary">
          {open ? 'Masquer les étapes' : 'Voir les étapes'}
        </Typography>
        <ExpandMoreIcon sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 200ms' }} />
      </ButtonBase>

      <Box
        component="div"
        sx={{
          display: { xs: open ? 'block' : 'none', md: 'block' },
          flex: 1,
          minHeight: 0,
          overflowY: 'auto',
          maxHeight: { xs: '40vh', md: 'none' },
          px: 1,
          pb: 2,
        }}
      >
        {recipes.map((recipe, recipeIndex) => (
          <Box key={`${recipe.recipeId}-${recipeIndex}`} component="section" aria-label={recipe.name} sx={{ mb: 2 }}>
            {recipes.length > 1 && (
              <Typography variant="overline" component="h2" color="text.secondary" sx={{ display: 'block', px: 1, letterSpacing: 1.2 }}>
                {recipe.name}
              </Typography>
            )}
            <Box component="ol" sx={{ listStyle: 'none', m: 0, p: 0, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
              {recipe.steps.map(({ position, text }) => {
                const ticked = isDone(recipe.recipeId, position)
                const isCurrent = current.recipeIndex === recipeIndex && current.step === position
                const state: RowState = isCurrent ? 'current' : ticked ? 'done' : 'upcoming'
                const timer = timerOf(recipe.recipeId, position)
                const ringing = timer?.status === 'done'

                return (
                  <li key={position}>
                    <ButtonBase
                      data-testid="cooking-step-row"
                      data-state={state}
                      data-done={ticked}
                      data-position={position}
                      data-lit={ringing}
                      aria-current={isCurrent ? 'step' : undefined}
                      onClick={() => onSelect(recipeIndex, position, timer)}
                      sx={(theme) => ({
                        width: '100%',
                        minHeight: 56,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1.5,
                        px: 1.5,
                        py: 1,
                        borderRadius: 2,
                        textAlign: 'left',
                        border: 1,
                        borderColor: isCurrent ? 'primary.main' : 'transparent',
                        bgcolor: ringing ? 'rgba(143,184,255,0.18)' : isCurrent ? `${theme.palette.primary.main}22` : 'transparent',
                        opacity: ticked && !isCurrent && !ringing ? 0.5 : 1,
                        ...(ringing ? { '@media (prefers-reduced-motion: no-preference)': { animation: `${lit} 1.4s ease-in-out infinite` } } : {}),
                      })}
                    >
                      <Box
                        component="span"
                        aria-hidden
                        sx={{
                          flex: 'none',
                          width: 28,
                          height: 28,
                          borderRadius: '50%',
                          display: 'grid',
                          placeItems: 'center',
                          fontSize: 14,
                          fontWeight: 700,
                          bgcolor: ticked ? 'primary.main' : isCurrent ? 'primary.main' : 'action.selected',
                          color: ticked || isCurrent ? 'primary.contrastText' : 'text.primary',
                        }}
                      >
                        {ticked ? <CheckIcon sx={{ fontSize: 18 }} /> : position}
                      </Box>
                      <Typography
                        component="span"
                        sx={{
                          flex: 1,
                          minWidth: 0,
                          fontSize: '1rem',
                          fontWeight: isCurrent ? 600 : 400,
                          display: '-webkit-box',
                          WebkitLineClamp: 2,
                          WebkitBoxOrient: 'vertical',
                          overflow: 'hidden',
                        }}
                      >
                        <Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
                          {`Étape ${position}${ticked ? ' faite' : ''} : `}
                        </Box>
                        {text}
                      </Typography>
                      {timer && (
                        <Typography
                          component="span"
                          role="timer"
                          data-testid="cooking-step-timer"
                          data-status={timer.status}
                          aria-label={`${timer.label} : ${clockOf(timer.remaining)}`}
                          sx={{ flex: 'none', fontSize: 18, fontWeight: 400, fontVariantNumeric: 'tabular-nums', color: ringing ? 'primary.main' : 'text.primary' }}
                        >
                          {ringing ? 'Prêt' : clockOf(timer.remaining)}
                        </Typography>
                      )}
                    </ButtonBase>
                  </li>
                )
              })}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  )
}
