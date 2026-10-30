import { ResourceGuesser } from '@api-platform/admin'
import { lazyPage } from '../../lazyPage'

const WorkoutList = lazyPage(() => import('./WorkoutList'), 'WorkoutList')

export const healthResources = <ResourceGuesser name="workouts" list={WorkoutList} />
