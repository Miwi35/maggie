import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const CalendarView = lazyPage(() => import('./CalendarView'), 'CalendarView')
const TaskBoard = lazyPage(() => import('./TaskBoard'), 'TaskBoard')

export const calendarResources = (
  <>
    <CustomRoutes>
      <Route path="/calendar" element={<CalendarView />} />
      <Route path="/tasks/upcoming" element={<TaskBoard />} />
      <Route path="/tasks/someday" element={<TaskBoard />} />
      <Route path="/tasks/done" element={<TaskBoard />} />
      <Route path="/tasks/routines" element={<TaskBoard />} />
    </CustomRoutes>
    <ResourceGuesser name="agendas" />
    <ResourceGuesser name="events" />
    <ResourceGuesser name="tasks" list={TaskBoard} />
  </>
)
