import { useMemo, useState } from 'react'
import { useGetList } from 'react-admin'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'
import { WORKOUT_TOPICS } from './workoutsApi'
import { weeklyMinutes, weeksStart } from './workoutWeeks'
import type { Workout } from './workoutFormat'

const MAX_READ = 500

/**
 * « Minutes par semaine »: the 13 last ISO weeks, read apart from the table's
 * filters. It reads again when a workout changes (Doctrine serves the
 * collection, no index to outrun).
 */
export const WeeklyMinutesCard = () => {
  const [now] = useState(() => new Date())
  const after = useMemo(() => weeksStart(now).toISOString(), [now])
  const filter = useMemo(() => ({ 'startedAt[after]': after }), [after])
  const { data, refetch } = useGetList<Workout>('workouts', { filter, pagination: { page: 1, perPage: MAX_READ } })

  useMercure(WORKOUT_TOPICS, () => {
    void refetch()
  })

  const weeks = useMemo(() => weeklyMinutes(data ?? [], now), [data, now])
  const scale = Math.max(...weeks.map((week) => week.minutes)) * 1.1

  return (
    <Box
      component="section"
      aria-label="Minutes par semaine"
      sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0, minHeight: 0 }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Minutes par semaine</span>
        <Typography component="span" sx={{ fontSize: 12, color: (theme) => theme.palette.veilleuse.caption, fontWeight: 400 }}>
          13 semaines
        </Typography>
      </Box>
      <Box sx={{ flexGrow: 1, minHeight: 0, display: 'flex', gap: 0.75, alignItems: 'flex-end' }}>
        {weeks.map((week) => (
          <Box
            key={week.start.getTime()}
            role="img"
            aria-label={`Semaine du ${week.label} : ${week.minutes} min`}
            data-testid={week.current ? 'weekly-minutes-current' : 'weekly-minutes-bar'}
            sx={{ flex: 1, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', alignItems: 'center', gap: 0.5 }}
          >
            <Typography component="span" sx={{ fontSize: 11, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
              {week.minutes}
            </Typography>
            <Box
              sx={(theme) => ({
                width: '70%',
                height: `${scale > 0 ? ((week.minutes / scale) * 100).toFixed(1) : 0}%`,
                borderRadius: '4px 4px 1px 1px',
                bgcolor: week.current ? alpha(theme.palette.module.sport, 0.42) : theme.palette.module.sport,
              })}
            />
          </Box>
        ))}
      </Box>
      <Box sx={{ display: 'flex', gap: 0.75 }}>
        {weeks.map((week) => (
          <Typography
            key={week.start.getTime()}
            component="span"
            sx={{ flex: 1, textAlign: 'center', fontSize: 11, color: (theme) => theme.palette.veilleuse.caption, fontVariantNumeric: 'tabular-nums' }}
          >
            {week.label}
          </Typography>
        ))}
      </Box>
    </Box>
  )
}
