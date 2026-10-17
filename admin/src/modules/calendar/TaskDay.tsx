import { useMemo, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { useNotify } from 'react-admin'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  pointerWithin,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { CollisionDetection, DragEndEvent } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import CheckIcon from '@mui/icons-material/Check'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import RemoveCircleOutlineIcon from '@mui/icons-material/RemoveCircleOutline'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import TimerOutlinedIcon from '@mui/icons-material/TimerOutlined'
import UpdateIcon from '@mui/icons-material/Update'
import { UrgencyBadge } from './UrgencyBadge'
import { TaskBravo } from './TaskBravo'
import { TaskFocus } from './TaskFocus'
import { DAY_SLOTS, deferOnDay, placeOnDay, saveDayPlan } from './dayPlan'
import type { DayPlan, DayTarget, DayTask } from './dayPlan'
import { DEFAULT_CHEER, fetchReward, playBravoSound } from './reward'
import type { Reward, RewardCheer } from './reward'
import { createTask, postponedLabel, setTaskDone } from './taskViews'

interface TaskDayProps {
  day: DayPlan
  /** The local day shown, `YYYY-MM-DD`. */
  today: string
  /** The day as the API answered a write. */
  onDay: (day: DayPlan) => void
  /** Reads the day and the counts again. */
  onReload: () => void
  /** Opens the edit form of a task, by id. */
  onEdit: (taskId: string) => void
  /** Opens the morning's proposal again (MAG-498). */
  onChange?: () => void
  /** Opens the evening's review on demand (MAG-500). */
  onEvening?: () => void
  /** How a task done is cheered (MAG-508). */
  cheer?: RewardCheer
}

const FREE = 'place:free'

const placeId = (index: number): string => `place:${index}`

const parsePlace = (id: unknown): DayTarget | null => {
  if (id === FREE) return 'free'
  const match = /^place:(\d)$/.exec(String(id))

  return match ? Number(match[1]) : null
}

/** The pointer drops where it is; the keyboard, which has none, on the nearest place. */
const collision: CollisionDetection = (args) => (args.pointerCoordinates ? pointerWithin(args) : closestCenter(args))

/**
 * « reportée depuis … » in a neutral colour, never red; a critical task keeps
 * its own mark instead (MAG-497). And its minutes, when it has some.
 */
const TaskMarks = ({ task, today }: { task: DayTask; today: string }) => {
  const critical = task.urgency === 'critical'
  const postponed = critical ? null : postponedLabel(task.postponedSince, today)

  return (
    <>
      {critical && <UrgencyBadge urgency={task.urgency} />}
      {postponed && (
        <Typography
          component="span"
          variant="caption"
          color="text.secondary"
          sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
        >
          <UpdateIcon aria-hidden fontSize="inherit" />
          {postponed}
        </Typography>
      )}
      {task.estimatedMinutes != null && <Minutes value={task.estimatedMinutes} />}
    </>
  )
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

interface SlotRowProps {
  task: DayTask
  index: number
  count: number
  today: string
  busy: boolean
  onToggle: () => void
  onEdit: () => void
  onFocus: () => void
  onMove: (offset: -1 | 1) => void
  onRemove: () => void
}

/** A slot of the day: a place a reserve task can be dropped on, and a task that can be dragged elsewhere. */
const SlotRow = ({ task, index, count, today, busy, onToggle, onEdit, onFocus, onMove, onRemove }: SlotRowProps) => {
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id: placeId(index) })
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, isDragging } = useDraggable({
    id: `slot:${task.id}`,
    data: { taskId: task.id },
    disabled: busy,
    attributes: { roleDescription: 'tâche déplaçable' },
  })
  const done = task.completedAt != null

  return (
    <ListItem
      ref={(node) => {
        setDropRef(node)
        setNodeRef(node)
      }}
      disablePadding
      style={{ transform: CSS.Translate.toString(transform) }}
      sx={{
        gap: 0.5,
        borderRadius: 1,
        border: 1,
        borderColor: isOver ? 'primary.main' : 'divider',
        mb: 0.5,
        pr: 0.5,
        ...(isDragging && { zIndex: 10, boxShadow: 3, bgcolor: 'background.paper' }),
      }}
    >
      <IconButton
        size="small"
        ref={setActivatorNodeRef}
        {...attributes}
        {...listeners}
        aria-label={`Déplacer ${task.title}`}
        sx={{ cursor: busy ? 'default' : 'grab', touchAction: 'none' }}
      >
        <DragIndicatorIcon fontSize="small" />
      </IconButton>
      <Checkbox
        size="small"
        checked={done}
        onChange={onToggle}
        slotProps={{ input: { 'aria-label': `${done ? 'Rouvrir' : 'Terminer'} ${task.title}` } }}
      />
      <Box
        onClick={onEdit}
        sx={{ flex: 1, minWidth: 0, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, cursor: 'pointer', py: 1 }}
      >
        <Typography
          variant="body2"
          sx={{
            flex: 1,
            minWidth: 0,
            textDecoration: done ? 'line-through' : 'none',
            color: done ? 'text.disabled' : 'text.primary',
          }}
        >
          {task.title}
        </Typography>
        <TaskMarks task={task} today={today} />
      </Box>
      {!done && (
        <IconButton size="small" aria-label={`Se concentrer sur ${task.title}`} title="Focus" onClick={onFocus}>
          <CenterFocusStrongIcon fontSize="small" />
        </IconButton>
      )}
      <IconButton size="small" aria-label={`Monter ${task.title}`} disabled={busy || index === 0} onClick={() => onMove(-1)}>
        <ArrowUpwardIcon fontSize="small" />
      </IconButton>
      <IconButton
        size="small"
        aria-label={`Descendre ${task.title}`}
        disabled={busy || index === count - 1}
        onClick={() => onMove(1)}
      >
        <ArrowDownwardIcon fontSize="small" />
      </IconButton>
      <IconButton
        size="small"
        aria-label={`Remettre ${task.title} dans la réserve`}
        title="Remettre dans la réserve"
        disabled={busy}
        onClick={onRemove}
      >
        <RemoveCircleOutlineIcon fontSize="small" />
      </IconButton>
    </ListItem>
  )
}

