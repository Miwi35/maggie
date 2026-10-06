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
import { criticalityColor } from '../../design/tokens'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'

export interface DashboardTask {
  id: string
  title: string
  criticality: string
  dueDate?: string
  completedAt?: string
}

const CRITICALITY_ORDER: Record<string, number> = {
  critical: 0,
  high: 1,
  medium: 2,
  low: 3,
}

interface TaskListWidgetProps {
  tasks: DashboardTask[]
  loading: boolean
  onToggleDone: (taskId: string, done: boolean) => void
}

export const TaskListWidget = ({ tasks, loading, onToggleDone }: TaskListWidgetProps) => {
  const { addedIds, removingItems } = useItemTransitions(
    tasks,
    useCallback((t: DashboardTask) => t.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((t) => t.id)), [removingItems])
  const displayTasks = useMemo(() => {
    const currentIds = new Set(tasks.map((t) => t.id))
    return [...tasks, ...removingItems.filter((t) => !currentIds.has(t.id))]
  }, [tasks, removingItems])

  const sorted = [...displayTasks].sort((a, b) => {
    // Done tasks at bottom
    if (!!a.completedAt !== !!b.completedAt) return a.completedAt ? 1 : -1
    // By criticality
    const ca = CRITICALITY_ORDER[a.criticality] ?? 4
    const cb = CRITICALITY_ORDER[b.criticality] ?? 4
    if (ca !== cb) return ca - cb
    // By due date
    if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate)
    if (a.dueDate) return -1
    if (b.dueDate) return 1
    return 0
  })

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
        {tasks.length === 0 && removingItems.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Aucune tâche
          </Typography>
        ) : (
          <List dense disablePadding>
            {sorted.map((task) => {
              const isDone = task.completedAt != null
              return (
                <ListItem key={task.id} disableGutters sx={{ py: 0.25, borderRadius: 1, ...transitionSx(task.id, addedIds, removingIds) }}>
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
                    />
                  </Box>
                  {task.dueDate && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ ml: 1, flexShrink: 0 }}
                    >
                      {new Date(task.dueDate).toLocaleDateString('fr-FR', {
                        day: 'numeric',
                        month: 'short',
                      })}
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
