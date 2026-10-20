import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import StarIcon from '@mui/icons-material/Star'
import { motion } from './motion'
import { ofMonth } from './stars'
import type { Stars } from './stars'

/** The stars of the month, lavender; the latest, orchid. */
export const STAR_COLOR = '#C7B5FF'
export const LATEST_STAR_COLOR = '#E8A9D9'

/** Ten to a row, a hundred at most: beyond, the last hundred and « +n ». */
export const ROW = 10
export const MAX_DRAWN = 100

const glint = keyframes`
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.55; transform: scale(1.25); }
`

/** The count with its noun: « 1 étoile », « 12 étoiles ». */
export const starsLabel = (count: number): string => `${count} étoile${count > 1 ? 's' : ''}`

interface ConstellationProps {
  stars: Stars
  /** The animations, as the settings say. */
  animate: boolean
  /** Shows « depuis toujours » under the grid. */
  showTotal?: boolean
  /** The arrows to the months before and after; the next one is out of reach on the current month. */
  onPrevious?: () => void
  onNext?: (() => void) | null
}

/**
 * The constellation of a month (MAG-509): one star for every task done, only
 * those earned — never an empty place to fill. The latest one glints. Ten to a
 * row; past a hundred, the last hundred and « +n ».
 */
export const Constellation = ({ stars, animate, showTotal = false, onPrevious, onNext }: ConstellationProps) => {
  const drawn = Math.min(stars.count, MAX_DRAWN)
  const hidden = stars.count - drawn

  return (
    <Box component="section" aria-label="Constellation" data-testid="constellation">
      <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
        {onPrevious && (
          <IconButton size="small" aria-label="Mois précédent" onClick={onPrevious}>
            <ChevronLeftIcon />
          </IconButton>
        )}
        <Typography variant="subtitle2" component="h3" sx={{ flex: 1, textAlign: 'center' }}>
          Ta constellation {ofMonth(stars.month)} · {starsLabel(stars.count)}
        </Typography>
        {onPrevious && (
          <IconButton size="small" aria-label="Mois suivant" onClick={onNext ?? undefined} disabled={!onNext}>
            <ChevronRightIcon />
          </IconButton>
        )}
      </Stack>

      {drawn > 0 && (
        <Box
          aria-hidden
          sx={{ display: 'grid', gridTemplateColumns: `repeat(${ROW}, 1fr)`, gap: 0.5, mt: 1, justifyItems: 'center' }}
        >
          {Array.from({ length: drawn }, (_, i) => {
            const latest = i === drawn - 1

            return (
              <StarIcon
                key={i}
                data-testid="constellation-star"
                data-latest={latest}
                sx={{
                  fontSize: 22,
                  color: latest ? LATEST_STAR_COLOR : STAR_COLOR,
                  ...(latest ? motion(animate, `${glint} 1400ms ease-in-out infinite`) : {}),
                }}
              />
            )
          })}
        </Box>
      )}
      {hidden > 0 && (
        <Typography variant="body2" color="text.secondary" data-testid="constellation-more" sx={{ textAlign: 'right', mt: 0.5 }}>
          +{hidden}
        </Typography>
      )}

      {showTotal && (
        <Stack direction="row" alignItems="center" justifyContent="center" spacing={0.5} sx={{ mt: 1 }} data-testid="constellation-total">
          <StarIcon aria-hidden sx={{ fontSize: 16, color: STAR_COLOR }} />
          <Typography variant="body2" color="text.secondary">
            {starsLabel(stars.total)} depuis toujours
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