/** The room left in the day: one dashed place per free slot, where a reserve task can be dropped. */
const FreePlaces = ({ count }: { count: number }) => {
  const { setNodeRef, isOver } = useDroppable({ id: FREE })

  return (
    <Box ref={setNodeRef} data-testid="day-free" sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {Array.from({ length: count }, (_, i) => (
        <Box
          key={i}
          aria-hidden
          sx={{ height: 36, borderRadius: 1, border: '1px dashed', borderColor: isOver ? 'primary.main' : 'divider' }}
        />
      ))}
    </Box>
  )
}

interface ReserveRowProps {
  task: DayTask
  today: string
  slots: DayTask[]
  busy: boolean
  onEdit: () => void
  onPlace: (target: DayTarget) => void
}

const ReserveRow = ({ task, today, slots, busy, onEdit, onPlace }: ReserveRowProps) => {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, isDragging } = useDraggable({
    id: `reserve:${task.id}`,
    data: { taskId: task.id },
    disabled: busy,
    attributes: { roleDescription: 'tâche déplaçable' },
  })
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const full = slots.length >= DAY_SLOTS

  return (
    <ListItem
      ref={setNodeRef}
      disablePadding
      style={{ transform: CSS.Translate.toString(transform) }}
      sx={{ gap: 0.5, pr: 0.5, ...(isDragging && { zIndex: 10, boxShadow: 3, bgcolor: 'background.paper' }) }}
    >
      <IconButton
        size="small"
        ref={setActivatorNodeRef}
        {...attributes}
        {...listeners}
        aria-label={`Déplacer ${task.title}`}
        sx={{ cursor: busy ? 'default' : 'grab', touchAction: 'none' }}
      >
        <DragIndicatorIcon fontSize="small" />
      </IconButton>
      <Box
        onClick={onEdit}
        sx={{ flex: 1, minWidth: 0, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, cursor: 'pointer', py: 1 }}
      >
        <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
          {task.title}
        </Typography>
        <TaskMarks task={task} today={today} />
      </Box>
      {full ? (
        <>
          <IconButton
            size="small"
            aria-label={`Mettre ${task.title} à la place de…`}
            title="À la place de…"
            aria-haspopup="menu"
            disabled={busy}
            onClick={(e) => setAnchor(e.currentTarget)}
          >
            <SwapHorizIcon fontSize="small" />
          </IconButton>
          <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
            {slots.map((slot, index) => (
              <MenuItem
                key={slot.id}
                onClick={() => {
                  setAnchor(null)
                  onPlace(index)
                }}
              >
                À la place de {slot.title}
              </MenuItem>
            ))}
          </Menu>
        </>
      ) : (
        <IconButton
          size="small"
          aria-label={`Ajouter ${task.title} à la journée`}
          title="Ajouter à la journée"
          disabled={busy}
          onClick={() => onPlace('free')}
        >
          <AddIcon fontSize="small" />
        </IconButton>
      )}
    </ListItem>
  )
}

/**
 * The « Aujourd'hui » tab (MAG-497): five tasks at most, « Maintenant » — the
 * first not done — on top, and the reserve folded away below. Every change
 * sends the whole list to the API, which answers the day as it stands.
 */
