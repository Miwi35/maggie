import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import TimerOutlinedIcon from '@mui/icons-material/TimerOutlined'
import { RoutinesBand } from './RoutinesBand'
import { TaskBravo } from './TaskBravo'
import { TaskFocus } from './TaskFocus'
import { isLate, TaskList } from './TaskList'
import type { ListedTask, TaskGroup } from './TaskList'
import { deferOnDay, saveDayPlan } from './dayPlan'
import type { DayPlan, DayTask } from './dayPlan'
import { DEFAULT_CHEER, fetchReward, playBravoSound } from './reward'
import type { Reward, RewardCheer } from './reward'
import { fetchStars } from './stars'
import type { Stars } from './stars'
import { setTaskDone } from './taskViews'

interface TaskDayProps {
  day: DayPlan
  /** The local day shown, `YYYY-MM-DD`. */
  today: string
  /** The day as the API answered a write. */
  onDay: (day: DayPlan) => void
  /** Reads the day and the counts again. */
  onReload: () => void
  /** Opens the sheet of a task, by id. */
  onEdit: (taskId: string) => void
  /** The task a link points at (MAG-640): marked and scrolled to. */
  highlightId?: string | null
  /** The task whose sheet is open. */
  selectedId?: string | null
  /** Opens the morning's proposal again (MAG-498). */
  onChange?: () => void
  /** Opens the evening's review on demand (MAG-500). */
  onEvening?: () => void
  /** How a task done is cheered (MAG-508). */
  cheer?: RewardCheer
}

const Minutes = ({ value }: { value: number }) => (
  <Typography
    component="span"
    variant="caption"
    color="text.secondary"
    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}
  >
    <TimerOutlinedIcon aria-hidden fontSize="inherit" />
    {value} min
  </Typography>
)

/** The day's progress: one segment per slot, the done ones filled. */
export const DayRing = ({ done, total }: { done: number; total: number }) => {
  const theme = useTheme()
  if (total === 0) return null
  const size = 36
  const radius = 14
  const centre = size / 2
  const gap = total > 1 ? 14 : 0
  const point = (angle: number) => {
    const radians = ((angle - 90) * Math.PI) / 180

    return `${centre + radius * Math.cos(radians)} ${centre + radius * Math.sin(radians)}`
  }
  const step = 360 / total

  return (
    <Box role="img" aria-label={`${done} sur ${total}`} sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden>
        {Array.from({ length: total }, (_, i) => {
          const stroke = i < done ? theme.palette.primary.main : theme.palette.action.disabledBackground
          if (total === 1) {
            return <circle key={i} cx={centre} cy={centre} r={radius} fill="none" stroke={stroke} strokeWidth={5} />
          }
          const from = i * step + gap / 2
          const to = (i + 1) * step - gap / 2

          return (
            <path
              key={i}
              d={`M ${point(from)} A ${radius} ${radius} 0 ${to - from > 180 ? 1 : 0} 1 ${point(to)}`}
              fill="none"
              stroke={stroke}
              strokeWidth={5}
              strokeLinecap="round"
            />
          )
        })}
      </svg>
      <Typography variant="body2" color="text.secondary">
        {done} sur {total}
      </Typography>
    </Box>
  )
}

/**
 * The « Aujourd'hui » view (MAG-497, MAG-644): « Maintenant » — the first slot not done — on top,
 * then every task of the day in the full width, « En retard » apart from « Aujourd'hui ». The five
 * slots are the API's (and the morning's) to choose; the list draws them first, then the reserve.
 */
