import { Datagrid, TextField, EditButton, DeleteButton } from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'

const CONTACT_LIVE: LiveConfig = {
  topics: ['/api/contacts/{id}'],
  required: ['name'],
}

export const ContactList = () => (
  <LiveList
    live={CONTACT_LIVE}
    empty={
      <ListEmpty
        title="Aucun contact"
        action="Nouveau contact"
      />
    }
  >
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="notes" label="Notes" emptyText="—" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  </LiveList>
)
