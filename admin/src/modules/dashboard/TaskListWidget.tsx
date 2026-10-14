import { useCallback, useMemo } from 'react'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import EventBusyIcon from '@mui/icons-material/EventBusy'
import { parseDay } from '../../dates'
import { criticalityColor } from '../../design/tokens'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'
import { UrgencyBadge } from '../calendar/UrgencyBadge'

export interface DashboardTask {
  id: string
  title: string
  criticality: string
  /** The due day, `YYYY-MM-DD` (MAG-404). */
  dueDate?: string
  completedAt?: string
  /** Whole days late, from the API (MAG-405); null or absent when the task is not late. */
  overdueDays?: number | null
  /** A subtask is shown under its task, indented. */
  isSubtask?: boolean
  /** Computed by the API (MAG-406): `low`, `normal`, `high` or `critical`. */
  urgency?: string | null
  /** The API's order score (MAG-406): the highest first. */
  orderScore?: number | null
}

/**
 * The API's order (MAG-406): the highest score first, then the closest due day,
 * undated last. Done tasks go to the bottom.
 */
export const byOrderScore = (a: DashboardTask, b: DashboardTask): number => {
  if (!!a.completedAt !== !!b.completedAt) return a.completedAt ? 1 : -1
  const score = (b.orderScore ?? 0) - (a.orderScore ?? 0)
  if (score !== 0) return score
  if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate)
  if (a.dueDate) return -1
  if (b.dueDate) return 1
  return 0
}

interface TaskListWidgetProps {
  tasks: DashboardTask[]
  loading: boolean
  onToggleDone: (taskId: string, done: boolean) => void
  /** Keep the order given (the API's « Aujourd'hui »: by score, subtasks under their task). */
  ordered?: boolean
  /** The list could not be read: say it instead of « Aucune tâche ». */
  error?: boolean
}

const shortDay = (day: string): string =>
  parseDay(day).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })

export const TaskListWidget = ({ tasks, loading, onToggleDone, ordered = false, error = false }: TaskListWidgetProps) => {
  const { addedIds, removingItems } = useItemTransitions(
    tasks,
    useCallback((t: DashboardTask) => t.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((t) => t.id)), [removingItems])
  const displayTasks = useMemo(() => {
    const currentIds = new Set(tasks.map((t) => t.id))
    return [...tasks, ...removingItems.filter((t) => !currentIds.has(t.id))]
  }, [tasks, removingItems])

  const sorted = ordered ? displayTasks : [...displayTasks].sort(byOrderScore)

  if (loading) {
    return (
      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" gutterBottom>
            Tâches
          </Typography>
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} height={32} />
          ))}
        </CardContent>
      </Card>
    )
  }

  return (
    <Card variant="outlined">
      <CardContent sx={{ '&:last-child': { pb: 2 } }}>
        <Typography variant="subtitle2" gutterBottom>
          Tâches
          {tasks.length > 0 && (
            <Typography
              component="span"
              variant="caption"
              color="text.secondary"
              sx={{ ml: 1 }}
            >
              ({tasks.length})
            </Typography>
          )}
        </Typography>
        {error ? (
          <Typography variant="body2" color="error" role="alert">
            Tâches indisponibles
          </Typography>
        ) : tasks.length === 0 && removingItems.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Aucune tâche
          </Typography>
        ) : (
          <List dense disablePadding>
            {sorted.map((task) => {
              const isDone = task.completedAt != null
              const late = !isDone && task.overdueDays != null && task.overdueDays > 0
              return (
                <ListItem
                  key={task.id}
                  disableGutters
                  sx={{ py: 0.25, borderRadius: 1, pl: task.isSubtask ? 4 : 0, ...transitionSx(task.id, addedIds, removingIds) }}
                >
                  <Checkbox
                    size="small"
                    checked={isDone}
                    onChange={() => onToggleDone(task.id, !isDone)}
                    sx={{ p: 0.25, mr: 0.5 }}
                  />
                  <Chip
                    label={task.criticality}
                    size="small"
                    sx={{
                      bgcolor: criticalityColor(task.criticality),
                      color: '#fff',
                      fontSize: '0.65rem',
                      height: 20,
                      mr: 1,
                    }}
                  />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <ListItemText
                      secondaryTypographyProps={{ component: 'span', sx: { display: 'flex', gap: 1, flexWrap: 'wrap' } }}
                      primary={
                        <Typography
                          variant="body2"
                          noWrap
                          sx={{
                            textDecoration: isDone ? 'line-through' : 'none',
                            color: isDone ? 'text.disabled' : 'text.primary',
                          }}
                        >
                          {task.title}
                        </Typography>
                      }
                      secondary={
                        late || task.urgency ? (
                          <>
                            <UrgencyBadge urgency={task.urgency} />
                            {late && (
                              <Typography
                                component="span"
                                variant="caption"
                                color="error"
                                sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                              >
                                <EventBusyIcon aria-hidden fontSize="inherit" />
                                En retard de {task.overdueDays} j
                              </Typography>
                            )}
                          </>
                        ) : undefined
                      }
                    />
                  </Box>
                  {task.dueDate && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ ml: 1, flexShrink: 0 }}
                    >
                      {late ? `prévue le ${shortDay(task.dueDate)}` : shortDay(task.dueDate)}
                    </Typography>
                  )}
                </ListItem>
              )
            })}
          </List>
        )}
      </CardContent>
    </Card>
  )
}
