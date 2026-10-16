import { Create } from 'react-admin'
import { ContactForm } from './ContactForm'

export const ContactCreate = () => (
  <Create title="Nouveau contact" redirect="edit">
    <ContactForm />
  </Create>
)