export const TaskDay = ({
  day,
  today,
  onDay,
  onReload,
  onEdit,
  highlightId = null,
  selectedId = null,
  onChange,
  onEvening,
  cheer = DEFAULT_CHEER,
}: TaskDayProps) => {
  const notify = useNotify()
  const [focusId, setFocusId] = useState<string | null>(null)
  const [bravo, setBravo] = useState<{ task: DayTask; reward: Reward; stars: Stars | null } | null>(null)

  const slotTasks = useMemo(() => day.slots.map((slot) => slot.task), [day.slots])
  const dayTasks = useMemo(() => [...slotTasks, ...day.reserve], [slotTasks, day.reserve])

  const groups = useMemo((): TaskGroup[] => {
    const late = dayTasks.filter((task) => isLate(task, today))
    const rest = dayTasks.filter((task) => !isLate(task, today))

    return [
      ...(late.length > 0 ? [{ label: 'En retard', tasks: late }] : []),
      ...(rest.length > 0 ? [{ label: "Aujourd'hui", tasks: rest }] : []),
    ]
  }, [dayTasks, today])

  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  // The tick laid down its reward (MAG-508): the bravo shows it, the plain notice only when it cannot be read.
  const celebrate = (task: DayTask) => {
    // The constellation is a bonus: the bravo comes without it when it cannot be read.
    Promise.all([fetchReward(task.id), fetchStars().catch(() => null)])
      .then(([reward, stars]) => {
        if (!reward) throw new Error('no reward')
        if (cheer.sound) playBravoSound()
        setBravo({ task, reward, stars })
      })
      .catch(() => {
        // No bravo to show: a Focus left open on the task done must not come back with it.
        setFocusId(null)
        notify('Tâche terminée', { type: 'success' })
      })
  }

  const toggle = (listed: ListedTask) => {
    const task = dayTasks.find((candidate) => candidate.id === listed.id)
    if (!task) return
    const done = task.completedAt == null
    setTaskDone(task.id, done)
      .then(() => {
        if (done) celebrate(task)
        else notify('Tâche rouverte', { type: 'success' })
        onReload()
      })
      .catch(fail)
  }

  // « Plus tard, sans souci »: the task goes after the other slots not done, and the day shows the next one.
  const later = async (taskId: string) => {
    const next = deferOnDay(
      day.slots.map((slot) => ({ id: slot.task.id, done: slot.task.completedAt != null })),
      taskId,
    )
    if (next) onDay(await saveDayPlan(today, next))
    setFocusId(null)
  }

  // The day may not have read the tick yet: the task just done counts as done, and is not next.
  let overlay: ReactNode = null
  if (bravo) {
    const doneNow = (task: DayTask) => task.completedAt != null || task.id === bravo.task.id
    const next = slotTasks.find((task) => !doneNow(task)) ?? null
    overlay = (
      <TaskBravo
        title={bravo.task.title}
        reward={bravo.reward}
        done={slotTasks.filter(doneNow).length}
        total={slotTasks.length}
        next={next}
        animate={cheer.animation}
        stars={bravo.stars}
        onNext={() => {
          setBravo(null)
          if (next) setFocusId(next.id)
        }}
        onPause={() => {
          setBravo(null)
          setFocusId(null)
        }}
      />
    )
  }

  const focused = focusId ? dayTasks.find((task) => task.id === focusId && task.completedAt == null) : undefined
  if (focused && !bravo) {
    return (
      <TaskFocus
        key={focused.id}
        task={focused}
        onBack={() => setFocusId(null)}
        onDone={() => {
          celebrate(focused)
          onReload()
        }}
        onLater={() => later(focused.id)}
      />
    )
  }

  const now = day.now?.task
  let card: ReactNode = null
  if (now) {
    card = (
      <Paper component="section" aria-labelledby="day-now" variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Typography id="day-now" variant="overline" color="text.secondary" component="h2">
          Maintenant
        </Typography>
        <Typography variant="h6" component="p">
          {now.title}
        </Typography>
        {now.firstStep && (
          <Typography variant="body2" color="text.secondary">
            {now.firstStep}
          </Typography>
        )}
        {now.estimatedMinutes != null && <Minutes value={now.estimatedMinutes} />}
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <Button variant="contained" startIcon={<CheckIcon />} onClick={() => toggle(now)}>
            C'est fait
          </Button>
          <Button variant="outlined" startIcon={<TimerOutlinedIcon />} onClick={() => setFocusId(now.id)}>
            Juste 10 minutes
          </Button>
        </Stack>
      </Paper>
    )
  }

  return (
    <>
      {overlay}
      <RoutinesBand />
      {card}

      <Stack direction="row" alignItems="center" justifyContent="flex-end" spacing={1} sx={{ mb: 1 }}>
        {onChange && (
          <Button size="small" color="inherit" onClick={onChange}>
            Changer
          </Button>
        )}
        {onEvening && day.total > 0 && (
          <Button size="small" color="inherit" onClick={onEvening}>
            Le soir
          </Button>
        )}
        <DayRing done={day.done} total={day.total} />
      </Stack>

      {groups.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Rien à faire aujourd'hui
        </Typography>
      ) : (
        <TaskList
          groups={groups}
          today={today}
          label="Tâches du jour"
          highlightId={highlightId}
          selectedId={selectedId}
          onToggle={toggle}
          onOpen={(task) => onEdit(task.id)}
          onFocus={(task) => setFocusId(task.id)}
        />
      )}
    </>
  )
}
