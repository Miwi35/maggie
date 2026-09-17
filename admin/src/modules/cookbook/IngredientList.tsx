import {
  List,
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { useMercure } from '../../hooks/useMercure'

const INGREDIENT_TOPICS = ['/api/ingredients/{id}']

const IngredientDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(INGREDIENT_TOPICS, () => { refetch() })

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
  <List
    empty={
      <ListEmpty
        title="Aucun ingrédient pour l'instant"
        description="Les ingrédients alimentent vos recettes et leurs valeurs nutritionnelles."
        action="Ajouter un ingrédient"
      />
    }
  >
    <IngredientDatagrid />
  </List>
)
