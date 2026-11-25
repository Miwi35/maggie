import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import CheckIcon from '@mui/icons-material/Check'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import KeyboardDoubleArrowUpIcon from '@mui/icons-material/KeyboardDoubleArrowUp'
import Link from '@mui/material/Link'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import { Link as RouterLink } from 'react-router-dom'
import { localDay } from '../../../dates'
import { checkRoutine } from '../../calendar/routinesApi'
import { setTaskDone } from '../../calendar/taskViews'
import { HomeCard } from '../HomeCard'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { moduleColor } from '../moduleColor'
import type { HomeDay } from '../homeDay'
import { useTasksCardState } from '../useTasksCardState'
import { ringNext, routinesHeading, taskMeta, withRoutineChecked, withTaskDone } from './tasksCardState'
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
  const today = localDay(new Date())
  const routinesDone = state.routines.filter((r) => r.checked).length

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      {state.tasks.length > 0 && (
        <>
          {state.total > 0 && <Ring done={state.done} total={state.total} tomorrow={!state.editable} />}
          <Box>
            <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {state.tasks.map((task) => (
                <TaskRow
                  key={task.id}
                  task={task}
                  today={today}
                  disabled={disabled || pending.has(`task:${task.id}`)}
                  onToggle={onTask}
                />
              ))}
            </Box>
            {state.more > 0 && (
              <Link component={RouterLink} to="/tasks" variant="body2" underline="hover" sx={{ display: 'inline-block', mt: 0.5 }}>
                {state.more === 1 ? '+ 1 autre' : `+ ${state.more} autres`}
              </Link>
            )}
          </Box>
        </>
      )}
      {state.saga && (
        <Typography variant="caption" color="text.secondary">
          {state.saga.title} · {state.saga.done}/{state.saga.total}
        </Typography>
      )}
      {state.routines.length > 0 && (
        <Box component="section" aria-label="Routines" sx={{ borderTop: 1, borderColor: 'divider', pt: '6px' }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 12, color: 'text.disabled' }}>
            <Typography component="h3" sx={{ fontSize: 12, color: 'inherit' }}>
              <Link component={RouterLink} to="/tasks/routines" color="inherit" underline="hover">
                {routinesHeading(state.routines)}
              </Link>
            </Typography>
            <Typography data-testid="home-routines-count" sx={{ fontSize: 12, color: 'inherit' }}>
              {`${routinesDone} sur ${state.routines.length}`}
            </Typography>
          </Box>
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
            {state.routines.map((routine) => (
              <RoutineRow key={routine.id} routine={routine} disabled={disabled || pending.has(`routine:${routine.id}`)} onToggle={onRoutine} />
            ))}
          </Box>
        </Box>
      )}
    </Box>
  )
}

const RING = 52
const STROKE = 6
const RADIUS = (RING - STROKE) / 2
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const Ring = ({ done, total, tomorrow }: { done: number; total: number; tomorrow: boolean }) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
    <Box sx={{ position: 'relative', width: RING, height: RING, flexShrink: 0 }}>
      <svg
        width={RING}
        height={RING}
        viewBox={`0 0 ${RING} ${RING}`}
        role="img"
        aria-label={`${done} tâches faites sur ${total}`}
        style={{ transform: 'rotate(-90deg)', display: 'block' }}
      >
        <Box component="circle" cx={RING / 2} cy={RING / 2} r={RADIUS} fill="none" strokeWidth={STROKE} sx={(theme) => ({ stroke: theme.palette.veilleuse.track })} />
        <Box
          component="circle"
          cx={RING / 2}
          cy={RING / 2}
          r={RADIUS}
          fill="none"
          strokeWidth={STROKE}
          strokeLinecap="round"
          strokeDasharray={`${CIRCUMFERENCE * Math.min(1, done / total)} ${CIRCUMFERENCE}`}
          sx={(theme) => ({ stroke: moduleColor(theme, 'tasks') })}
        />
      </svg>
      <Typography
        aria-hidden
        sx={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 500 }}
      >
        {done}
      </Typography>
    </Box>
    <Box sx={{ minWidth: 0 }}>
      <Typography data-testid="home-tasks-ring" sx={{ fontSize: 16, fontWeight: 500 }}>
        {`${done} sur ${total}`}
      </Typography>
      <Typography data-testid="home-tasks-next" sx={{ fontSize: 13, color: 'text.secondary' }}>
        {ringNext(done, total, tomorrow)}
      </Typography>
    </Box>
  </Box>
)

