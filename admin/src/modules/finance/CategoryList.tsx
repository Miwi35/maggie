import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import AddIcon from '@mui/icons-material/Add'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import {
  Datagrid,
  FunctionField,
  EditButton,
  DeleteButton,
  CreateButton,
  TopToolbar,
  ListContextProvider,
  useCreatePath,
  useListContext,
} from 'react-admin'
import { Link } from 'react-router-dom'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { CategorizationRuleList } from './CategorizationRuleList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { CategoryGlyph } from './CategoryGlyph'
import { StandardCategoriesButton } from './StandardCategoriesButton'
import { RuleSuggestions } from './RuleSuggestions'
import { CATEGORY_MAX_DEPTH, categoryTree } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import type { RaRecord } from 'react-admin'

const CATEGORY_TOPICS = ['/api/categories/{id}']

const CATEGORY_LIVE: LiveConfig = {
  topics: CATEGORY_TOPICS,
  required: ['name'],
  references: {
    parentId: { field: 'parent', collection: '/api/categories' },
  },
}

/** The starting set sits next to Create; the recurring operations, moved here from Revue (MAG-634), are one click away. */
const CategoryActions = () => (
  <TopToolbar>
    <Button component={Link} to="/finance/recurring-operations">
      Opérations récurrentes
    </Button>
    <StandardCategoriesButton variant="text" />
    <CreateButton />
  </TopToolbar>
)

/** Create under a category: the form opens with the parent already chosen. */
const AddUnderButton = ({ category }: { category: CategoryNode }) => {
  const createPath = useCreatePath()
  const to = {
    pathname: createPath({ resource: 'categories', type: 'create' }),
    search: `?source=${encodeURIComponent(JSON.stringify({ parent: category.id }))}`,
  }

  return (
    <Button
      component={Link}
      to={to}
      size="small"
      startIcon={<AddIcon />}
      aria-label={`Ajouter une sous-catégorie à ${category.name}`}
      onClick={(event: React.MouseEvent) => event.stopPropagation()}
    >
      Sous-catégorie
    </Button>
  )
}

/** The rows as a three-level tree: each category under its parent, indented by its level. */
const CategoryDatagrid = () => {
  const list = useListContext()
  const rows = useMemo(() => categoryTree((list.data ?? []) as unknown as CategoryNode[]), [list.data])
  const depths = useMemo(() => new Map(rows.map((row) => [row.category.id, row.depth])), [rows])
  const value = useMemo(
    () => ({ ...list, data: rows.map((row) => row.category) }) as unknown as typeof list,
    [list, rows],
  )

  return (
    <ListContextProvider value={value}>
      <Datagrid rowClick="edit">
        <FunctionField
          label="Nom"
          sortable={false}
          render={(record: RaRecord) => {
            const depth = depths.get(record.id as string) ?? 1

            return (
              <Box
                component="span"
                data-depth={depth}
                title={(record.path as string | undefined) ?? undefined}
                sx={{ pl: (depth - 1) * 3, fontWeight: depth === 1 ? 600 : 400 }}
              >
                {depth > 1 && <span aria-hidden="true">› </span>}
                <CategoryGlyph color={record.color as string | null} icon={record.icon as string | null} />
                <span>{record.name as string}</span>
              </Box>
            )
          }}
        />
        <FunctionField
          label="Rente"
          sortable={false}
          render={(record: RaRecord) => (record.passiveIncome ? 'Rente' : '')}
        />
        <FunctionField
          label=""
          sortable={false}
          render={(record: RaRecord) =>
            (depths.get(record.id as string) ?? 1) < CATEGORY_MAX_DEPTH ? (
              <AddUnderButton category={record as unknown as CategoryNode} />
            ) : null
          }
        />
        <EditButton />
        <DeleteButton />
      </Datagrid>
    </ListContextProvider>
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
          <LiveList
            live={CATEGORY_LIVE}
            perPage={200}
            pagination={false}
            // The tree has no pager to change the page size or the sort back:
            // what the old paginated list left in the store must not apply (MAG-503).
            storeKey={false}
            disableSyncWithLocation
            sort={{ field: 'name', order: 'ASC' }}
            actions={<CategoryActions />}
            empty={
              <ListEmpty
                title="Aucune catégorie pour l'instant"
                action="Créer une catégorie"
                secondaryAction={<StandardCategoriesButton />}
              />
            }
          >
            <CategoryDatagrid />
          </LiveList>
        )}
      </Box>

      <Box hidden={tab !== 1}>{tab === 1 && <CategorizationRuleList />}</Box>

      <Box hidden={tab !== 2}>{tab === 2 && <RuleSuggestions />}</Box>
    </>
  )
}
