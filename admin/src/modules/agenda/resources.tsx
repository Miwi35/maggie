import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { AgendaView } from './AgendaView'

export const agendaResources = (
  <>
    <CustomRoutes>
      <Route path="/agenda" element={<AgendaView />} />
    </CustomRoutes>
    <ResourceGuesser name="calendars" />
    <ResourceGuesser name="events" />
  </>
)
