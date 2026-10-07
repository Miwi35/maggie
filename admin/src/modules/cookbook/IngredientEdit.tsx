import { Edit, SimpleForm, TextInput, NumberInput, SelectInput, required } from 'react-admin'
import Box from '@mui/material/Box'
import { CiqualFoodAutocomplete, CiqualAutoFill } from './CiqualFoodAutocomplete'

const categoryChoices = [
  { id: 'produce', name: 'Fruits & Légumes' },
  { id: 'dairy', name: 'Produits laitiers' },
  { id: 'meat', name: 'Viandes' },
  { id: 'fish', name: 'Poissons' },
  { id: 'grain', name: 'Céréales' },
  { id: 'spice', name: 'Épices' },
  { id: 'condiment', name: 'Condiments' },
  { id: 'frozen', name: 'Surgelés' },
  { id: 'beverage', name: 'Boissons' },
  { id: 'other', name: 'Autre' },
]

const unitChoices = [
  { id: 'g', name: 'g' },
  { id: 'kg', name: 'kg' },
  { id: 'ml', name: 'ml' },
  { id: 'l', name: 'l' },
  { id: 'cl', name: 'cl' },
  { id: 'piece', name: 'pièce' },
  { id: 'bunch', name: 'botte' },
  { id: 'can', name: 'boîte' },
  { id: 'bottle', name: 'bouteille' },
  { id: 'pack', name: 'paquet' },
  { id: 'sachet', name: 'sachet' },
  { id: 'jar', name: 'bocal' },
]

export const IngredientEdit = () => (
  <Edit>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <SelectInput
        source="category"
        label="Catégorie"
        choices={categoryChoices}
        validate={required()}
      />
      <SelectInput source="defaultUnit" label="Unité par défaut" choices={unitChoices} />
      <CiqualFoodAutocomplete />
      <CiqualAutoFill />
      <Box sx={{ display: 'flex', gap: 2 }}>
        <NumberInput source="kcalPer100g" label="kcal/100g" />
        <NumberInput source="proteinPer100g" label="Protéines/100g" />
        <NumberInput source="carbsPer100g" label="Glucides/100g" />
        <NumberInput source="fatPer100g" label="Lipides/100g" />
      </Box>
    </SimpleForm>
  </Edit>
)
