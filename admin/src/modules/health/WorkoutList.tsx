import { useMemo, useState } from 'react'
import { useListContext, useNotify } from 'react-admin'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { SourcePill, WorkoutDetail } from './WorkoutDetail'
import { WorkoutForm } from './WorkoutForm'
import { WeeklyMinutesCard } from './WeeklyMinutesCard'
import {
  feelingLabel,
  formatDistance,
  formatDuration,
  formatShortDate,
  SOURCE_LABELS,
  TYPE_LABELS,
  typeLabel,
  WORKOUT_SOURCES,
  WORKOUT_TYPES,
  workoutUlid,
} from './workoutFormat'
import type { Workout } from './workoutFormat'
import { WORKOUT_TOPICS } from './workoutsApi'

const PERIODS = [30, 90, 365] as const
type Period = (typeof PERIODS)[number]
const DEFAULT_PERIOD: Period = 90

interface Filters {
  type?: string
  source?: string
  period: Period
}

/** Local midnight `days` days ago, as an instant. */
const sinceDays = (days: number): string => {
  const since = new Date()
  since.setHours(0, 0, 0, 0)
  since.setDate(since.getDate() - days)

  return since.toISOString()
}

const head = { textAlign: 'left', py: 1.125, px: 1.25, fontSize: 12, fontWeight: 500 } as const
const cell = { py: 1.125, px: 1.25, fontSize: 13 } as const
const right = { textAlign: 'right' } as const

interface FilterMenuProps {
  label: string
  value: string | undefined
  options: [string, string][]
  allLabel?: string
  onChange: (value: string | undefined) => void
}

