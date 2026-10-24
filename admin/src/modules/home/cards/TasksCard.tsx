import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'
import { localDay } from '../../../dates'
import { TOKENS } from '../../../design/tokens'
import { checkRoutine } from '../../calendar/routinesApi'
import { setTaskDone } from '../../calendar/taskViews'
import { HomeCard } from '../HomeCard'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { moduleColor } from '../moduleColor'
import type { HomeDay } from '../homeDay'
import { useTasksCardState } from '../useTasksCardState'
import { withRoutineChecked, withTaskDone } from './tasksCardState'
import type { HomeRoutine, HomeTask, TasksHomeState } from './tasksCardState'

const calm = {
  // A tick is felt, not performed: and nothing moves for those who asked it not to.
  transition: 'color 200ms ease, opacity 200ms ease, text-decoration-color 200ms ease',
  '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
} as const

type Op = (state: TasksHomeState) => TasksHomeState

const WRITE_FAILED = "Impossible d'enregistrer : rien n'a été modifié. Réessayez."

/** The « Tâches » card of the Accueil (MAG-561): the day's tasks and the routines of the moment, ticked in place. */
export const TasksCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useTasksCardState(day)
  // What the card shows between a tick and the API's answer: the ticks sent on top of the state they were made on.
  // A new read is a new state, which drops them — the API's word wins.
  const [optimistic, setOptimistic] = useState<{ base: TasksHomeState | null; ops: Op[] }>({ base: null, ops: [] })
  const [writeFailed, setWriteFailed] = useState(false)
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set())
  const inFlight = useRef(new Set<string>())

  // The day changes: a failure of the other day says nothing about this one.
  const [failedDay, setFailedDay] = useState(day)
  if (failedDay !== day) {
    setFailedDay(day)
    setWriteFailed(false)
  }

  const shown = state && optimistic.base === state ? optimistic.ops.reduce((s, op) => op(s), state) : state
  const status = homeCardStatus({ state: shown, loading, error }, isEmpty)
  useReportHomeCard('tasks', status)

  const tick = (key: string, op: Op, send: () => Promise<unknown>) => {
    if (inFlight.current.has(key) || !state) return
    inFlight.current.add(key)
    setPending(new Set(inFlight.current))
    setWriteFailed(false)
    setOptimistic((prev) => ({ base: state, ops: [...(prev.base === state ? prev.ops : []), op] }))
    send()
      .then(() => retry())
      .catch(() => {
        setWriteFailed(true)
        setOptimistic((prev) => ({ ...prev, ops: prev.ops.filter((o) => o !== op) }))
      })
      .finally(() => {
        inFlight.current.delete(key)
        setPending(new Set(inFlight.current))
      })
  }

  const toggleTask = (task: HomeTask) =>
    tick(`task:${task.id}`, (s) => withTaskDone(s, task.id, !task.done), () => setTaskDone(task.id, !task.done))

  const toggleRoutine = (routine: HomeRoutine) =>
    tick(
      `routine:${routine.id}`,
      (s) => withRoutineChecked(s, routine.id, !routine.checked),
      () => checkRoutine(routine.id, localDay(new Date()), !routine.checked),
    )

  if (status === 'empty') return null

  return (
    <HomeCard module="tasks" title="Tâches" to="/tasks">
      {!shown && loading ? (
        <Box aria-busy="true" data-testid="home-tasks-loading">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} height={32} />
          ))}
        </Box>
      ) : !shown ? (
        <HomeCardError onRetry={retry} />
      ) : (
        <>
          {error && <HomeCardError onRetry={retry} />}
          {writeFailed && (
            <Typography role="alert" variant="body2" color="error" sx={{ mb: 1 }}>
              {WRITE_FAILED}
            </Typography>
          )}
          {isEmpty(shown) ? (
            loading && !error && <Skeleton height={32} data-testid="home-tasks-loading" />
          ) : (
            <TasksBody state={shown} pending={pending} onTask={toggleTask} onRoutine={toggleRoutine} />
          )}
        </>
      )}
    </HomeCard>
  )
}

const isEmpty = (state: TasksHomeState) => state.tasks.length === 0 && state.routines.length === 0

interface BodyProps {
  state: TasksHomeState
  pending: ReadonlySet<string>
  onTask: (task: HomeTask) => void
  onRoutine: (routine: HomeRoutine) => void
}

