import {
  Create,
  SimpleForm,
  TextInput,
  NumberInput,
  ArrayInput,
  SimpleFormIterator,
  ReferenceInput,
  AutocompleteInput,
  SelectInput,
  required,
} from 'react-admin'

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

export const RecipeCreate = () => (
  <Create>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <NumberInput source="servings" label="Portions" defaultValue={4} />
      <TextInput source="tags" label="Tags (séparés par des virgules)" fullWidth />
      <TextInput source="notes" label="Notes" multiline rows={3} fullWidth />
      <ArrayInput source="ingredients" label="Ingrédients">
        <SimpleFormIterator inline>
          <ReferenceInput source="ingredient" reference="ingredients">
            <AutocompleteInput
              label="Ingrédient"
              optionText="name"
              sx={{ minWidth: 200 }}
            />
          </ReferenceInput>
          <NumberInput source="quantity" label="Quantité" sx={{ maxWidth: 120 }} />
          <SelectInput source="unit" label="Unité" choices={unitChoices} sx={{ minWidth: 120 }} />
        </SimpleFormIterator>
      </ArrayInput>
    </SimpleForm>
  </Create>
)
