import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'
import { HEALTH_SUIVI_PATH } from '../../components/layout/navigation'

const SuiviPage = lazyPage(() => import('./SuiviPage'), 'SuiviPage')
const WorkoutList = lazyPage(() => import('./WorkoutList'), 'WorkoutList')

export const healthResources = (
  <>
    <CustomRoutes>
      <Route path={HEALTH_SUIVI_PATH} element={<SuiviPage />} />
    </CustomRoutes>
    <ResourceGuesser name="workouts" list={WorkoutList} />
  </>
)
