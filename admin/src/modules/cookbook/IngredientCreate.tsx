import { useCallback } from 'react'
import {
  Create,
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
  useGetOne,
} from 'react-admin'
import { useWatch, useFormContext } from 'react-hook-form'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

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
]

const CiqualAutoFill = () => {
  const ciqualFood = useWatch({ name: 'ciqualFood' })
  const { setValue } = useFormContext()

  const { data } = useGetOne(
    'ciqual_foods',
    { id: ciqualFood },
    { enabled: !!ciqualFood },
  )

  const handleAutoFill = useCallback(() => {
    if (!data?.nutrients) return
    const nutrients = data.nutrients as Array<{
      nutrient: { constCode: string }
      value: number | null
    }>
    for (const fn of nutrients) {
      const code = fn.nutrient?.constCode
      if (code === '328') setValue('kcalPer100g', fn.value, { shouldDirty: true })
      if (code === '25000') setValue('proteinPer100g', fn.value, { shouldDirty: true })
      if (code === '31000') setValue('carbsPer100g', fn.value, { shouldDirty: true })
      if (code === '40000') setValue('fatPer100g', fn.value, { shouldDirty: true })
    }
  }, [data, setValue])

  if (!ciqualFood || !data) return null

  return (
    <Box sx={{ mb: 2 }}>
      <Typography
        variant="body2"
        color="primary"
        sx={{ cursor: 'pointer', textDecoration: 'underline' }}
        onClick={handleAutoFill}
      >
        Remplir les macros depuis Ciqual
      </Typography>
    </Box>
  )
}

export const IngredientCreate = () => (
  <Create>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <SelectInput
        source="category"
        label="Catégorie"
        choices={categoryChoices}
        validate={required()}
      />
      <SelectInput source="defaultUnit" label="Unité par défaut" choices={unitChoices} />
      <ReferenceInput source="ciqualFood" reference="ciqual_foods">
        <AutocompleteInput
          label="Aliment Ciqual"
          optionText="alimNameFr"
          filterToQuery={(q: string) => ({ alimNameFr: q })}
          fullWidth
        />
      </ReferenceInput>
      <CiqualAutoFill />
      <Box sx={{ display: 'flex', gap: 2 }}>
        <NumberInput source="kcalPer100g" label="kcal/100g" />
        <NumberInput source="proteinPer100g" label="Protéines/100g" />
        <NumberInput source="carbsPer100g" label="Glucides/100g" />
        <NumberInput source="fatPer100g" label="Lipides/100g" />
      </Box>
    </SimpleForm>
  </Create>
)
