import { useState } from 'react'
import { useNavigate, useLocation, useParams } from 'react-router-dom'
import { useCreatePath, useGetList, useListContext } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import InputAdornment from '@mui/material/InputAdornment'
import MenuItem from '@mui/material/MenuItem'
import MuiTextField from '@mui/material/TextField'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import SearchIcon from '@mui/icons-material/Search'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { packagingLabel } from './packaging'
import { ProductPanel } from './ProductPanel'
import type { PanelTarget } from './ProductPanel'
import { StockStateChip } from './StockStateChip'
import { NO_AISLE } from './groceryAisles'
import {
  PASTILLES,
  aisleIn,
  categoryName,
  groupsByAisle,
  groupsByCategory,
  matchesPastille,
  matchesSearch,
} from './productPage'
import type { PastilleId, ProductGroup, ProductRow } from './productPage'
import { bareId } from './recurring'
import { useStoreAisles } from './useStoreAisles'
import type { Aisle } from './aislesApi'

const PRODUCT_LIVE: LiveConfig = {
  topics: ['/api/products/{id}'],
  required: ['name', 'category', 'stockState'],
  references: {
    preferredStoreId: { field: 'preferredStore', collection: '/api/stores' },
    fallbackStoreId: { field: 'fallbackStore', collection: '/api/stores' },
  },
}

/** The API serves at most 100 per page: the pastilles and the search narrow that page here. */
const PER_PAGE = 100

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

interface ToolbarProps {
  stores: StoreRecord[]
  aisles: Aisle[]
  search: string
  onSearch: (search: string) => void
  pastille: PastilleId
  onPastille: (pastille: PastilleId) => void
  onCreate: () => void
}

const ProductToolbar = ({ stores, aisles, search, onSearch, pastille, onPastille, onCreate }: ToolbarProps) => {
  const { filterValues, setFilters, sort, setSort } = useListContext()
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
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, py: 1 }}>
      <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
        <MuiTextField
          type="search"
          size="small"
          placeholder="Rechercher un produit"
          value={search}
          onChange={(e) => onSearch(e.target.value)}
          slotProps={{
            htmlInput: { 'aria-label': 'Rechercher un produit' },
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
          sx={{ flex: 1, maxWidth: 420 }}
        />
        <Button variant="contained" onClick={onCreate} sx={{ ml: 'auto' }}>
          + Produit
        </Button>
      </Box>

      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          {PASTILLES.map(({ id, label }) => (
            <Chip
              key={id}
              label={label}
              clickable
              aria-pressed={pastille === id}
              color={pastille === id ? 'primary' : 'default'}
              variant={pastille === id ? 'filled' : 'outlined'}
              onClick={() => onPastille(id)}
            />
          ))}
        </Box>

        <MuiTextField select size="small" label="Magasin" value={store} onChange={(e) => chooseStore(e.target.value)} sx={{ ...filterSx, ml: 'auto' }}>
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
    </Box>
  )
}

const muted = { color: 'text.secondary' }

interface TableProps {
  groups: ProductGroup[]
  storeId: string
  aisles: Aisle[]
  storeName: (iri?: string | null) => string
  onOpen: (id: string) => void
}

