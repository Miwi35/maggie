import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const CalendarView = lazyPage(() => import('../calendar/CalendarView'), 'CalendarView')
const MealsWeekView = lazyPage(() => import('./MealsWeekView'), 'MealsWeekView')
const IngredientCreate = lazyPage(() => import('./IngredientCreate'), 'IngredientCreate')
const IngredientEdit = lazyPage(() => import('./IngredientEdit'), 'IngredientEdit')
const IngredientList = lazyPage(() => import('./IngredientList'), 'IngredientList')
const RecipeCreate = lazyPage(() => import('./RecipeCreate'), 'RecipeCreate')
const RecipeList = lazyPage(() => import('./RecipeList'), 'RecipeList')
const RecipeShow = lazyPage(() => import('./RecipeShow'), 'RecipeShow')

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
    <ResourceGuesser name="recipes" list={RecipeList} create={RecipeCreate} show={RecipeShow} />
  </>
)
