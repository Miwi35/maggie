import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import StarIcon from '@mui/icons-material/Star'
import TimerOutlinedIcon from '@mui/icons-material/TimerOutlined'
import UpdateIcon from '@mui/icons-material/Update'
import { parseDay } from '../../dates'
import { STAR_COLOR } from './Constellation'
import { UrgencyBadge } from './UrgencyBadge'
import { postponedLabel } from './taskViews'

/** What a row draws of a task: the day's tasks and the views' tasks both fit. */
export interface ListedTask {
  id: string
  '@id'?: string
  title: string
  urgency?: string | null
  dueDate?: string | null
  completedAt?: string | null
  postponedSince?: string | null
  estimatedMinutes?: number | null
  /** The IRI of the parent task: a subtask is indented under it when the parent is listed too. */
  parent?: string | null
  overdueDays?: number | null
}

export interface TaskGroup {
  /** The heading of the group; absent when the list has one group only. */
  label?: string
  tasks: ListedTask[]
}

interface TaskListProps {
  groups: TaskGroup[]
  /** The local day, `YYYY-MM-DD`. */
  today: string
  label: string
  /** The task a link points at (MAG-640): marked and scrolled to. */
  highlightId?: string | null
  /** The task whose sheet is open. */
  selectedId?: string | null
  onToggle: (task: ListedTask) => void
  onOpen: (task: ListedTask) => void
  /** Draws a « Focus » button on the tasks still open, and the star of the ones done. */
  onFocus?: (task: ListedTask) => void
}

const scrolled = new WeakSet<Element>()

/** Brings the row a link points at into view — once per row, whatever the re-renders. */
export const scrollToRow = (node: HTMLElement | null) => {
  if (!node || scrolled.has(node)) return
  scrolled.add(node)
  node.scrollIntoView?.({ block: 'center' })
}

const shortDay = (day: string): string =>
  parseDay(day.slice(0, 10)).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })

/** Open and due before today. */
export const isLate = (task: ListedTask, today: string): boolean => {
  if (task.completedAt != null) return false
  if (task.dueDate) return task.dueDate.slice(0, 10) < today

  return task.overdueDays != null && task.overdueDays > 0
}

const GRID = { xs: 'auto minmax(0, 1fr) auto', md: 'auto minmax(0, 1fr) 120px 140px 40px' }

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

interface RowProps extends Pick<TaskListProps, 'today' | 'onToggle' | 'onOpen' | 'onFocus'> {
  task: ListedTask
  indented: boolean
  highlighted: boolean
  selected: boolean
}

const Row = ({ task, today, indented, highlighted, selected, onToggle, onOpen, onFocus }: RowProps) => {
  const done = task.completedAt != null
  const late = isLate(task, today)
  // Put off, never late: a critical task keeps its own mark instead (MAG-497).
  const postponed = task.urgency === 'critical' ? null : postponedLabel(task.postponedSince, today)

  return (
    <Box
      component="li"
      ref={highlighted ? scrollToRow : undefined}
      aria-current={highlighted ? 'true' : undefined}
      sx={{
        display: 'grid',
        gridTemplateColumns: GRID,
        alignItems: 'center',
        columnGap: 1,
        pl: indented ? 4 : 0,
        pr: 1,
        borderBottom: 1,
        borderColor: 'divider',
        ...((highlighted || selected) && { bgcolor: 'action.selected' }),
      }}
    >
      <Checkbox
        size="small"
        checked={done}
        onChange={() => onToggle(task)}
        slotProps={{ input: { 'aria-label': `${done ? 'Rouvrir' : 'Terminer'} ${task.title}` } }}
      />
      <Box
        role="button"
        tabIndex={0}
        aria-label={`Ouvrir ${task.title}`}
        onClick={() => onOpen(task)}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return
          event.preventDefault()
          onOpen(task)
        }}
        sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, cursor: 'pointer', py: 1, minWidth: 0 }}
      >
        <Typography
          variant="body2"
          sx={{
            minWidth: 0,
            textDecoration: done ? 'line-through' : 'none',
            color: done ? 'text.disabled' : 'text.primary',
          }}
        >
          {task.title}
        </Typography>
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
      </Box>
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <UrgencyBadge urgency={task.urgency} />
      </Box>
      <Typography
        variant="caption"
        color={late ? 'error' : 'text.secondary'}
        sx={{ display: { xs: 'none', md: 'block' } }}
      >
        {task.dueDate ? (late ? `prévue le ${shortDay(task.dueDate)}` : shortDay(task.dueDate)) : ''}
      </Typography>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
        {done && onFocus ? (
          <StarIcon data-testid="task-star" titleAccess="Étoile gagnée" sx={{ color: STAR_COLOR, fontSize: 22 }} />
        ) : onFocus ? (
          <IconButton size="small" aria-label={`Se concentrer sur ${task.title}`} title="Focus" onClick={() => onFocus(task)}>
            <CenterFocusStrongIcon fontSize="small" />
          </IconButton>
        ) : null}
      </Box>
    </Box>
  )
}

const Columns = (): ReactNode => (
  <Box
    aria-hidden
    sx={{
      display: { xs: 'none', md: 'grid' },
      gridTemplateColumns: GRID,
      columnGap: 1,
      px: 1,
      py: 0.5,
      borderBottom: 1,
      borderColor: 'divider',
      color: 'text.secondary',
      fontSize: '0.75rem',
      fontWeight: 600,
    }}
  >
    <span style={{ width: 38 }} />
    <span>Tâche</span>
    <span>Urgence</span>
    <span>Échéance</span>
    <span />
  </Box>
)

/**
 * The tasks of a view in the full width (MAG-644): a checkbox, the title, then the Urgence and Échéance
 * columns, under the heading of the group they belong to (« En retard », « Aujourd'hui »).
 */
export const TaskList = ({ groups, today, label, highlightId = null, selectedId = null, onToggle, onOpen, onFocus }: TaskListProps) => {
  const listed = new Set(groups.flatMap((group) => group.tasks.flatMap((task) => [task['@id'], task.id])))

  return (
    <Box>
      <Columns />
      {groups.map((group, index) => (
        <Box key={group.label ?? index} sx={{ mb: 2 }}>
          {group.label && (
            <Typography variant="subtitle2" component="h2" sx={{ px: 1, pt: 1.5, pb: 0.5 }}>
              {group.label} · {group.tasks.length}
            </Typography>
          )}
          <Box component="ul" aria-label={group.label ?? label} sx={{ listStyle: 'none', m: 0, p: 0 }}>
            {group.tasks.map((task) => (
              <Row
                key={task.id}
                task={task}
                today={today}
                indented={!!task.parent && listed.has(task.parent)}
                highlighted={task.id === highlightId}
                selected={task.id === selectedId}
                onToggle={onToggle}
                onOpen={onOpen}
                onFocus={onFocus}
              />
            ))}
          </Box>
        </Box>
      ))}
    </Box>
  )
}
