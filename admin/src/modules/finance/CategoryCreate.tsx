import {
  Create,
  SimpleForm,
  TextInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import { OBLIGATION_CHOICES } from './obligationFlags'

export const CategoryCreate = () => (
  <Create>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <SelectInput
        source="obligation"
        label="Obligation"
        choices={OBLIGATION_CHOICES}
        defaultValue="optional"
        validate={required()}
      />
      <ReferenceInput source="parent" reference="categories">
        <AutocompleteInput label="Catégorie parente" optionText="name" />
      </ReferenceInput>
      <TextInput source="color" label="Couleur (hex)" />
      <TextInput source="icon" label="Icône" />
    </SimpleForm>
  </Create>
)
