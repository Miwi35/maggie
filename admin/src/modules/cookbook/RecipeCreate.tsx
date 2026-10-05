import {
  Create,
  SimpleForm,
  TextInput,
  NumberInput,
  ArrayInput,
  SimpleFormIterator,
  SelectInput,
  required,
  useNotify,
  useRedirect,
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

export const RecipeCreate = () => {
  const notify = useNotify()
  const redirect = useRedirect()

  return (
    <Create
      mutationOptions={{
        onSuccess: () => {
          notify('Recette enregistrée', { type: 'success' })
          redirect('list', 'recipes')
        },
      }}
    >
      <SimpleForm>
        <TextInput source="name" label="Nom" validate={required()} fullWidth />
        <NumberInput source="servings" label="Portions" defaultValue={4} />
        <TagsInput source="tags" label="Tags (séparés par des virgules)" />
        <TextInput source="notes" label="Notes" multiline rows={3} fullWidth />
        <ArrayInput source="ingredients" label="Ingrédients">
          <SimpleFormIterator inline>
            <CiqualFoodAutocomplete source="ciqualAlimCode" />
            <NumberInput source="quantity" label="Quantité" sx={{ maxWidth: 120 }} />
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
    </Create>
  )
}
