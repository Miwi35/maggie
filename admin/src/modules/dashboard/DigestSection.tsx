import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { EventListWidget } from './EventListWidget'
import { TaskListWidget } from './TaskListWidget'
import type { DashboardEvent } from './EventListWidget'
import type { DashboardTask } from './TaskListWidget'

interface DigestSectionProps {
  title: string
  icon: ReactNode
  events: DashboardEvent[]
  tasks: DashboardTask[]
  loading: boolean
  onToggleDone: (taskId: string, done: boolean) => void
}

export const DigestSection = ({
  title,
  icon,
  events,
  tasks,
  loading,
  onToggleDone,
}: DigestSectionProps) => {
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        {icon}
        <Typography variant="h6">{title}</Typography>
      </Box>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
          gap: 2,
        }}
      >
        <EventListWidget events={events} loading={loading} />
        <TaskListWidget tasks={tasks} loading={loading} onToggleDone={onToggleDone} />
      </Box>
    </Box>
  )
}
