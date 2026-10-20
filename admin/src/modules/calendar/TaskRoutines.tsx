import { useCallback, useEffect, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Checkbox from '@mui/material/Checkbox'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { RoutineDialog } from './RoutineDialog'
import { motion } from './motion'
import { checkRoutine, countLabel, fetchRoutines, GROUPS, groupOf, whenLabel } from './routinesApi'
import type { RecentDay, Routine } from './routinesApi'

/** The green of the routines (MAG-511). */
export const ROUTINE_COLOR = '#7FD1AE'

// A tick is published on its own topic, apart from the routine it counts for.
const ROUTINE_TOPICS = ['/api/routines/{id}', '/api/routine_checks/{id}']

const pop = keyframes`
  0% { transform: scale(0.6); }
  60% { transform: scale(1.2); }
  100% { transform: scale(1); }
`

const dayState = (day: RecentDay): 'checked' | 'missed' | 'off' => {
  if (day.checked) return 'checked'

  return day.due ? 'missed' : 'off'
}

/** The last seven days, the oldest first: green done, grey due and not done, nothing when not due. */
const RecentDays = ({ days }: { days: RecentDay[] }) => {
  const checked = days.filter((day) => day.checked).length

  return (
    <Box role="img" aria-label={`7 derniers jours : ${checked} fois`} sx={{ display: 'flex', gap: 0.5 }}>
      {days.map((day) => {
        const state = dayState(day)

        return (
          <Box
            key={day.date}
            data-testid="routine-day"
            data-state={state}
            sx={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              bgcolor: state === 'checked' ? ROUTINE_COLOR : state === 'missed' ? 'action.disabled' : 'transparent',
            }}
          />
        )
      })}
    </Box>
  )
}

interface TaskRoutinesProps {
  /** The tick's little bounce, off with Réglages › Tâches like every animation of the page. */
  animate?: boolean
}

/**
 * « Routines » (MAG-511): the small things that come back — a moment, a rhythm, a
 * tick. Grouped by the part of the day for the daily ones, by their rhythm for the
 * others. A tick is for today only, and only on a day the routine is due.
 */
export const TaskRoutines = ({ animate = true }: TaskRoutinesProps) => {
  const notify = useNotify()

  const [routines, setRoutines] = useState<Routine[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  // The routine opened stays set while the dialog fades out, so its title does not flip.
  const [editing, setEditing] = useState<Routine | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [ticked, setTicked] = useState<string | null>(null)
  // Each opening mounts a fresh form.
  const [opening, setOpening] = useState(0)

  const today = localDay(new Date())

  // Only the answer to the last read is drawn; a tick's own answer wins over a read sent before it.
  const request = useRef(0)

  const load = useCallback(() => {
    const current = ++request.current
    fetchRoutines()
      .then((result) => {
        if (current !== request.current) return
        setRoutines(result)
        setFailed(false)
      })
      .catch((error: unknown) => {
        if (current !== request.current) return
        console.error(error)
        setFailed(true)
      })
      .finally(() => {
        if (current === request.current) setLoading(false)
      })
  }, [])

  useEffect(load, [load])

  useMercure(ROUTINE_TOPICS, load)

  const toggle = (routine: Routine) => {
    const checked = !routine.checkedToday
    checkRoutine(routine.id, localDay(new Date()), checked)
      .then((updated) => {
        request.current++
        setRoutines((list) => list.map((r) => (r.id === updated.id ? updated : r)))
        setTicked(checked ? routine.id : null)
      })
      .catch((error: Error) => notify(`Erreur : ${error.message}`, { type: 'info' }))
  }

  const groups = GROUPS.map((group) => ({ ...group, routines: routines.filter((r) => groupOf(r) === group.key) })).filter(
    (group) => group.routines.length > 0,
  )

  return (
    <Box>
      <Button startIcon={<AddIcon />} variant="outlined" onClick={() => {
          setOpening((n) => n + 1)
          setEditing(null)
          setDialogOpen(true)
        }} sx={{ mb: 2 }}>
        Nouvelle routine
      </Button>

      {failed ? (
        <Stack direction="row" spacing={1} alignItems="center" role="alert">
          <Typography variant="body2" color="text.secondary">
            Routines indisponibles
          </Typography>
          <Button size="small" onClick={load}>
            Réessayer
          </Button>
        </Stack>
      ) : loading ? (
        [1, 2, 3].map((i) => <Skeleton key={i} height={56} />)
      ) : (
        groups.map((group) => (
          <Box key={group.key} component="section" aria-labelledby={`routines-${group.key}`} sx={{ mb: 3 }}>
            <Typography id={`routines-${group.key}`} variant="subtitle1" component="h2" sx={{ fontWeight: 600, mb: 1 }}>
              {group.label}
            </Typography>
            <List disablePadding aria-labelledby={`routines-${group.key}`} sx={{ display: 'grid', gap: 1 }}>
              {group.routines.map((routine) => (
                <ListItem
                  key={routine.id}
                  disablePadding
                  data-testid="routine-card"
                  sx={{
                    border: 1,
                    borderColor: 'divider',
                    borderRadius: 2,
                    pl: 0.5,
                    opacity: routine.active ? 1 : 0.5,
                  }}
                >
                  {routine.dueToday ? (
                    <Checkbox
                      checked={routine.checkedToday}
                      onChange={() => toggle(routine)}
                      icon={<RadioButtonUncheckedIcon />}
                      checkedIcon={
                        <CheckCircleIcon
                          sx={{
                            color: ROUTINE_COLOR,
                            ...(ticked === routine.id ? motion(animate, `${pop} 320ms ease-out`) : {}),
                          }}
                        />
                      }
                      slotProps={{ input: { 'aria-label': `${routine.title} : fait aujourd'hui` } }}
                    />
                  ) : (
                    // The room of the tick, so the titles stay in line.
                    <Box aria-hidden sx={{ width: 42, flexShrink: 0 }} />
                  )}
                  <ButtonBase
                    onClick={() => {
                      setOpening((n) => n + 1)
                      setEditing(routine)
                      setDialogOpen(true)
                    }}
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      justifyContent: 'space-between',
                      textAlign: 'left',
                      gap: 2,
                      py: 1.25,
                      pr: 1.5,
                      borderRadius: 2,
                      flexWrap: 'wrap',
                      '&.Mui-focusVisible': { outline: 2, outlineColor: ROUTINE_COLOR, outlineOffset: -2 },
                    }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body1" sx={{ fontWeight: 500 }}>
                        {routine.title}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {whenLabel(routine)}
                      </Typography>
                    </Box>
                    <Stack alignItems="flex-end" spacing={0.5}>
                      <RecentDays days={routine.recentDays} />
                      <Typography variant="caption" color="text.secondary" data-testid="routine-count">
                        {countLabel(routine)}
                      </Typography>
                    </Stack>
                  </ButtonBase>
                </ListItem>
              ))}
            </List>
          </Box>
        ))
      )}

      <RoutineDialog
        key={opening}
        open={dialogOpen}
        routine={editing}
        today={today}
        onClose={() => setDialogOpen(false)}
        onSaved={load}
      />
    </Box>
  )
}
