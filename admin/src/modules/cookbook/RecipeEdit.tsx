import {
  Edit,
  SimpleForm,
  TextInput,
  NumberInput,
  ArrayInput,
  SimpleFormIterator,
  SelectInput,
  required,
} from 'react-admin'
import { CiqualFoodAutocomplete } from './CiqualFoodAutocomplete'
import { TagsInput } from './TagsInput'

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

const positive = (value?: number | null) =>
  typeof value === 'number' && value > 0 ? undefined : 'La quantité doit être supérieure à 0'

// Pessimistic: the form only shows the new quantity once the API has kept it, and
// a refusal stays on screen instead of silently putting the old value back.
export const RecipeEdit = () => (
  <Edit mutationMode="pessimistic">
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <NumberInput source="servings" label="Portions" />
      <TagsInput source="tags" label="Tags (séparés par des virgules)" />
      <TextInput source="notes" label="Notes" multiline rows={3} fullWidth />
      <ArrayInput source="ingredients" label="Ingrédients">
        <SimpleFormIterator inline>
          <CiqualFoodAutocomplete source="ciqualAlimCode" />
          <NumberInput source="quantity" label="Quantité" validate={[required(), positive]} sx={{ maxWidth: 120 }} />
          <SelectInput
            source="unit"
            label="Unité"
            choices={unitChoices}
            defaultValue="g"
            validate={required()}
            sx={{ minWidth: 120 }}
          />
        </SimpleFormIterator>
      </ArrayInput>
    </SimpleForm>
  </Edit>
)
