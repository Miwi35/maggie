import { useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useLocation, useParams } from 'react-router-dom'
import { useCreatePath, useGetList, useListContext } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import InputAdornment from '@mui/material/InputAdornment'
import MuiTextField from '@mui/material/TextField'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SearchIcon from '@mui/icons-material/Search'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { packagingLabel } from './packaging'
import { ProductPanel } from './ProductPanel'
import type { PanelTarget } from './ProductPanel'
import { NO_AISLE } from './groceryAisles'
import {
  PASTILLES,
  SORT_WORDS,
  groupsByAisle,
  matchesPastille,
  aisleIn,
  matchesSearch,
  sortCycle,
  stockLook,
  storesLabel,
  usualAisle,
} from './productPage'
import type { SortField } from './productPage'
import type { PastilleId, ProductGroup, ProductRow } from './productPage'
import { bareId } from './recurring'
import { useAislesByStore, useStoreAisles } from './useStoreAisles'
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

interface StoreRecord {
  id: string
  name: string
}

/** « Sans rayon »: the API's `aisle=none`, which needs a store. */
const NONE = 'none'

interface PillProps {
  selected: boolean
  height?: number
  toggle?: boolean
  onClick: () => void
  children: ReactNode
}

const Pill = ({ selected, height = 36, toggle = true, onClick, children }: PillProps) => (
  <Button
    variant="text"
    aria-pressed={toggle ? selected : undefined}
    onClick={onClick}
    sx={(theme) => ({
      height,
      minWidth: 0,
      px: 1.75,
      borderRadius: 999,
      fontSize: 14,
      textTransform: 'none',
      fontWeight: selected ? 500 : 400,
      color: selected ? theme.palette.module.courses : theme.palette.text.secondary,
      bgcolor: selected ? alpha(theme.palette.module.courses, 0.16) : alpha(theme.palette.text.primary, 0.05),
    })}
  >
    {children}
  </Button>
)

const Legend = ({ children }: { children: ReactNode }) => (
  <Typography variant="overline" component="span" sx={(theme) => ({ color: theme.palette.veilleuse.caption })}>
    {children}
  </Typography>
)

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

  const nextSort = () => {
    const cycle = sortCycle(Boolean(store))
    const at = cycle.indexOf(sort.field as SortField)
    setSort({ field: cycle[(at + 1) % cycle.length], order: 'ASC' })
  }

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
            <Pill key={id} selected={pastille === id} onClick={() => onPastille(id)}>
              {label}
            </Pill>
          ))}
        </Box>

        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center', ml: 'auto' }}>
          <Legend>Magasin</Legend>
          <Pill selected={!store} onClick={() => chooseStore('')}>
            Tous
          </Pill>
          {stores.map((s) => {
            const id = bareId(String(s.id))

            return (
              <Pill key={id} selected={store === id} onClick={() => chooseStore(id)}>
                {s.name}
              </Pill>
            )
          })}
        </Box>

        <Pill selected={false} toggle={false} onClick={nextSort}>
          {`Trier : ${SORT_WORDS[sort.field as SortField] ?? SORT_WORDS.name}`}
          <ExpandMoreIcon fontSize="small" sx={{ ml: 0.25 }} />
        </Pill>
      </Box>

      {store && (
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
          <Legend>Rayon</Legend>
          <Pill height={32} selected={!aisle} onClick={() => chooseAisle('')}>
            Tous les rayons
          </Pill>
          {aisles.map((a) => (
            <Pill key={a.id} height={32} selected={aisle === a.id} onClick={() => chooseAisle(a.id)}>
              {a.name}
            </Pill>
          ))}
          <Pill height={32} selected={aisle === NONE} onClick={() => chooseAisle(NONE)}>
            {NO_AISLE}
          </Pill>
        </Box>
      )}
    </Box>
  )
}

const muted = { color: 'text.secondary' }

interface TableProps {
  groups: ProductGroup[]
  showHead: boolean
  rayonHead: string
  aisleOf: (row: ProductRow) => string
  selectedId: string
  storeName: (iri?: string | null) => string
  onOpen: (id: string) => void
}

const stockTones = {
  neutral: (theme: Theme) => ({ color: theme.palette.text.secondary, bgcolor: alpha(theme.palette.text.primary, 0.06) }),
  courses: (theme: Theme) => ({ color: theme.palette.module.courses, bgcolor: alpha(theme.palette.module.courses, 0.16) }),
  warning: (theme: Theme) => ({ color: theme.palette.warning.main, bgcolor: alpha(theme.palette.warning.main, 0.14) }),
}

