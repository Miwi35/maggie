import {
  List,
  Datagrid,
  TextField,
  NumberField,
  DateField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import Chip from '@mui/material/Chip'
import Box from '@mui/material/Box'

export const RecipeList = () => (
  <List>
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <NumberField source="servings" label="Portions" />
      <FunctionField
        label="Tags"
        render={(record: { tags?: string[] }) =>
          record.tags?.length ? (
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
              {record.tags.map((tag: string) => (
                <Chip key={tag} label={tag} size="small" />
              ))}
            </Box>
          ) : null
        }
      />
      <FunctionField
        label="Ingrédients"
        render={(record: { ingredients?: unknown[] }) =>
          record.ingredients?.length
            ? `${record.ingredients.length} ingrédient${record.ingredients.length > 1 ? 's' : ''}`
            : '—'
        }
      />
      <DateField source="createdAt" label="Créé le" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  </List>
)
