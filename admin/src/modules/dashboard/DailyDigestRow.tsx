import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { EventListWidget } from './EventListWidget'
import { TaskListWidget } from './TaskListWidget'
import type { DashboardEvent } from './EventListWidget'
import type { DashboardTask } from './TaskListWidget'

interface DailyDigestRowProps {
  todayEvents: DashboardEvent[]
  tomorrowEvents: DashboardEvent[]
  todayTasks: DashboardTask[]
  tomorrowTasks: DashboardTask[]
  loading: boolean
  onToggleDone: (taskId: string, done: boolean) => void
}

export const DailyDigestRow = ({
  todayEvents,
  tomorrowEvents,
  todayTasks,
  tomorrowTasks,
  loading,
  onToggleDone,
}: DailyDigestRowProps) => {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
        gap: 3,
      }}
    >
      {/* Today */}
      <Box>
        <Typography variant="h6" sx={{ mb: 1.5 }}>
          Aujourd&apos;hui
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <EventListWidget events={todayEvents} loading={loading} />
          <TaskListWidget tasks={todayTasks} loading={loading} onToggleDone={onToggleDone} />
        </Box>
      </Box>

      {/* Tomorrow */}
      <Box>
        <Typography variant="h6" sx={{ mb: 1.5 }}>
          Demain
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <EventListWidget events={tomorrowEvents} loading={loading} />
          <TaskListWidget tasks={tomorrowTasks} loading={loading} onToggleDone={onToggleDone} />
        </Box>
      </Box>
    </Box>
  )
}
