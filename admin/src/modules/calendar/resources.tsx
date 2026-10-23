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
    </CustomRoutes>
    <ResourceGuesser name="agendas" />
    <ResourceGuesser name="events" />
    <ResourceGuesser name="tasks" list={TaskBoard} />
  </>
)
