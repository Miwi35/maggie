import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'

const MONTHS = [
  'janvier',
  'février',
  'mars',
  'avril',
  'mai',
  'juin',
  'juillet',
  'août',
  'septembre',
  'octobre',
  'novembre',
  'décembre',
]
const WEEK = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi']
const HEADS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

const pad = (n: number) => String(n).padStart(2, '0')
const key = (year: number, month: number, day: number) => `${year}-${pad(month + 1)}-${pad(day)}`

interface CalendarGridProps {
  /** The chosen day, `YYYY-MM-DD`; null for none. */
  value: string | null
  onChange: (day: string) => void
  /** The month shown when nothing is chosen, `YYYY-MM-DD`. */
  fallbackMonth?: string
}

/** A month grid, days Monday to Sunday: the days are plain `YYYY-MM-DD` strings, no zone involved. */
export const CalendarGrid = ({ value, onChange, fallbackMonth }: CalendarGridProps) => {
  const base = value ?? fallbackMonth ?? new Date().toISOString().slice(0, 10)
  const [shown, setShown] = useState({ year: Number(base.slice(0, 4)), month: Number(base.slice(5, 7)) - 1 })

  const first = new Date(shown.year, shown.month, 1, 12)
  const lead = (first.getDay() + 6) % 7
  const length = new Date(shown.year, shown.month + 1, 0, 12).getDate()
  const cells = [...Array<null>(lead).fill(null), ...Array.from({ length }, (_, i) => i + 1)]

  const move = (by: number) => {
    const next = new Date(shown.year, shown.month + by, 1, 12)
    setShown({ year: next.getFullYear(), month: next.getMonth() })
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <IconButton size="small" aria-label="Mois précédent" onClick={() => move(-1)}>
          <ChevronLeftIcon fontSize="small" />
        </IconButton>
        <Typography variant="subtitle2" aria-live="polite">
          {`${MONTHS[shown.month]} ${shown.year}`}
        </Typography>
        <IconButton size="small" aria-label="Mois suivant" onClick={() => move(1)}>
          <ChevronRightIcon fontSize="small" />
        </IconButton>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 0.25, textAlign: 'center' }}>
        {HEADS.map((head, i) => (
          <Typography key={i} variant="caption" color="text.secondary" aria-hidden>
            {head}
          </Typography>
        ))}
        {cells.map((day, i) => {
          if (day === null) return <span key={`blank-${i}`} />
          const iso = key(shown.year, shown.month, day)
          const chosen = iso === value

          return (
            <ButtonBase
              key={iso}
              aria-label={`${WEEK[(lead + day) % 7]} ${day} ${MONTHS[shown.month]} ${shown.year}`}
              aria-pressed={chosen}
              onClick={() => onChange(iso)}
              sx={{
                height: 32,
                borderRadius: '50%',
                typography: 'body2',
                bgcolor: chosen ? 'primary.main' : 'transparent',
                color: chosen ? 'primary.contrastText' : 'text.primary',
                '&:hover': { bgcolor: chosen ? 'primary.dark' : 'action.hover' },
              }}
            >
              {day}
            </ButtonBase>
          )
        })}
      </Box>
    </Box>
  )
}
