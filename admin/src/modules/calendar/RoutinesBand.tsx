import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked'
import { ROUTINE_COLOR } from './TaskRoutines'
import { bandAt } from './routinesApi'
import { useRoutinesDay } from './useRoutinesDay'

const hhmm = (date: Date): string => `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`

/**
 * « Routines · matin » (MAG-512): the routines of the current moment and those left from the earlier
 * ones, one tap each, above the day's five. They never count in the ring nor in « Maintenant ».
 */
export const RoutinesBand = () => {
  const { day, pending, toggle } = useRoutinesDay()
  const [now, setNow] = useState(() => hhmm(new Date()))

  useEffect(() => {
    const timer = window.setInterval(() => setNow(hhmm(new Date())), 60_000)

    return () => window.clearInterval(timer)
  }, [])

  const band = day ? bandAt(day, now) : null
  if (!band) return null

  return (
    <Box component="section" aria-labelledby="day-routines" sx={{ mb: 2 }}>
      <Typography id="day-routines" variant="overline" color="text.secondary" component="h2">
        Routines · {band.part}
      </Typography>
      <Box sx={{ display: 'flex', gap: 1, overflowX: 'auto', py: 0.5, whiteSpace: 'nowrap' }}>
        {band.routines.map((routine) => (
          <Chip
            key={routine.id}
            label={routine.title}
            aria-pressed={routine.checked}
            disabled={pending.has(routine.id)}
            onClick={() => toggle(routine)}
            icon={
              routine.checked ? <CheckCircleIcon sx={{ color: `${ROUTINE_COLOR} !important` }} /> : <RadioButtonUncheckedIcon />
            }
            variant={routine.checked ? 'filled' : 'outlined'}
            sx={{ flexShrink: 0, ...(routine.checked ? { textDecoration: 'line-through', color: 'text.secondary' } : {}) }}
          />
        ))}
      </Box>
    </Box>
  )
}
