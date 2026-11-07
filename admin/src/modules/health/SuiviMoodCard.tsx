import { useMemo } from 'react'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { HealthDay } from './healthDaysApi'
import { daysBetween, formatScore, scorePaths } from './healthJournal'
import { dayLabel } from './nutritionBalance'
import type { Loaded } from './nutritionApi'

interface SuiviMoodCardProps {
  state: Loaded<HealthDay[]>
  from: string
  /** The last day of the period, today. */
  to: string
  /** The chosen day, as the cards Pas and Énergie count it; null: the last one, today. */
  selected: number | null
  onSelect: (index: number | null) => void
}

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const
const cardSx = { borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, fontVariantNumeric: 'tabular-nums' } as const

/** Moral in a solid amber line, fatigue in a dotted blue one: the theme's warning and info hues. */
const MOOD = (theme: Theme) => theme.palette.warning.main
const FATIGUE = (theme: Theme) => theme.palette.info.main

/**
 * « Moral · fatigue » (MAG-750): the scores the journal holds over the
 * period, on a 0–10 scale, without a verdict. The figure is the chosen day's.
 */
export const SuiviMoodCard = ({ state, from, to, selected, onSelect }: SuiviMoodCardProps) => {
  const days = useMemo(() => daysBetween(from, to), [from, to])
  const byDate = useMemo(() => new Map((state.status === 'ready' ? state.data : []).map((day) => [day.date, day])), [state])
  const current = selected === null || selected >= days.length ? days.length - 1 : selected
  const chosen = byDate.get(days[current] ?? '')
  const mood = useMemo(() => scorePaths(days, byDate, 'mood'), [days, byDate])
  const fatigue = useMemo(() => scorePaths(days, byDate, 'fatigue'), [days, byDate])

  return (
    <Box component="section" aria-label="Moral · fatigue" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Moral · fatigue</span>
        <Typography component="span" data-testid="mood-label" sx={{ ...caption, fontWeight: 400 }}>
          {days[current] ? dayLabel(days[current], to) : ''}
        </Typography>
      </Box>
      {state.status === 'loading' && <Typography sx={caption}>Chargement…</Typography>}
      {state.status === 'error' && <Typography sx={caption}>Erreur : le moral et la fatigue n’ont pas pu être chargés.</Typography>}
      {state.status === 'ready' && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
            <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
              Moral{' '}
              <Box component="span" data-testid="mood-value" sx={{ fontSize: 26, fontWeight: 300, color: 'text.primary' }}>
                {formatScore(chosen?.mood ?? null)}
              </Box>
            </Typography>
            <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
              Fatigue{' '}
              <Box component="span" data-testid="fatigue-value" sx={{ fontSize: 26, fontWeight: 300, color: 'text.primary' }}>
                {formatScore(chosen?.fatigue ?? null)}
              </Box>
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', gap: 0.75, height: 140 }}>
            <Box sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', ...caption, fontSize: 11 }} aria-hidden="true">
              <span>10</span>
              <span>5</span>
              <span>0</span>
            </Box>
            <Box sx={{ position: 'relative', flexGrow: 1, minWidth: 0 }}>
              <Box sx={{ position: 'absolute', inset: 0, display: 'flex', gap: '2px' }}>
                {days.map((day, index) => {
                  const entry = byDate.get(day)

                  return (
                    <Box
                      key={day}
                      component="button"
                      type="button"
                      aria-label={`${dayLabel(day, to)} : moral ${formatScore(entry?.mood ?? null)}, fatigue ${formatScore(entry?.fatigue ?? null)}`}
                      aria-pressed={index === current}
                      data-testid="mood-column"
                      onClick={() => onSelect(index === selected ? null : index)}
                      sx={(theme) => ({
                        appearance: 'none',
                        border: 0,
                        m: 0,
                        p: 0,
                        cursor: 'pointer',
                        flex: '1 1 0',
                        minWidth: 0,
                        height: '100%',
                        borderRadius: 1,
                        bgcolor: index === current ? alpha(theme.palette.text.primary, 0.07) : 'transparent',
                      })}
                    />
                  )
                })}
              </Box>
              <Box
                component="svg"
                width="100%"
                height="100%"
                viewBox="0 0 1000 100"
                preserveAspectRatio="none"
                fill="none"
                aria-hidden="true"
                sx={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'visible' }}
              >
                <Box component="path" data-testid="mood-line" d={mood.line} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" sx={(theme) => ({ stroke: MOOD(theme) })} />
                <Box component="path" data-testid="mood-dots" d={mood.dots} strokeWidth={6} strokeLinecap="round" vectorEffect="non-scaling-stroke" sx={(theme) => ({ stroke: MOOD(theme) })} />
                <Box
                  component="path"
                  data-testid="fatigue-line"
                  d={fatigue.line}
                  strokeWidth={2.5}
                  strokeDasharray="4 4"
                  strokeLinejoin="round"
                  vectorEffect="non-scaling-stroke"
                  sx={(theme) => ({ stroke: FATIGUE(theme) })}
                />
                <Box component="path" data-testid="fatigue-dots" d={fatigue.dots} strokeWidth={6} strokeLinecap="round" vectorEffect="non-scaling-stroke" sx={(theme) => ({ stroke: FATIGUE(theme) })} />
              </Box>
            </Box>
          </Box>
          <Box sx={{ display: 'flex', gap: 1.5, ...caption }}>
            <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
              <Box component="span" sx={(theme) => ({ width: 14, borderTop: `2px solid ${MOOD(theme)}` })} />
              moral
            </Box>
            <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
              <Box component="span" sx={(theme) => ({ width: 14, borderTop: `2px dashed ${FATIGUE(theme)}` })} />
              fatigue
            </Box>
          </Box>
        </>
      )}
    </Box>
  )
}
