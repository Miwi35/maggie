import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { AgentSettings } from './AgentSettings'
import { GoogleCalendarSettings } from './GoogleCalendarSettings'

export const settingsResources = (
  <CustomRoutes>
    <Route path="/settings/agent" element={<AgentSettings />} />
    <Route path="/settings/google" element={<GoogleCalendarSettings />} />
  </CustomRoutes>
)
