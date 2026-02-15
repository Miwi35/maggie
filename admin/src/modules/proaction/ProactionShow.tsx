import {
  Show,
  SimpleShowLayout,
  TextField,
  DateField,
  FunctionField,
} from 'react-admin'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'

const statusColors: Record<string, 'default' | 'info' | 'success' | 'error'> = {
  pending: 'default',
  running: 'info',
  success: 'success',
  failed: 'error',
}

export const ProactionShow = () => (
  <Show>
    <SimpleShowLayout>
      <TextField source="id" label="ID" />
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
      <DateField source="scheduledAt" label="Planifié le" showTime />
      <DateField source="createdAt" label="Créé le" showTime />
      <DateField source="completedAt" label="Terminé le" showTime emptyText="-" />
      <FunctionField
        label="Prompt"
        render={(record: { prompt?: string }) => (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
            {record.prompt}
          </Typography>
        )}
      />
      <FunctionField
        label="Réponse"
        render={(record: { response?: string | null }) =>
          record.response ? (
            <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
              {record.response}
            </Typography>
          ) : (
            <Typography variant="body2" color="text.secondary">-</Typography>
          )
        }
      />
      <FunctionField
        label="Erreur"
        render={(record: { error?: string | null }) =>
          record.error ? (
            <Typography variant="body2" color="error" sx={{ whiteSpace: 'pre-wrap' }}>
              {record.error}
            </Typography>
          ) : null
        }
      />
    </SimpleShowLayout>
  </Show>
)
