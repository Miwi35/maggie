import { Edit, useRecordContext } from 'react-admin'
import { ContactCounterpartiesSection } from './ContactCounterpartiesSection'
import { ContactForm } from './ContactForm'
import { ContactPeerDebtsSection } from './ContactPeerDebtsSection'

/** The labels and the créances need the record's id and name: they wait for it under the form. */
const Sections = () => {
  const record = useRecordContext<{ id: string; name: string }>()

  return record ? (
    <>
      <ContactPeerDebtsSection contactId={String(record.id)} />
      <ContactCounterpartiesSection contactId={String(record.id)} contactName={record.name} />
    </>
  ) : null
}

/** The « fiche contact »: who they are, what is still open with them, then the labels their transfers carry. */
export const ContactEdit = () => (
  <Edit title="Modifier le contact">
    <ContactForm />
    <Sections />
  </Edit>
)
