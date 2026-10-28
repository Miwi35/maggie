import {
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'

const INGREDIENT_TOPICS = ['/api/ingredients/{id}']

const INGREDIENT_LIVE: LiveConfig = {
  topics: INGREDIENT_TOPICS,
  required: ['name', 'category'],
}

const IngredientDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="category" label="Catégorie" />
      <TextField source="defaultUnit" label="Unité" />
      <NumberField source="kcalPer100g" label="kcal" />
      <NumberField source="proteinPer100g" label="Protéines (g)" />
      <NumberField source="carbsPer100g" label="Glucides (g)" />
      <NumberField source="fatPer100g" label="Lipides (g)" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const IngredientList = () => (
  <LiveList
    live={INGREDIENT_LIVE}
    empty={
      <ListEmpty
        title="Aucun ingrédient pour l'instant"
        action="Ajouter un ingrédient"
      />
    }
  >
    <IngredientDatagrid />
  </LiveList>
)
