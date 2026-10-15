import { useMemo, useState } from 'react'
import { Datagrid, FunctionField, useGetList, useRecordContext } from 'react-admin'
import type { RaRecord } from 'react-admin'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ShoppingBasketIcon from '@mui/icons-material/ShoppingBasket'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { errandMatches, errandQuery, formatEndedAt, purchasedQuantity } from './errands'
import type { ErrandFilter, PurchasedItem } from './errands'

const ERRAND_TOPICS = ['/api/errands/{id}']

/** The lines bought, under the row: the admin reads a past errand in place. */
export const ErrandItems = () => {
  const record = useRecordContext()
  const items = (record?.items as PurchasedItem[] | undefined) ?? []
  if (items.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucun article.
      </Typography>
    )
  }

  return (
    <Table size="small" aria-label="Articles achetés">
      <TableBody>
        {items.map((item) => (
          <TableRow key={item.id}>
            <TableCell>{item.label}</TableCell>
            <TableCell align="right" sx={{ color: 'text.secondary' }}>
              {purchasedQuantity(item)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

const ErrandsEmpty = ({ filtered }: { filtered: boolean }) => (
  <Box sx={{ textAlign: 'center', py: 6 }}>
    <ShoppingBasketIcon color="disabled" sx={{ fontSize: 48 }} />
    <Typography variant="h6">
      {filtered ? 'Aucune course pour ce filtre' : 'Aucune course passée pour l’instant'}
    </Typography>
    <Typography variant="body2" color="text.secondary">
      Terminer une course depuis la liste l’archive ici, avec ses articles cochés.
    </Typography>
  </Box>
)

const ErrandFilters = ({
  value,
  onChange,
}: {
  value: ErrandFilter
  onChange: (value: ErrandFilter) => void
}) => {
  const { data: stores = [] } = useGetList('stores', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })

  return (
    <Stack direction="row" spacing={2} sx={{ px: 2, pt: 2 }}>
      <TextField
        select
        size="small"
        label="Magasin"
        value={value.store ?? ''}
        onChange={(event) => onChange({ ...value, store: event.target.value || undefined })}
        sx={{ minWidth: 200 }}
      >
        <MenuItem value="">Tous les magasins</MenuItem>
        {stores.map((store) => (
          <MenuItem key={store.id} value={String(store.id)}>
            {store.name as string}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        type="month"
        size="small"
        label="Mois"
        value={value.month ?? ''}
        onChange={(event) => onChange({ ...value, month: event.target.value || undefined })}
        slotProps={{ inputLabel: { shrink: true } }}
      />
    </Stack>
  )
}

export const ErrandList = () => {
  const [filter, setFilter] = useState<ErrandFilter>({})
  const query = useMemo(() => errandQuery(filter), [filter])
  const live = useMemo<LiveConfig>(
    () => ({
      topics: ERRAND_TOPICS,
      required: ['endedAt', 'itemCount'],
      references: { storeId: { field: 'store', collection: '/api/stores' } },
      accept: errandMatches(filter),
    }),
    [filter],
  )
  const filtered = Object.keys(query).length > 0

  return (
    <LiveList
      live={live}
      filter={query}
      sort={{ field: 'endedAt', order: 'DESC' }}
      actions={false}
      title="Courses passées"
      empty={false}
    >
      <ErrandFilters value={filter} onChange={setFilter} />
      <Datagrid
        rowClick="expand"
        expand={<ErrandItems />}
        bulkActionButtons={false}
        empty={<ErrandsEmpty filtered={filtered} />}
      >
        <FunctionField
          label="Date"
          sortBy="endedAt"
          render={(record: RaRecord) => formatEndedAt(record.endedAt as string)}
        />
        <FunctionField
          label="Magasin"
          sortable={false}
          render={(record: RaRecord) => (record.storeName as string | null) ?? 'Sans magasin'}
        />
        <FunctionField
          label="Articles"
          textAlign="right"
          sortable={false}
          render={(record: RaRecord) => (
            <Chip size="small" icon={<ShoppingBasketIcon />} label={record.itemCount as number} />
          )}
        />
      </Datagrid>
    </LiveList>
  )
}
