import {
  ListContextProvider,
  useGetList,
  useListContext,
  Datagrid,
  TextField,
  NumberField,
  FunctionField,
  ReferenceField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import MuiTextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { packagingLabel } from './packaging'
import { StockStateChip } from './StockStateChip'
import { NO_AISLE } from './groceryAisles'
import { bareId } from './recurring'
import { useStoreAisles } from './useStoreAisles'
import type { Aisle } from './aislesApi'

const PRODUCT_TOPICS = ['/api/products/{id}']

const PRODUCT_LIVE: LiveConfig = {
  topics: PRODUCT_TOPICS,
  required: ['name', 'category', 'stockState'],
  references: {
    preferredStoreId: { field: 'preferredStore', collection: '/api/stores' },
    fallbackStoreId: { field: 'fallbackStore', collection: '/api/stores' },
  },
}

const ProductDatagrid = () => {
  return (
    <Datagrid rowClick="edit" sx={{ '& .MuiTable-root': { tableLayout: 'fixed' } }}>
      <TextField source="name" label="Nom" />
      <TextField source="category" label="Catégorie" />
      <TextField source="defaultUnit" label="Unité" />
      <FunctionField
        label="Conditionnement"
        render={(record: { packagingUnit?: string | null; packagingSize?: number | null; packagingSizeUnit?: string | null }) =>
          packagingLabel(record.packagingUnit, record.packagingSize, record.packagingSizeUnit) ?? ''
        }
      />
      <FunctionField
        label="Stock"
        render={(record: { stockState?: string | null }) => <StockStateChip state={record.stockState} />}
      />
      <NumberField source="shelfLifeDays" label="Conservation (jours)" />
      <ReferenceField source="preferredStore" reference="stores" label="Magasin préféré" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

const SORTS = [
  { field: 'name', label: 'Nom' },
  { field: 'aisle', label: 'Rayon' },
  { field: 'stockState', label: 'Stock' },
]

const filterSx = { minWidth: 200 }

interface StoreRecord {
  id: string
  name: string
}

/** « Sans rayon »: the API's `aisle=none`, which needs a store. */
const NONE = 'none'

const ProductFilters = ({ aisles }: { aisles: Aisle[] }) => {
  const { filterValues, setFilters, sort, setSort } = useListContext()
  const { data: stores = [] } = useGetList<StoreRecord>('stores', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })
  const store = typeof filterValues.store === 'string' ? filterValues.store : ''
  const aisle = typeof filterValues.aisle === 'string' ? filterValues.aisle : ''

  const withoutKeys = (keys: string[]) =>
    Object.fromEntries(Object.entries(filterValues).filter(([key]) => !keys.includes(key)))

  const chooseStore = (next: string) => {
    // The aisle belongs to a store; the way through the store is the order that matters once one is chosen.
    setFilters(next ? { ...withoutKeys(['store', 'aisle']), store: next } : withoutKeys(['store', 'aisle']), null)
    if (next && sort.field === 'name') setSort({ field: 'aisle', order: 'ASC' })
    if (!next && sort.field === 'aisle') setSort({ field: 'name', order: 'ASC' })
  }

  const chooseAisle = (next: string) =>
    setFilters(next ? { ...withoutKeys(['aisle']), aisle: next } : withoutKeys(['aisle']), null)

  return (
    <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center', py: 1 }}>
      <MuiTextField select size="small" label="Magasin" value={store} onChange={(e) => chooseStore(e.target.value)} sx={filterSx}>
        <MenuItem value="">Tous les magasins</MenuItem>
        {stores.map((s) => (
          <MenuItem key={s.id} value={bareId(String(s.id))}>
            {s.name}
          </MenuItem>
        ))}
      </MuiTextField>
      {store && (
        <MuiTextField select size="small" label="Rayon" value={aisle} onChange={(e) => chooseAisle(e.target.value)} sx={filterSx}>
          <MenuItem value="">Tous les rayons</MenuItem>
          {aisles.map((a) => (
            <MenuItem key={a.id} value={a.id}>
              {a.name}
            </MenuItem>
          ))}
          <MenuItem value={NONE}>{NO_AISLE}</MenuItem>
        </MuiTextField>
      )}
      <MuiTextField
        select
        size="small"
        label="Trier par"
        value={sort.field}
        onChange={(e) => setSort({ field: e.target.value, order: 'ASC' })}
        sx={filterSx}
      >
        {SORTS.map(({ field, label }) => (
          <MenuItem key={field} value={field} disabled={field === 'aisle' && !store}>
            {label}
          </MenuItem>
        ))}
      </MuiTextField>
    </Box>
  )
}

interface ShelvedRow {
  id: string
  aisles?: { store: string; aisle: string }[]
}

/** The page cut by aisle, in the order of the way through the store; « Sans rayon » last. */
const sectionsOf = (rows: ShelvedRow[], storeId: string, aisles: Aisle[]) => {
  const known = new Map(aisles.map((a) => [a.id, a]))
  const byAisle = new Map<string, ShelvedRow[]>()
  for (const row of rows) {
    const entry = row.aisles?.find((a) => bareId(a.store) === storeId)
    const id = entry && known.has(bareId(entry.aisle)) ? bareId(entry.aisle) : ''
    byAisle.set(id, [...(byAisle.get(id) ?? []), row])
  }
  return [...aisles.map((a) => ({ title: a.name, rows: byAisle.get(a.id) ?? [] })), { title: NO_AISLE, rows: byAisle.get('') ?? [] }].filter(
    (section) => section.rows.length > 0,
  )
}

const ProductBody = ({ aisles, ready }: { aisles: Aisle[]; ready: boolean }) => {
  const list = useListContext()
  const store = typeof list.filterValues.store === 'string' ? list.filterValues.store : ''
  const rows = list.data as ShelvedRow[] | undefined

  if (!store || !ready || !rows) return <ProductDatagrid />
  if (rows.length === 0) return <Typography sx={{ py: 2 }}>Aucun produit ne correspond.</Typography>

  return (
    <>
      {sectionsOf(rows, store, aisles).map((section) => (
        <Box key={section.title} component="section" sx={{ mb: 3 }}>
          <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600, py: 1 }}>
            {section.title} · {section.rows.length}
          </Typography>
          <ListContextProvider value={{ ...list, data: section.rows, total: section.rows.length } as typeof list}>
            <ProductDatagrid />
          </ListContextProvider>
        </Box>
      ))}
    </>
  )
}

const ProductListContent = () => {
  const { filterValues } = useListContext()
  const store = typeof filterValues.store === 'string' && filterValues.store ? filterValues.store : null
  const { aisles, ready } = useStoreAisles(store)

  return (
    <>
      <ProductFilters aisles={aisles} />
      <ProductBody aisles={aisles} ready={ready} />
    </>
  )
}

export const ProductList = () => (
  <LiveList
    live={PRODUCT_LIVE}
    sort={{ field: 'name', order: 'ASC' }}
    empty={
      <ListEmpty
        title="Aucun produit pour l'instant"
        description="Les produits sont ce que vous ajoutez à votre liste de courses : enregistrez ceux que vous rachetez souvent."
        action="Ajouter un produit"
      />
    }
  >
    <ProductListContent />
  </LiveList>
)
