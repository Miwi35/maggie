import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { AgentSettings } from './AgentSettings'
import { GoogleCalendarSettings } from './GoogleCalendarSettings'
import { TaskOrderSettings } from './TaskOrderSettings'
import { MealSettings } from './MealSettings'
import { TaskSettings } from './TaskSettings'
import { UserPreferenceSettings } from './UserPreferenceSettings'

export const settingsResources = (
  <CustomRoutes>
    <Route path="/settings/agent" element={<AgentSettings />} />
    <Route path="/settings/google" element={<GoogleCalendarSettings />} />
    <Route path="/settings/preferences" element={<UserPreferenceSettings />} />
    <Route path="/settings/tasks" element={<TaskSettings />} />
    <Route path="/settings/tasks/order" element={<TaskOrderSettings />} />
    <Route path="/settings/meals" element={<MealSettings />} />
  </CustomRoutes>
)