const urgencyRing = (theme: Theme, urgency?: string | null): string =>
  urgency === 'critical' ? theme.palette.error.main : urgency === 'high' ? theme.palette.warning.main : alpha(theme.palette.text.primary, 0.24)

const BOX = 26

const Tick = ({
  checked,
  disabled,
  label,
  urgency,
  routine,
  onChange,
}: {
  checked: boolean
  disabled: boolean
  label: string
  urgency?: string | null
  routine?: boolean
  onChange: () => void
}) => (
  <Checkbox
    checked={checked}
    disabled={disabled}
    onChange={onChange}
    slotProps={{ input: { 'aria-label': label } }}
    icon={<Box sx={(theme) => ({ width: BOX, height: BOX, borderRadius: '9px', boxShadow: `inset 0 0 0 2px ${urgencyRing(theme, urgency)}`, ...calm })} />}
    checkedIcon={
      <Box
        sx={(theme) => ({
          width: BOX,
          height: BOX,
          borderRadius: '9px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          bgcolor: routine ? theme.palette.module.comptes : moduleColor(theme, 'tasks'),
          color: 'background.default',
          ...calm,
        })}
      >
        <CheckIcon sx={{ fontSize: 15 }} />
      </Box>
    }
    sx={(theme) => ({ p: 0, [theme.breakpoints.down('md')]: { p: 0 }, '&.Mui-disabled': { opacity: 1 } })}
  />
)

const URGENCY_PILLS: Record<string, { label: string; Icon: typeof KeyboardArrowUpIcon; color: 'error' | 'warning' }> = {
  critical: { label: 'Critique', Icon: KeyboardDoubleArrowUpIcon, color: 'error' },
  high: { label: 'Haute', Icon: KeyboardArrowUpIcon, color: 'warning' },
}

const UrgencyPill = ({ urgency }: { urgency: string }) => {
  const pill = URGENCY_PILLS[urgency]
  if (!pill) return null
  const { label, Icon, color } = pill

  return (
    <Box
      component="span"
      data-testid="home-task-urgency"
      sx={(theme) => ({
        display: 'inline-flex',
        alignItems: 'center',
        gap: '3px',
        height: 22,
        px: 1,
        borderRadius: '999px',
        fontSize: 12,
        fontWeight: 500,
        color: theme.palette[color].main,
        bgcolor: alpha(theme.palette[color].main, 0.16),
      })}
    >
      <Icon aria-hidden sx={{ fontSize: 11 }} />
      {label}
    </Box>
  )
}

const TaskRow = ({
  task,
  today,
  disabled,
  onToggle,
}: {
  task: HomeTask
  today: string
  disabled: boolean
  onToggle: (task: HomeTask) => void
}) => {
  const meta = taskMeta(task, today)
  const pill = !task.done && task.urgency ? task.urgency : null

  return (
    <Box component="li" sx={{ display: 'flex', alignItems: 'center', gap: 1.5, pl: task.parentId ? 4 : 0, minHeight: 48 }}>
      <Tick
        checked={task.done}
        disabled={disabled}
        label={`${task.title} : fait`}
        urgency={task.urgency}
        onChange={() => onToggle(task)}
      />
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography
          noWrap
          sx={{
            ...calm,
            fontSize: 15,
            textDecoration: task.done ? 'line-through' : 'none',
            color: task.done ? 'text.disabled' : 'text.primary',
          }}
        >
          {task.title}
        </Typography>
        {(pill || meta) && (
          <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '6px' }}>
            {pill && <UrgencyPill urgency={pill} />}
            {meta && (
              <Typography data-testid="home-task-meta" sx={{ fontSize: 12, color: 'text.disabled' }}>
                {meta}
              </Typography>
            )}
          </Box>
        )}
      </Box>
    </Box>
  )
}

const RoutineRow = ({
  routine,
  disabled,
  onToggle,
}: {
  routine: HomeRoutine
  disabled: boolean
  onToggle: (routine: HomeRoutine) => void
}) => (
  <Box component="li" sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 48 }}>
    <Tick checked={routine.checked} disabled={disabled} routine label={`${routine.title} : fait`} onChange={() => onToggle(routine)} />
    <Typography
      noWrap
      sx={{
        ...calm,
        fontSize: 14,
        minWidth: 0,
        textDecoration: routine.checked ? 'line-through' : 'none',
        color: routine.checked ? 'text.disabled' : 'text.primary',
      }}
    >
      {routine.title}
    </Typography>
  </Box>
)
