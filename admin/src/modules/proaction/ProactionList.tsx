import {
  List,
  Datagrid,
  DateField,
  FunctionField,
  TextInput,
  SelectInput,
  ShowButton,
} from 'react-admin'
import Chip from '@mui/material/Chip'

const statusChoices = [
  { id: 'pending', name: 'En attente' },
  { id: 'running', name: 'En cours' },
  { id: 'success', name: 'Succès' },
  { id: 'failed', name: 'Échoué' },
]

const statusColors: Record<string, 'default' | 'info' | 'success' | 'error' | 'warning'> = {
  pending: 'default',
  running: 'info',
  success: 'success',
  failed: 'error',
}

const proactionFilters = [
  <TextInput key="prompt" source="prompt" label="Prompt" alwaysOn />,
  <SelectInput key="status" source="status" label="Statut" choices={statusChoices} alwaysOn />,
]

export const ProactionList = () => (
  <List
    sort={{ field: 'scheduledAt', order: 'DESC' }}
    filters={proactionFilters}
  >
    <Datagrid rowClick="show" bulkActionButtons={false}>
      <DateField source="scheduledAt" label="Planifié le" showTime />
      <FunctionField
        label="Prompt"
        render={(record: { prompt?: string }) =>
          record.prompt && record.prompt.length > 80
            ? record.prompt.substring(0, 80) + '...'
            : record.prompt
        }
      />
      <FunctionField
        label="Statut"
        render={(record: { status?: string }) => (
          <Chip
            label={record.status}
            color={statusColors[record.status || 'default'] || 'default'}
            size="small"
          />
        )}
      />
      <DateField source="completedAt" label="Terminé le" showTime emptyText="-" />
      <ShowButton />
    </Datagrid>
  </List>
)