const StockPill = ({ state }: { state?: string | null }) => {
  const { label, tone } = stockLook(state)

  return (
    <Box
      component="span"
      sx={(theme) => ({
        display: 'inline-flex',
        alignItems: 'center',
        height: 24,
        px: 1.25,
        borderRadius: 999,
        fontSize: 12,
        fontWeight: 500,
        ...stockTones[tone](theme),
      })}
    >
      {label}
    </Box>
  )
}

const ProductTable = ({ groups, showHead, rayonHead, aisleOf, selectedId, storeName, onOpen }: TableProps) => (
  <Box sx={{ borderRadius: 5, bgcolor: 'background.paper', px: 1.5, py: 1 }}>
    <Table size="small" sx={{ '& td, & th': { px: 1.5 } }}>
      <TableHead>
        <TableRow>
          <TableCell>Produit</TableCell>
          <TableCell>{rayonHead}</TableCell>
          <TableCell>Magasin · à défaut</TableCell>
          <TableCell>Conditionnement</TableCell>
          <TableCell>Stock</TableCell>
        </TableRow>
      </TableHead>
      {groups.map((group) => (
        <TableBody key={group.title}>
          {showHead && (
            <TableRow>
              <TableCell colSpan={5} sx={{ pt: 2.5, pb: 0.5, borderBottom: 0 }}>
                <Typography
                  variant="overline"
                  component="h3"
                  sx={(theme) => ({
                    display: 'inline',
                    fontSize: 12,
                    letterSpacing: '0.08em',
                    color: group.title === NO_AISLE ? theme.palette.warning.main : theme.palette.module.courses,
                  })}
                >
                  {group.title}
                </Typography>
                <Box component="span" sx={(theme) => ({ ml: 1, fontSize: 12, color: theme.palette.veilleuse.caption })}>
                  {group.rows.length} {group.rows.length === 1 ? 'produit' : 'produits'}
                </Box>
              </TableCell>
            </TableRow>
          )}
          {group.rows.map((row) => {
            const selected = bareId(row.id) === selectedId
            const aisleName = aisleOf(row)

            return (
              <TableRow
                key={row.id}
                hover
                tabIndex={0}
                aria-selected={selected}
                sx={(theme) => ({ cursor: 'pointer', ...(selected && { bgcolor: alpha(theme.palette.module.courses, 0.08) }) })}
                onClick={() => onOpen(row.id)}
                onKeyDown={(e) => e.key === 'Enter' && onOpen(row.id)}
              >
                <TableCell>
                  <span data-testid="product-name">{row.name}</span>
                </TableCell>
                <TableCell sx={(theme) => ({ color: aisleName ? theme.palette.text.primary : theme.palette.veilleuse.caption })}>
                  {aisleName || NO_AISLE}
                </TableCell>
                <TableCell sx={muted}>{storesLabel(row, storeName)}</TableCell>
                <TableCell sx={muted}>{packagingLabel(row.packagingUnit, row.packagingSize, row.packagingSizeUnit) ?? ''}</TableCell>
                <TableCell>
                  <StockPill state={row.stockState} />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      ))}
    </Table>
  </Box>
)

const ProductListContent = ({
  selectedId,
  onOpen,
  onCreate,
}: {
  selectedId: string
  onOpen: (id: string) => void
  onCreate: () => void
}) => {
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
  const usualStores = store ? [] : [...new Set((loaded ?? []).flatMap((row) => (row.preferredStore ? [bareId(row.preferredStore)] : [])))]
  const aislesByStore = useAislesByStore(usualStores)

  const storeLabel = stores.find((s) => bareId(String(s.id)) === store)?.name ?? ''

  let body = null
  if (rows && (!store || ready)) {
    body =
      rows.length === 0 ? (
        <Typography sx={{ py: 2 }}>Aucun produit ne correspond.</Typography>
      ) : (
        <ProductTable
          groups={store ? groupsByAisle(rows, store, aisles) : [{ title: '', rows }]}
          showHead={Boolean(store)}
          rayonHead={store ? `Rayon · ${storeLabel}` : 'Rayon (habituel)'}
          aisleOf={(row) => (store ? (aisleIn(row, store, aisles)?.name ?? '') : (usualAisle(row, aislesByStore)?.name ?? ''))}
          selectedId={selectedId}
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
          selectedId={id ? bareId(decodeURIComponent(id)) : ''}
          onOpen={(productId) => navigate(createPath({ resource: 'products', type: 'edit', id: productId }))}
          onCreate={() => navigate(createPath({ resource: 'products', type: 'create' }))}
        />
      </LiveList>
      <ProductPanel target={target} onClose={() => navigate(listPath)} />
    </>
  )
}
