import { SimpleForm, TextInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { MarkdownInput } from '../../components/markdown/MarkdownInput'

const formSx = { maxWidth: 680 }

export const ContactForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection first title="La personne" />
    <TextInput source="name" label="Nom" validate={required()} fullWidth />
    <MarkdownInput source="notes" label="Notes" />
  </SimpleForm>
)
