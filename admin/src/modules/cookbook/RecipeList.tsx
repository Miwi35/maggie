import {
  Datagrid,
  TextField,
  NumberField,
  DateField,
  FunctionField,
  EditButton,
  ShowButton,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import Chip from '@mui/material/Chip'
import Box from '@mui/material/Box'
import { RecipeDeleteButton } from './RecipeDeleteButton'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'

const RECIPE_TOPICS = ['/api/recipes/{id}']

const RECIPE_LIVE: LiveConfig = {
  topics: RECIPE_TOPICS,
  required: ['name', 'servings'],
  fallback: () => ({ createdAt: new Date().toISOString() }),
}

const RecipeDatagrid = () => {
  return (
    <Datagrid rowClick="edit" bulkActionButtons={false}>
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
      <ShowButton />
      <EditButton />
      <RecipeDeleteButton />
    </Datagrid>
  )
}

export const RecipeList = () => (
  <LiveList
    live={RECIPE_LIVE}
    empty={
      <ListEmpty
        title="Aucune recette pour l'instant"
        description="Enregistrez vos recettes pour les retrouver et les planifier dans les repas de la semaine."
        action="Ajouter une recette"
      />
    }
  >
    <RecipeDatagrid />
  </LiveList>
)