export const TaskDay = ({
  day,
  today,
  onDay,
  onReload,
  onEdit,
  onChange,
  onEvening,
  cheer = DEFAULT_CHEER,
}: TaskDayProps) => {
  const notify = useNotify()
  const [busy, setBusy] = useState(false)
  const [reserveOpen, setReserveOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [focusId, setFocusId] = useState<string | null>(null)
  const [bravo, setBravo] = useState<{ task: DayTask; reward: Reward } | null>(null)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )

  const slotTasks = useMemo(() => day.slots.map((slot) => slot.task), [day.slots])
  const ids = slotTasks.map((task) => task.id)

  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  const save = (next: string[] | null) => {
    if (!next || busy) return
    setBusy(true)
    saveDayPlan(today, next)
      .then(onDay)
      .catch((error: Error) => {
        fail(error)
        onReload()
      })
      .finally(() => setBusy(false))
  }

  const place = (taskId: string, target: DayTarget) => save(placeOnDay(ids, taskId, target))

  // The tick laid down its reward (MAG-508): the bravo shows it, the plain notice only when it cannot be read.
  const celebrate = (task: DayTask) => {
    fetchReward(task.id)
      .then((reward) => {
        if (!reward) throw new Error('no reward')
        if (cheer.sound) playBravoSound()
        setBravo({ task, reward })
      })
      .catch(() => notify('Tâche terminée', { type: 'success' }))
  }

  const toggle = (task: DayTask) => {
    const done = task.completedAt == null
    setTaskDone(task.id, done)
      .then(() => {
        if (done) celebrate(task)
        else notify('Tâche rouverte', { type: 'success' })
        onReload()
      })
      .catch(fail)
  }

  // A new task takes a free slot; with the five taken it waits in the reserve.
  const create = () => {
    const value = title.trim()
    if (!value || busy) return
    setBusy(true)
    createTask(value)
      .then((id) => {
        setTitle('')
        if (id && ids.length < DAY_SLOTS) return saveDayPlan(today, [...ids, id]).then(onDay)
        onReload()
      })
      .catch((error: Error) => {
        fail(error)
        onReload()
      })
      .finally(() => setBusy(false))
  }

  const onTitleKey = (event: KeyboardEvent) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    create()
  }

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    const taskId = active.data.current?.taskId as string | undefined
    const target = parsePlace(over?.id)
    if (taskId && target !== null) place(taskId, target)
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

  const focused = focusId ? slotTasks.find((task) => task.id === focusId && task.completedAt == null) : undefined
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
    <DndContext sensors={sensors} collisionDetection={collision} onDragEnd={onDragEnd}>
      {overlay}
      {card}

      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
        <Typography variant="subtitle1" component="h2">
          Mes 5
        </Typography>
        <Stack direction="row" alignItems="center" spacing={1}>
          {onChange && (
            <Button size="small" color="inherit" onClick={onChange} disabled={busy}>
              Changer
            </Button>
          )}
          {onEvening && day.total > 0 && (
            <Button size="small" color="inherit" onClick={onEvening} disabled={busy}>
              Le soir
            </Button>
          )}
          <DayRing done={day.done} total={day.total} />
        </Stack>
      </Stack>

      <List disablePadding aria-label="Mes 5">
        {slotTasks.map((task, index) => (
          <SlotRow
            key={task.id}
            task={task}
            index={index}
            count={slotTasks.length}
            today={today}
            busy={busy}
            onToggle={() => toggle(task)}
            onEdit={() => onEdit(task.id)}
            onFocus={() => setFocusId(task.id)}
            onMove={(offset) => place(task.id, index + offset)}
            onRemove={() => save(ids.filter((id) => id !== task.id))}
          />
        ))}
      </List>
      {slotTasks.length < DAY_SLOTS && <FreePlaces count={DAY_SLOTS - slotTasks.length} />}

      <TextField
        label="Ajouter une tâche"
        size="small"
        fullWidth
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        onKeyDown={onTitleKey}
        disabled={busy}
        sx={{ mt: 2, mb: 3 }}
      />

      <Button
        onClick={() => setReserveOpen((open) => !open)}
        aria-expanded={reserveOpen}
        aria-controls="day-reserve"
        endIcon={reserveOpen ? <ExpandLessIcon /> : <ExpandMoreIcon />}
        color="inherit"
      >
        Réserve ({day.reserve.length})
      </Button>
      <Collapse in={reserveOpen} unmountOnExit>
        <List id="day-reserve" disablePadding aria-label="Réserve">
          {day.reserve.map((task) => (
            <ReserveRow
              key={task.id}
              task={task}
              today={today}
              slots={slotTasks}
              busy={busy}
              onEdit={() => onEdit(task.id)}
              onPlace={(target) => place(task.id, target)}
            />
          ))}
        </List>
      </Collapse>
    </DndContext>
  )
}
