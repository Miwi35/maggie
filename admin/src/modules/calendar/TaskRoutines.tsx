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
import { alpha, keyframes } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import CheckIcon from '@mui/icons-material/Check'
import SyncIcon from '@mui/icons-material/Sync'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { RoutineDialog } from './RoutineDialog'
import { motion } from './motion'
import { checkRoutine, countLabel, fetchRoutines, GROUPS, groupOf, ROUTINE_TOPICS, rhythmLabel } from './routinesApi'
import type { Routine } from './routinesApi'

/** The green of the routines (MAG-511). */
export const ROUTINE_COLOR = '#7FD1AE'

const GRID = '44px minmax(0, 1fr) 190px 110px'

const capitals = (theme: Theme) => ({
  fontSize: 12,
  fontWeight: 500,
  letterSpacing: '0.14em',
  textTransform: 'uppercase',
  color: theme.palette.veilleuse.caption,
})

const pop = keyframes`
  0% { transform: scale(0.6); }
  60% { transform: scale(1.2); }
  100% { transform: scale(1); }
`

interface TaskRoutinesProps {
  /** The tick's little bounce, off with Réglages › Tâches like every animation of the page. */
  animate?: boolean
}

/**
 * « Routines » (MAG-511): the small things that come back — a moment, a rhythm, a
 * tick. Grouped by the part of the day for the daily ones, by their rhythm for the
 * others. A tick is for today only, on any active routine.
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

  // The routines' « today » is the API's, in the user's time zone: the last of the seven days.
  const serverToday = (routine: Routine): string => routine.recentDays[routine.recentDays.length - 1]?.date ?? localDay(new Date())
  // A new routine's default weekday and date: the API's today when a routine carries it.
  const today = routines.length > 0 ? serverToday(routines[0]) : localDay(new Date())

  // The ticks on their way, by routine: a second click before the answer sends nothing.
  const inFlight = useRef(new Set<string>())
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set())

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

  // A page left open past midnight reads the list again when it comes back: a new day, new ticks.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') load()
    }
    window.addEventListener('focus', load)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      window.removeEventListener('focus', load)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [load])

  const settle = (id: string) => {
    inFlight.current.delete(id)
    setPending(new Set(inFlight.current))
  }

  const toggle = (routine: Routine) => {
    if (inFlight.current.has(routine.id)) return
    inFlight.current.add(routine.id)
    setPending(new Set(inFlight.current))
    const checked = !routine.checkedToday
    checkRoutine(routine.id, serverToday(routine), checked)
      .then((updated) => {
        request.current++
        setRoutines((list) => list.map((r) => (r.id === updated.id ? updated : r)))
        setTicked(checked ? routine.id : null)
      })
      .catch((error: Error) => notify(`Erreur : ${error.message}`, { type: 'info' }))
      .finally(() => settle(routine.id))
  }

  const groups = GROUPS.map((group) => ({ ...group, routines: routines.filter((r) => groupOf(r) === group.key) })).filter(
    (group) => group.routines.length > 0,
  )

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
        <Button
          startIcon={<AddIcon />}
          variant="contained"
          onClick={() => {
            setOpening((n) => n + 1)
            setEditing(null)
            setDialogOpen(true)
          }}
          sx={{ borderRadius: 999, height: 36, bgcolor: 'text.primary', color: 'background.default', boxShadow: 'none' }}
        >
          Nouvelle routine
        </Button>
      </Box>

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
        <>
          {groups.length > 0 && (
            <Box aria-hidden sx={(theme) => ({ ...capitals(theme), display: 'grid', gridTemplateColumns: GRID, px: 0.5, pb: 0.5 })}>
              <Box sx={{ gridColumn: 2 }}>Tâche</Box>
              <Box>Quand</Box>
              <Box>Urgence</Box>
            </Box>
          )}
          {groups.map((group) => (
          <Box key={group.key} component="section" aria-labelledby={`routines-${group.key}`} sx={{ mb: 3 }}>
            <Typography
              id={`routines-${group.key}`}
              component="h2"
              sx={(theme) => ({ ...capitals(theme), px: 1.75, pt: 1.25, pb: 0.25 })}
            >
              {group.label}
            </Typography>
            <List disablePadding aria-labelledby={`routines-${group.key}`} sx={{ display: 'grid', gap: 0.75 }}>
              {group.routines.map((routine) => (
                <ListItem
                  key={routine.id}
                  disablePadding
                  data-testid="routine-card"
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: GRID,
                    alignItems: 'center',
                    bgcolor: 'background.paper',
                    borderRadius: 3.5,
                    minHeight: 56,
                    px: 0.5,
                    opacity: !routine.active ? 0.5 : routine.checkedToday ? 0.6 : 1,
                  }}
                >
                  {routine.active ? (
                    <Checkbox
                      checked={routine.checkedToday}
                      disabled={pending.has(routine.id)}
                      onChange={() => toggle(routine)}
                      icon={<Box sx={{ width: 22, height: 22, borderRadius: '7px', border: 2, borderColor: 'text.secondary' }} />}
                      checkedIcon={
                        <Box
                          sx={{
                            width: 22,
                            height: 22,
                            borderRadius: '7px',
                            bgcolor: ROUTINE_COLOR,
                            color: (theme) => theme.palette.veilleuse.onInk,
                            display: 'grid',
                            placeItems: 'center',
                            ...(ticked === routine.id ? motion(animate, `${pop} 320ms ease-out`) : {}),
                          }}
                        >
                          <CheckIcon sx={{ fontSize: 16 }} />
                        </Box>
                      }
                      slotProps={{ input: { 'aria-label': `${routine.title} : fait aujourd'hui` } }}
                    />
                  ) : (
                    <Box aria-hidden />
                  )}
                  <ButtonBase
                    onClick={() => {
                      setOpening((n) => n + 1)
                      setEditing(routine)
                      setDialogOpen(true)
                    }}
                    sx={{
                      gridColumn: 2,
                      minWidth: 0,
                      justifyContent: 'flex-start',
                      textAlign: 'left',
                      py: 1,
                      borderRadius: 2,
                      '&.Mui-focusVisible': { outline: 2, outlineColor: ROUTINE_COLOR, outlineOffset: -2 },
                    }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography
                        variant="body1"
                        sx={{ fontWeight: 500, textDecoration: routine.checkedToday ? 'line-through' : 'none' }}
                      >
                        {routine.title}
                      </Typography>
                      <Typography
                        variant="caption"
                        data-testid="routine-count"
                        sx={(theme) => ({ display: 'block', color: theme.palette.veilleuse.caption })}
                      >
                        {countLabel(routine)}
                      </Typography>
                    </Box>
                  </ButtonBase>
                  <Box>
                    <Box
                      data-testid="routine-when"
                      sx={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 0.5,
                        height: 24,
                        px: 1.25,
                        borderRadius: 999,
                        fontSize: 12,
                        fontWeight: 500,
                        bgcolor: alpha(ROUTINE_COLOR, 0.14),
                        color: ROUTINE_COLOR,
                      }}
                    >
                      <SyncIcon sx={{ fontSize: 11 }} />
                      {rhythmLabel(routine)}
                    </Box>
                  </Box>
                  <Box aria-hidden />
                </ListItem>
              ))}
            </List>
          </Box>
        ))}
        </>
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
