import {
  List,
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
} from 'react-admin'

export const IngredientList = () => (
  <List>
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
  </List>
)
