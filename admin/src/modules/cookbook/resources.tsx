import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { MealsWeekView } from './MealsWeekView'
import { GroceryListView } from './GroceryListView'
import { IngredientCreate } from './IngredientCreate'
import { IngredientEdit } from './IngredientEdit'
import { IngredientList } from './IngredientList'
import { RecipeCreate } from './RecipeCreate'
import { RecipeEdit } from './RecipeEdit'
import { RecipeList } from './RecipeList'

export const cookbookResources = (
  <>
    <CustomRoutes>
      <Route path="/meals" element={<MealsWeekView />} />
      <Route path="/grocery" element={<GroceryListView />} />
    </CustomRoutes>
    <ResourceGuesser name="products" />
    <ResourceGuesser
      name="ingredients"
      list={IngredientList}
      create={IngredientCreate}
      edit={IngredientEdit}
    />
    <ResourceGuesser name="recipes" list={RecipeList} create={RecipeCreate} edit={RecipeEdit} />
    <ResourceGuesser name="recurring_grocery_items" />
  </>
)
