import { SimpleForm, TextInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'

const formSx = { maxWidth: 680 }

export const ContactForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="La personne"
      description="Quelqu'un à qui vous prêtez ou qui vous prête : ses virements lui sont rattachés d'après les libellés de « Ses comptes »."
    />
    <TextInput source="name" label="Nom" validate={required()} fullWidth />
    <TextInput source="notes" label="Notes" multiline minRows={2} fullWidth />
  </SimpleForm>
)
