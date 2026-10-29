import { SimpleForm, TextInput, NumberInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { MarkdownInput } from '../../components/markdown/MarkdownInput'

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
    <MarkdownInput source="description" label="Description" minRows={3} />
    <NumberInput
      source="visitOrder"
      label="Ordre de visite"
      defaultValue={0}
    />
  </SimpleForm>
)
