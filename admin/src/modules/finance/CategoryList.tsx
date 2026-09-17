import { useState } from 'react'
import Box from '@mui/material/Box'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import {
  List,
  Datagrid,
  TextField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  CreateButton,
  TopToolbar,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { CategorizationRuleList } from './CategorizationRuleList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { StandardCategoriesButton } from './StandardCategoriesButton'
import { RuleSuggestions } from './RuleSuggestions'
import { OBLIGATION_LABELS } from './obligationFlags'
import type { RaRecord } from 'react-admin'

const CATEGORY_TOPICS = ['/api/categories/{id}']

/** The starting set sits next to Create: both are ways to fill this screen. */
const CategoryActions = () => (
  <TopToolbar>
    <StandardCategoriesButton variant="text" />
    <CreateButton />
  </TopToolbar>
)

const CategoryDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(CATEGORY_TOPICS, () => {
    refetch()
  })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <FunctionField
        label="Obligation"
        render={(record: RaRecord) =>
          OBLIGATION_LABELS[record.obligation as string] ?? record.obligation
        }
      />
      <ReferenceField source="parent" reference="categories" label="Catégorie parente" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <TextField source="color" label="Couleur" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

/**
 * Categories and the rules that file transactions into them live on the same
 * page: a rule only makes sense next to its category.
 */
export const CategoryList = () => {
  const [tab, setTab] = useState(0)

  return (
    <>
      <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ mb: 1 }}>
        <Tab label="Catégories" />
        <Tab label="Règles de catégorisation" />
        <Tab label="Suggestions" />
      </Tabs>

      <Box hidden={tab !== 0}>
        {tab === 0 && (
          <List
            actions={<CategoryActions />}
            empty={
              <ListEmpty
                title="Aucune catégorie pour l'instant"
                description="Les catégories portent les budgets, les règles automatiques et la revue mensuelle. Partez du jeu standard — logement, nourriture, abonnements, loisirs, placements, essence, imprévus — puis ajustez-le."
                action="Créer une catégorie"
                secondaryAction={<StandardCategoriesButton />}
              />
            }
          >
            <CategoryDatagrid />
          </List>
        )}
      </Box>

      <Box hidden={tab !== 1}>{tab === 1 && <CategorizationRuleList />}</Box>

      <Box hidden={tab !== 2}>{tab === 2 && <RuleSuggestions />}</Box>
    </>
  )
}
