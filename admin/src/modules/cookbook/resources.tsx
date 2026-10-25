import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes, useCreatePath } from 'react-admin'
import { Navigate, Route, useParams } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'
import { COOKING_ACTIVITY_PATH } from './cooking/cookingSession'

const MealsPage = lazyPage(() => import('./MealsPage'), 'MealsPage')
const IngredientCreate = lazyPage(() => import('./IngredientCreate'), 'IngredientCreate')
const IngredientEdit = lazyPage(() => import('./IngredientEdit'), 'IngredientEdit')
const IngredientList = lazyPage(() => import('./IngredientList'), 'IngredientList')
const RecipeCreate = lazyPage(() => import('./RecipeCreate'), 'RecipeCreate')
const RecipeList = lazyPage(() => import('./RecipeList'), 'RecipeList')
const CookingActivityPage = lazyPage(() => import('./cooking/CookingActivityPage'), 'CookingActivityPage')
const RecipeShow = lazyPage(() => import('./RecipeShow'), 'RecipeShow')

// react-admin serves `:id/*` as the edit route: a recipe has no edit form, its sheet is the page.
// The target is absolute: a relative one stacks on the splat and never settles (React error #185).
export const RecipeEditRedirect = () => {
  const { id } = useParams()
  const createPath = useCreatePath()

  return <Navigate to={createPath({ resource: 'recipes', type: 'show', id })} replace />
}

// The planning of the meals was a second page for the same function: its old address lands on the Repas page.
export const MealsCalendarRedirect = () => <Navigate to="/meals" replace />

export const cookbookResources = (
  <>
    <CustomRoutes>
      <Route path="/meals" element={<MealsPage />} />
      <Route path="/meals/calendar" element={<MealsCalendarRedirect />} />
    </CustomRoutes>
    {/* The Cuisine activity (MAG-580): full screen, out of the menus. */}
    <CustomRoutes noLayout>
      <Route path={COOKING_ACTIVITY_PATH} element={<CookingActivityPage />} />
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
