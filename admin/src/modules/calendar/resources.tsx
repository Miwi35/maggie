import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { CalendarView } from './CalendarView'
import { GoogleCalendarSettings } from '../settings'

export const calendarResources = (
  <>
    <CustomRoutes>
      <Route path="/calendar" element={<CalendarView />} />
      <Route path="/settings/google-calendar" element={<GoogleCalendarSettings />} />
    </CustomRoutes>
    <ResourceGuesser name="agendas" />
    <ResourceGuesser name="events" />
    <ResourceGuesser name="tasks" />
  </>
)
