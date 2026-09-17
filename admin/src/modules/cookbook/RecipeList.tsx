import {
  List,
  Datagrid,
  TextField,
  NumberField,
  DateField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import Chip from '@mui/material/Chip'
import Box from '@mui/material/Box'
import { useMercure } from '../../hooks/useMercure'

const RECIPE_TOPICS = ['/api/recipes/{id}']

const RecipeDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(RECIPE_TOPICS, () => { refetch() })

  return (
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
  )
}

export const RecipeList = () => (
  <List
    empty={
      <ListEmpty
        title="Aucune recette pour l'instant"
        description="Enregistrez vos recettes pour les retrouver et les planifier dans les repas de la semaine."
        action="Ajouter une recette"
      />
    }
  >
    <RecipeDatagrid />
  </List>
)
