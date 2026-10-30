import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'
import { HEALTH_CONFIGURATION_PATH, HEALTH_SUIVI_PATH } from '../../components/layout/navigation'

const SuiviPage = lazyPage(() => import('./SuiviPage'), 'SuiviPage')
const ConfigurationPage = lazyPage(() => import('./ConfigurationPage'), 'ConfigurationPage')
const WorkoutList = lazyPage(() => import('./WorkoutList'), 'WorkoutList')

export const healthResources = (
  <>
    <CustomRoutes>
      <Route path={HEALTH_SUIVI_PATH} element={<SuiviPage />} />
      <Route path={HEALTH_CONFIGURATION_PATH} element={<ConfigurationPage />} />
    </CustomRoutes>
    <ResourceGuesser name="workouts" list={WorkoutList} />
  </>
)
