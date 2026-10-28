import { SimpleForm, TextInput, NumberInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'

const formSx = { maxWidth: 680 }

export const StoreForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection first title="Le magasin" />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
    />
    <TextInput
      source="description"
      label="Description"
      multiline
      rows={3}
      fullWidth
    />
    <NumberInput
      source="visitOrder"
      label="Ordre de visite"
      defaultValue={0}
    />
  </SimpleForm>
)
