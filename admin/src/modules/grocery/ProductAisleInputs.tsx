import { useEffect, useState } from 'react'
import { useGetList, useInput, useRecordContext } from 'react-admin'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import { FormSection } from '../../components/form/FormSection'
import { fetchAisles, withAisleIn } from './aislesApi'
import type { Aisle, ProductAisle } from './aislesApi'
import { NO_AISLE } from './groceryAisles'
import { bareId } from './recurring'

const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface StoreRecord {
  id: string
  name: string
}

const AisleSelects = () => {
  const { data: stores = [] } = useGetList<StoreRecord>('stores', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })
  const { field } = useInput<ProductAisle[] | undefined>({ source: 'aisles' })
  const current: ProductAisle[] = Array.isArray(field.value) ? field.value : []
  const [aislesByStore, setAislesByStore] = useState<Record<string, Aisle[]>>({})

  const storeIds = stores.map((s) => bareId(String(s.id))).join(',')
  useEffect(() => {
    if (!storeIds) return
    let live = true
    Promise.all(
      storeIds.split(',').map(async (id) => [id, await fetchAisles(id).catch(() => [] as Aisle[])] as const),
    ).then((entries) => {
      if (live) setAislesByStore(Object.fromEntries(entries))
    })
    return () => {
      live = false
    }
  }, [storeIds])

  // A store with no aisle offers nothing to choose.
  const withAisles = stores.filter((s) => (aislesByStore[bareId(String(s.id))] ?? []).length > 0)
  if (withAisles.length === 0) return null

  return (
    <>
      <FormSection title="Rayons" />
      <Box sx={rowSx}>
        {withAisles.map((store) => {
          const storeId = bareId(String(store.id))
          const chosen = current.find((a) => bareId(a.store) === storeId)
          return (
            <TextField
              key={storeId}
              select
              size="small"
              label={store.name}
              value={chosen ? bareId(chosen.aisle) : ''}
              onChange={(e) => field.onChange(withAisleIn(current, storeId, e.target.value || null))}
              sx={halfSx}
            >
              <MenuItem value="">{NO_AISLE}</MenuItem>
              {aislesByStore[storeId].map((aisle) => (
                <MenuItem key={aisle.id} value={aisle.id}>
                  {aisle.name}
                </MenuItem>
              ))}
            </TextField>
          )
        })}
      </Box>
    </>
  )
}

/**
 * The product's aisle in each store that has aisles (MAG-493). Only once the
 * product exists: the API sets aisles on an update, not on a creation.
 */
export const ProductAisleInputs = () => {
  const record = useRecordContext()
  return record?.id ? <AisleSelects /> : null
}
