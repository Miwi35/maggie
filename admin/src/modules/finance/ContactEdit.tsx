import { Edit, useRecordContext } from 'react-admin'
import { ContactCounterpartiesSection } from './ContactCounterpartiesSection'
import { ContactForm } from './ContactForm'

/** The labels need the record's id and name: they wait for it under the form. */
const Counterparties = () => {
  const record = useRecordContext<{ id: string; name: string }>()

  return record ? <ContactCounterpartiesSection contactId={String(record.id)} contactName={record.name} /> : null
}

/** The « fiche contact »: who they are, then the labels their transfers carry. */
export const ContactEdit = () => (
  <Edit title="Modifier le contact">
    <ContactForm />
    <Counterparties />
  </Edit>
)
