import { SimpleForm, TextInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'

const formSx = { maxWidth: 680 }

export const ContactForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection first title="La personne" />
    <TextInput source="name" label="Nom" validate={required()} fullWidth />
    <TextInput source="notes" label="Notes" multiline minRows={2} fullWidth />
  </SimpleForm>
)
