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
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { CategorizationRuleList } from './CategorizationRuleList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { OBLIGATION_LABELS } from './obligationFlags'
import type { RaRecord } from 'react-admin'

const CATEGORY_TOPICS = ['/api/categories/{id}']

const CategoryDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(CATEGORY_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <FunctionField
        label="Obligation"
        render={(record: RaRecord) => OBLIGATION_LABELS[record.obligation as string] ?? record.obligation}
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
      </Tabs>

      <Box hidden={tab !== 0}>
        {tab === 0 && (
          <List
            empty={
              <ListEmpty
                title="Aucune catégorie pour l'instant"
                description="Les catégories portent les budgets, les règles automatiques et la revue mensuelle : commencez par celles où va l'essentiel de votre argent."
                action="Créer une catégorie"
              />
            }
          >
            <CategoryDatagrid />
          </List>
        )}
      </Box>

      <Box hidden={tab !== 1}>
        {tab === 1 && <CategorizationRuleList />}
      </Box>
    </>
  )
}
