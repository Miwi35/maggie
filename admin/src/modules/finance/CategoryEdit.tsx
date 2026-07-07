import {
  Edit,
  SimpleForm,
  TextInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import { OBLIGATION_CHOICES } from './obligationFlags'

export const CategoryEdit = () => (
  <Edit>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <SelectInput
        source="obligation"
        label="Obligation"
        choices={OBLIGATION_CHOICES}
        validate={required()}
      />
      <ReferenceInput source="parent" reference="categories">
        <AutocompleteInput label="Catégorie parente" optionText="name" />
      </ReferenceInput>
      <TextInput source="color" label="Couleur (hex)" />
      <TextInput source="icon" label="Icône" />
    </SimpleForm>
  </Edit>
)
