import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { CalendarView } from './CalendarView'

export const calendarResources = (
  <>
    <CustomRoutes>
      <Route path="/calendar" element={<CalendarView />} />
    </CustomRoutes>
    <ResourceGuesser name="agendas" />
    <ResourceGuesser name="events" />
  </>
)
