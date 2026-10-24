import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes, useCreatePath } from 'react-admin'
import { Navigate, Route, useParams } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const CalendarView = lazyPage(() => import('../calendar/CalendarView'), 'CalendarView')
const MealsWeekView = lazyPage(() => import('./MealsWeekView'), 'MealsWeekView')
const IngredientCreate = lazyPage(() => import('./IngredientCreate'), 'IngredientCreate')
const IngredientEdit = lazyPage(() => import('./IngredientEdit'), 'IngredientEdit')
const IngredientList = lazyPage(() => import('./IngredientList'), 'IngredientList')
const RecipeCreate = lazyPage(() => import('./RecipeCreate'), 'RecipeCreate')
const RecipeList = lazyPage(() => import('./RecipeList'), 'RecipeList')
const RecipeShow = lazyPage(() => import('./RecipeShow'), 'RecipeShow')

// react-admin serves `:id/*` as the edit route: a recipe has no edit form, its sheet is the page.
// The target is absolute: a relative one stacks on the splat and never settles (React error #185).
export const RecipeEditRedirect = () => {
  const { id } = useParams()
  const createPath = useCreatePath()

  return <Navigate to={createPath({ resource: 'recipes', type: 'show', id })} replace />
}

export const cookbookResources = (
  <>
    <CustomRoutes>
      <Route path="/meals" element={<MealsWeekView />} />
      <Route path="/meals/calendar" element={<CalendarView moduleKey="cookbook" />} />
    </CustomRoutes>
    <ResourceGuesser
      name="ingredients"
      list={IngredientList}
      create={IngredientCreate}
      edit={IngredientEdit}
    />
    <ResourceGuesser name="recipes" list={RecipeList} create={RecipeCreate} show={RecipeShow} edit={RecipeEditRedirect} />
  </>
)
