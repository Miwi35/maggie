import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const AgentSettings = lazyPage(() => import('./AgentSettings'), 'AgentSettings')
const GoogleCalendarSettings = lazyPage(() => import('./GoogleCalendarSettings'), 'GoogleCalendarSettings')
const TaskOrderSettings = lazyPage(() => import('./TaskOrderSettings'), 'TaskOrderSettings')
const MealSettings = lazyPage(() => import('./MealSettings'), 'MealSettings')
const TaskSettings = lazyPage(() => import('./TaskSettings'), 'TaskSettings')
const UserPreferenceSettings = lazyPage(() => import('./UserPreferenceSettings'), 'UserPreferenceSettings')

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