/** « Type ▾ »: a button that opens the choices; the chosen one replaces its label. */
const FilterMenu = ({ label, value, options, allLabel, onChange }: FilterMenuProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const current = options.find(([key]) => key === value)

  return (
    <>
      <Button
        variant="text"
        aria-haspopup="menu"
        onClick={(event) => setAnchor(event.currentTarget)}
        sx={(theme) => ({ height: 32, px: 1.5, borderRadius: 2, fontSize: 13, color: 'text.primary', bgcolor: alpha(theme.palette.text.primary, 0.08), fontVariantNumeric: 'tabular-nums' })}
      >
        {`${current ? current[1] : label} ▾`}
      </Button>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        {allLabel && (
          <MenuItem
            selected={value === undefined}
            onClick={() => {
              setAnchor(null)
              onChange(undefined)
            }}
          >
            {allLabel}
          </MenuItem>
        )}
        {options.map(([key, text]) => (
          <MenuItem
            key={key}
            selected={key === value}
            onClick={() => {
              setAnchor(null)
              onChange(key)
            }}
          >
            {text}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}

interface BoardProps {
  filters: Filters
  onFilters: (filters: Filters) => void
  withChart: boolean
}

const WorkoutsBoard = ({ filters, onFilters, withChart }: BoardProps) => {
  const { data, isPending } = useListContext<Workout>()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // The workout being edited is kept as it was opened: an event must not rewrite what is being typed.
  const [form, setForm] = useState<{ workout?: Workout } | null>(null)
  const workouts = data ?? []
  const selected = workouts.find((workout) => workout.id === selectedId) ?? workouts[0]
  const filtered = filters.type !== undefined || filters.source !== undefined

  return (
    <Box data-testid="workouts-board" sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, py: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <FilterMenu
          label="Type"
          value={filters.type}
          allLabel="Tous les types"
          options={WORKOUT_TYPES.map((type) => [type, TYPE_LABELS[type]])}
          onChange={(type) => onFilters({ ...filters, type })}
        />
        <FilterMenu
          label="Source"
          value={filters.source}
          allLabel="Toutes les sources"
          options={WORKOUT_SOURCES.map((source) => [source, SOURCE_LABELS[source]])}
          onChange={(source) => onFilters({ ...filters, source })}
        />
        <FilterMenu
          label="90 j"
          value={String(filters.period)}
          options={PERIODS.map((days) => [String(days), `${days} j`])}
          onChange={(days) => onFilters({ ...filters, period: Number(days) as Period })}
        />
        <Button
          variant="contained"
          onClick={() => setForm({})}
          sx={(theme) => ({ height: 32, px: 1.75, borderRadius: 2, fontSize: 13, fontWeight: 500, boxShadow: 'none', bgcolor: theme.palette.module.sport, color: theme.palette.getContrastText(theme.palette.module.sport), '&:hover': { bgcolor: theme.palette.module.sport, boxShadow: 'none' } })}
        >
          Ajouter une séance
        </Button>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 2fr) minmax(0, 1fr)' }, gridTemplateRows: { md: 'auto 230px auto' }, gap: 1.75, minWidth: 0 }}>
        {withChart ? <WeeklyMinutesCard /> : <Box />}

        {selected && <WorkoutDetail key={selected.id} workout={selected} onEdit={() => setForm({ workout: selected })} onDeleted={() => setSelectedId(null)} />}

        <Box component="section" aria-label="Séances" sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, minWidth: 0, overflowX: 'auto', gridColumn: { md: 1 } }}>
          <Table size="small" sx={{ borderCollapse: 'collapse', fontVariantNumeric: 'tabular-nums' }}>
            <TableHead>
              <TableRow>
                {['Date', 'Séance', 'Type'].map((title) => (
                  <TableCell key={title} sx={(theme) => ({ ...head, color: theme.palette.veilleuse.caption })}>
                    {title}
                  </TableCell>
                ))}
                {['Durée', 'Distance', 'kcal', 'FC moy.'].map((title) => (
                  <TableCell key={title} sx={(theme) => ({ ...head, ...right, color: theme.palette.veilleuse.caption })}>
                    {title}
                  </TableCell>
                ))}
                {['Source', 'Ressenti'].map((title) => (
                  <TableCell key={title} sx={(theme) => ({ ...head, color: theme.palette.veilleuse.caption })}>
                    {title}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {workouts.map((workout) => {
                const isSelected = workout.id === selected?.id

                return (
                  <TableRow
                    key={workout.id}
                    hover
                    data-testid={`workout-row-${workoutUlid(workout.id)}`}
                    aria-current={isSelected ? 'true' : undefined}
                    onClick={() => setSelectedId(workout.id)}
                    sx={(theme) => ({ cursor: 'pointer', bgcolor: isSelected ? alpha(theme.palette.module.sport, 0.1) : 'transparent' })}
                  >
                    <TableCell sx={cell}>{formatShortDate(new Date(workout.startedAt))}</TableCell>
                    <TableCell sx={cell}>
                      <Link component="button" type="button" underline="none" color="text.primary" sx={{ fontSize: 13, textAlign: 'left' }} onClick={() => setSelectedId(workout.id)}>
                        {workout.title}
                      </Link>
                    </TableCell>
                    <TableCell sx={{ ...cell, color: 'text.secondary' }}>{typeLabel(workout.type)}</TableCell>
                    <TableCell sx={{ ...cell, ...right }}>{formatDuration(workout.durationMinutes)}</TableCell>
                    <TableCell sx={{ ...cell, ...right, color: 'text.secondary' }}>{workout.distanceMeters == null ? '' : formatDistance(workout.distanceMeters)}</TableCell>
                    <TableCell sx={{ ...cell, ...right }}>{workout.kcal ?? ''}</TableCell>
                    <TableCell sx={{ ...cell, ...right, color: 'text.secondary' }}>{workout.avgHeartRate ?? ''}</TableCell>
                    <TableCell sx={cell}>
                      <SourcePill source={workout.source} />
                    </TableCell>
                    <TableCell sx={{ ...cell, color: 'text.secondary' }}>{workout.feeling ? feelingLabel(workout.feeling) : ''}</TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
          {!isPending && workouts.length === 0 && (
            <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
              {filtered ? 'Aucune séance pour ce filtre' : 'Aucune séance pour l’instant'}
            </Typography>
          )}
        </Box>
      </Box>

      <WorkoutForm
        open={form !== null}
        workout={form?.workout}
        onClose={() => setForm(null)}
        onSaved={(id) => setSelectedId(id)}
      />
    </Box>
  )
}

/**
 * Santé › Sport (MAG-705): the weekly minutes, the workouts and the selected
 * one, on the mockup's grid. The heading is the layout's.
 */
export const WorkoutList = ({ withChart = true }: { withChart?: boolean }) => {
  const notify = useNotify()
  const [filters, setFilters] = useState<Filters>({ period: DEFAULT_PERIOD })
  const after = useMemo(() => sinceDays(filters.period), [filters.period])

  const query = useMemo(
    () => ({
      'startedAt[after]': after,
      ...(filters.type ? { type: filters.type } : {}),
      ...(filters.source ? { source: filters.source } : {}),
    }),
    [after, filters.type, filters.source],
  )
  const live = useMemo<LiveConfig>(
    () => ({
      topics: WORKOUT_TOPICS,
      required: ['type', 'title', 'startedAt', 'durationMinutes', 'source'],
      accept: (row) =>
        row.id.startsWith('/api/workouts/') &&
        (!filters.type || row.type === filters.type) &&
        (!filters.source || row.source === filters.source) &&
        new Date(String(row.startedAt)).getTime() >= new Date(after).getTime(),
    }),
    [after, filters.type, filters.source],
  )
  const queryOptions = useMemo(
    () => ({ onError: () => notify('Erreur : les séances n’ont pas pu être chargées.', { type: 'error' }) }),
    [notify],
  )

  return (
    <LiveList
      live={live}
      filter={query}
      sort={{ field: 'startedAt', order: 'DESC' }}
      perPage={200}
      actions={false}
      pagination={false}
      empty={false}
      queryOptions={queryOptions}
    >
      <WorkoutsBoard filters={filters} onFilters={setFilters} withChart={withChart} />
    </LiveList>
  )
}