const ProductTable = ({ groups, storeId, aisles, storeName, onOpen }: TableProps) => (
  <Table size="small" sx={{ '& td, & th': { px: 1.5 } }}>
    <TableHead>
      <TableRow>
        <TableCell>Produit</TableCell>
        <TableCell>Rayon</TableCell>
        <TableCell>Magasin</TableCell>
        <TableCell>À défaut</TableCell>
        <TableCell>Conditionnement</TableCell>
        <TableCell>Stock</TableCell>
      </TableRow>
    </TableHead>
    {groups.map((group) => (
      <TableBody key={group.title}>
        <TableRow>
          <TableCell colSpan={6} sx={{ pt: 2.5, pb: 0.5, borderBottom: 0 }}>
            <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600 }}>
              {group.title} · {group.rows.length}
            </Typography>
          </TableCell>
        </TableRow>
        {group.rows.map((row) => (
          <TableRow key={row.id} hover tabIndex={0} sx={{ cursor: 'pointer' }} onClick={() => onOpen(row.id)} onKeyDown={(e) => e.key === 'Enter' && onOpen(row.id)}>
            <TableCell>
              <span data-testid="product-name">{row.name}</span>
            </TableCell>
            <TableCell sx={muted}>{storeId ? (aisleIn(row, storeId, aisles)?.name ?? '—') : categoryName(row.category)}</TableCell>
            <TableCell>{storeName(row.preferredStore)}</TableCell>
            <TableCell sx={muted}>{storeName(row.fallbackStore)}</TableCell>
            <TableCell sx={muted}>{packagingLabel(row.packagingUnit, row.packagingSize, row.packagingSizeUnit) ?? ''}</TableCell>
            <TableCell>
              <StockStateChip state={row.stockState} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    ))}
  </Table>
)

const ProductListContent = ({ onOpen, onCreate }: { onOpen: (id: string) => void; onCreate: () => void }) => {
  const list = useListContext()
  const store = typeof list.filterValues.store === 'string' ? list.filterValues.store : ''
  const { aisles, ready } = useStoreAisles(store || null)
  const { data: stores = [] } = useGetList<StoreRecord>('stores', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })
  const [search, setSearch] = useState('')
  const [pastille, setPastille] = useState<PastilleId>('all')

  const storeName = (iri?: string | null) => {
    if (!iri) return ''

    return stores.find((s) => s.id === iri || bareId(String(s.id)) === bareId(iri))?.name ?? ''
  }

  const month = new Date().getMonth() + 1
  const loaded = list.data as ProductRow[] | undefined
  const rows = loaded?.filter((row) => matchesPastille(row, pastille, month) && matchesSearch(row, search))

  let body = null
  if (rows && (!store || ready)) {
    body =
      rows.length === 0 ? (
        <Typography sx={{ py: 2 }}>Aucun produit ne correspond.</Typography>
      ) : (
        <ProductTable
          groups={store ? groupsByAisle(rows, store, aisles) : groupsByCategory(rows)}
          storeId={store}
          aisles={aisles}
          storeName={storeName}
          onOpen={onOpen}
        />
      )
  }

  return (
    <>
      <ProductToolbar
        stores={stores}
        aisles={aisles}
        search={search}
        onSearch={setSearch}
        pastille={pastille}
        onPastille={setPastille}
        onCreate={onCreate}
      />
      {body}
    </>
  )
}

/**
 * The Produits page of the mockup: pastilles, search and store above, the products by aisle, and the
 * fiche in a panel on the right. The same component serves `/products`, `/products/create` and
 * `/products/:id`, so opening a product leaves the list, its filters and its scroll where they are.
 */
export const ProductList = () => {
  const { id } = useParams()
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const createPath = useCreatePath()

  let target: PanelTarget = null
  if (pathname.endsWith('/create')) target = { kind: 'create' }
  else if (id) target = { kind: 'edit', id }

  const listPath = createPath({ resource: 'products', type: 'list' })

  return (
    <>
      <LiveList
        live={PRODUCT_LIVE}
        sort={{ field: 'name', order: 'ASC' }}
        perPage={PER_PAGE}
        actions={false}
        pagination={false}
        component="div"
        empty={
          <ListEmpty
            title="Aucun produit pour l'instant"
            action="Ajouter un produit"
          />
        }
      >
        <ProductListContent
          onOpen={(productId) => navigate(createPath({ resource: 'products', type: 'edit', id: productId }))}
          onCreate={() => navigate(createPath({ resource: 'products', type: 'create' }))}
        />
      </LiveList>
      <ProductPanel target={target} onClose={() => navigate(listPath)} />
    </>
  )
}