const TasksBody = ({ state, pending, onTask, onRoutine }: BodyProps) => {
  const disabled = !state.editable

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      {state.tasks.length > 0 && (
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
          {state.editable && state.total > 0 && <Ring done={state.done} total={state.total} />}
          <Box sx={{ flex: 1, minWidth: 0 }}>
            {state.overdue > 0 && (
              <Typography variant="caption" color="error" component="p" sx={{ mb: 0.5 }}>
                {state.overdue} en retard
              </Typography>
            )}
            <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {state.tasks.map((task) => (
                <TaskRow key={task.id} task={task} disabled={disabled || pending.has(`task:${task.id}`)} onToggle={onTask} />
              ))}
            </Box>
            {state.more > 0 && (
              <Link component={RouterLink} to="/tasks" variant="body2" underline="hover" sx={{ display: 'inline-block', mt: 0.5 }}>
                {state.more === 1 ? '+ 1 autre' : `+ ${state.more} autres`}
              </Link>
            )}
          </Box>
        </Box>
      )}
      {state.saga && (
        <Typography variant="caption" color="text.secondary">
          {state.saga.title} · {state.saga.done}/{state.saga.total}
        </Typography>
      )}
      {state.routines.length > 0 && (
        <Box component="section" aria-label="Routines">
          <Typography variant="overline" color="text.secondary" component="h3">
            <Link component={RouterLink} to="/tasks?tab=routines" color="inherit" underline="hover">
              Routines
            </Link>
          </Typography>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 0.5 }}>
            {state.routines.map((routine) => (
              <RoutineChip key={routine.id} routine={routine} disabled={disabled || pending.has(`routine:${routine.id}`)} onToggle={onRoutine} />
            ))}
          </Box>
        </Box>
      )}
    </Box>
  )
}

const Ring = ({ done, total }: { done: number; total: number }) => (
  <Box
    data-testid="home-tasks-ring"
    sx={(theme) => ({ position: 'relative', display: 'inline-flex', flexShrink: 0, color: moduleColor(theme, 'tasks') })}
  >
    <CircularProgress
      variant="determinate"
      value={Math.min(100, (done / total) * 100)}
      size={56}
      thickness={4}
      color="inherit"
      aria-label="Tâches faites"
      aria-valuetext={`${done} sur ${total}`}
          />
    <Typography
      variant="caption"
      color="text.primary"
      sx={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center', lineHeight: 1.1, px: 0.5 }}
    >
      {`${done} sur ${total}`}
    </Typography>
  </Box>
)

const TaskRow = ({ task, disabled, onToggle }: { task: HomeTask; disabled: boolean; onToggle: (task: HomeTask) => void }) => (
  <Box
    component="li"
    sx={{ display: 'flex', alignItems: 'center', gap: 0.5, pl: task.parentId ? 4 : 0, minHeight: 32 }}
  >
    <Checkbox
      size="small"
      checked={task.done}
      disabled={disabled}
      onChange={() => onToggle(task)}
      slotProps={{ input: { 'aria-label': `${task.title} : fait` } }}
      sx={(theme) => ({
        p: 0.5,
        [theme.breakpoints.down('md')]: { p: 1.25 },
        ...calm,
      })}
    />
    <Box sx={{ minWidth: 0, flex: 1 }}>
      <Typography
        variant="body2"
        noWrap
        sx={{
          ...calm,
          textDecoration: task.done ? 'line-through' : 'none',
          color: task.done ? 'text.disabled' : 'text.primary',
        }}
      >
        {task.title}
      </Typography>
    </Box>
    {!task.done && task.overdueDays != null && task.overdueDays > 0 && (
      <Typography variant="caption" color="error" sx={{ flexShrink: 0 }}>
        {task.overdueDays} j de retard
      </Typography>
    )}
  </Box>
)

const RoutineChip = ({
  routine,
  disabled,
  onToggle,
}: {
  routine: HomeRoutine
  disabled: boolean
  onToggle: (routine: HomeRoutine) => void
}) => (
  <Box
    component="label"
    sx={(theme) => ({
      display: 'inline-flex',
      alignItems: 'center',
      pr: 1.5,
      borderRadius: `${TOKENS.radius.pill}px`,
      border: 1,
      borderColor: routine.checked ? 'transparent' : 'divider',
      bgcolor: routine.checked ? 'action.selected' : 'transparent',
      cursor: disabled ? 'default' : 'pointer',
      opacity: disabled && !routine.checked ? 0.7 : 1,
      ...calm,
      [theme.breakpoints.down('md')]: { minHeight: 48 },
    })}
  >
    <Checkbox
      size="small"
      checked={routine.checked}
      disabled={disabled}
      onChange={() => onToggle(routine)}
      slotProps={{ input: { 'aria-label': `${routine.title} : fait` } }}
      sx={{ p: 0.5 }}
    />
    <Typography
      variant="body2"
      sx={{
        ...calm,
        textDecoration: routine.checked ? 'line-through' : 'none',
        color: routine.checked ? 'text.secondary' : 'text.primary',
      }}
    >
      {routine.title}
    </Typography>
  </Box>
)
