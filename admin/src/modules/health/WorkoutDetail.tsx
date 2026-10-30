import { useDelete, useNotify } from 'react-admin'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import {
  feelingLabel,
  formatDistance,
  formatDuration,
  formatLongDate,
  sourceLabel,
  typeLabel,
  workoutUlid,
} from './workoutFormat'
import type { Workout, WorkoutType } from './workoutFormat'

const ICONS: Record<WorkoutType, string> = {
  strength: 'M6.5 7v10M17.5 7v10M3.5 10v4M20.5 10v4M6.5 12h11',
  run: 'M14 3.5a1.6 1.6 0 1 0 0.01 0M9.5 21l2.5-6 3 2.5V21M7 11.5l3.5-3.5 4.5 1 2.5 3.5M12 15l-1.5-4',
  bike: 'M5.5 14a3.5 3.5 0 1 0 0.01 0M18.5 14a3.5 3.5 0 1 0 0.01 0M5.5 17.5L9 9.5h5.5l4 8M12 9.5l-1.2-3H8.5',
  walk: 'M13 3.5a1.6 1.6 0 1 0 0.01 0M11.5 8l-2 5.5 3 2.5 1 5M11.5 8l3 3.5 3 1M9.5 13.5L7.5 21',
  climbing: 'M3.5 20l5.5-10 4 5 3-4.5 4.5 9.5z',
  swim: 'M3 17c1.5 1.2 3 1.2 4.5 0s3-1.2 4.5 0 3 1.2 4.5 0 3-1.2 4.5 0M3 12.5c1.5 1.2 3 1.2 4.5 0s3-1.2 4.5 0 3 1.2 4.5 0 3-1.2 4.5 0M15 5.5a1.6 1.6 0 1 0 0.01 0',
  other: 'M12 7a5 5 0 1 0 0.01 0M12 11v2',
}

/** The workout's glyph, in the circle of the mockup. */
export const WorkoutIcon = ({ type, size = 38 }: { type: WorkoutType; size?: number }) => (
  <Box
    component="span"
    sx={(theme) => ({
      width: size,
      height: size,
      flex: 'none',
      borderRadius: '50%',
      bgcolor: theme.palette.veilleuse.raised,
      color: theme.palette.module.sport,
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
    })}
  >
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={ICONS[type] ?? ICONS.other} />
    </svg>
  </Box>
)

/** « Saisie », « Montre »…: a small pill. */
export const SourcePill = ({ source }: { source: string }) => (
  <Box
    component="span"
    sx={(theme) => ({
      display: 'inline-flex',
      alignItems: 'center',
      height: 22,
      px: 1,
      borderRadius: 999,
      bgcolor: alpha(theme.palette.text.primary, 0.06),
      color: 'text.secondary',
      fontSize: 11,
      flex: 'none',
    })}
  >
    {sourceLabel(source)}
  </Box>
)

interface WorkoutDetailProps {
  workout: Workout
  onEdit: () => void
  onDeleted: () => void
}

const Metric = ({ label, value }: { label: string; value: string }) => (
  <Box
    data-testid="workout-metric"
    sx={(theme) => ({ borderRadius: 3.5, bgcolor: theme.palette.veilleuse.raised, px: 1.5, py: 1.25, display: 'flex', flexDirection: 'column', gap: 0.25 })}
  >
    <Typography component="span" sx={(theme) => ({ fontSize: 12, color: theme.palette.veilleuse.caption })}>
      {label}
    </Typography>
    <Typography component="span" sx={{ fontSize: 18, fontVariantNumeric: 'tabular-nums' }}>
      {value}
    </Typography>
  </Box>
)

/** « Séance »: the selected workout, its figures and how it felt. */
export const WorkoutDetail = ({ workout, onEdit, onDeleted }: WorkoutDetailProps) => {
  const notify = useNotify()
  const [deleteOne] = useDelete()

  const metrics: [string, string | null][] = [
    ['Durée', formatDuration(workout.durationMinutes)],
    ['Distance', workout.distanceMeters == null ? null : formatDistance(workout.distanceMeters)],
    ['kcal', workout.kcal == null ? null : String(workout.kcal)],
    ['FC moy.', workout.avgHeartRate == null ? null : `${workout.avgHeartRate} bpm`],
  ]

  const remove = () => {
    deleteOne(
      'workouts',
      { id: workout.id, previousData: workout },
      {
        mutationMode: 'undoable',
        onError: () => notify(`Erreur : la séance « ${workout.title} » n’a pas été supprimée.`, { type: 'error' }),
      },
    )
    notify('Séance supprimée', { type: 'info', undoable: true })
    onDeleted()
  }

  return (
    <Box
      component="section"
      aria-label="Séance"
      data-testid={`workout-detail-${workoutUlid(workout.id)}`}
      sx={{ gridRow: { md: '2 / 4' }, gridColumn: { md: 2 }, borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0 }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <WorkoutIcon type={workout.type} size={44} />
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, flexGrow: 1, minWidth: 0 }}>
          <Typography component="span" sx={{ fontSize: 20, fontWeight: 500 }}>
            {workout.title}
          </Typography>
          <Typography component="span" sx={(theme) => ({ fontSize: 12, color: theme.palette.veilleuse.caption, fontVariantNumeric: 'tabular-nums' })}>
            {typeLabel(workout.type)} · {formatLongDate(new Date(workout.startedAt))}
          </Typography>
        </Box>
        <SourcePill source={workout.source} />
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
        {metrics.flatMap(([label, value]) => (value === null ? [] : [<Metric key={label} label={label} value={value} />]))}
      </Box>
      {workout.source !== 'planned' && (
        <Box sx={(theme) => ({ display: 'flex', justifyContent: 'space-between', minHeight: 44, alignItems: 'center', borderTop: `1px solid ${theme.palette.divider}`, fontSize: 15 })}>
          <Typography component="span" sx={{ color: 'text.secondary' }}>
            Ressenti
          </Typography>
          <span>{workout.feeling ? feelingLabel(workout.feeling) : ''}</span>
        </Box>
      )}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Button size="small" onClick={onEdit}>
          Modifier
        </Button>
        <IconButton size="small" aria-label={`Supprimer ${workout.title}`} onClick={remove}>
          <DeleteOutlineIcon fontSize="small" />
        </IconButton>
      </Box>
    </Box>
  )
}
