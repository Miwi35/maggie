import { useState } from 'react'
import { useWatch } from 'react-hook-form'
import {
  AutocompleteInput,
  ReferenceInput,
  useGetList,
  useInput,
  useNotify,
  useRecordContext,
} from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import { FormSection } from '../../components/form/FormSection'
import { AisleError, createAisle, withAisleIn } from './aislesApi'
import type { ProductAisle } from './aislesApi'
import { NO_AISLE } from './groceryAisles'
import { bareId } from './recurring'
import { useStoreAisles } from './useStoreAisles'

const halfSx = { flex: '1 1 240px' }
const columnSx = { display: 'flex', flexDirection: 'column', gap: 1, width: '100%' }
const NEW_AISLE = '__new__'

interface StoreRecord {
  id: string
  name: string
}

interface AisleControl {
  aisles: ProductAisle[]
  setAisles: (next: ProductAisle[]) => void
}

const useStores = () =>
  useGetList<StoreRecord>('stores', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  }).data ?? []

/** The product's aisle in one store: the store's aisles, « Sans rayon », and « Nouveau rayon… » that creates one in this store. */
const AisleChoice = ({
  storeId,
  label,
  control,
  onChosen,
}: {
  storeId: string
  label: string
  control: AisleControl
  onChosen?: () => void
}) => {
  const notify = useNotify()
  const { aisles, add } = useStoreAisles(storeId)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')

  const chosen = control.aisles.find((a) => bareId(a.store) === storeId)
  const value = chosen && aisles.some((a) => a.id === bareId(chosen.aisle)) ? bareId(chosen.aisle) : ''

  const choose = (aisleId: string | null) => {
    control.setAisles(withAisleIn(control.aisles, storeId, aisleId))
    onChosen?.()
  }

  const create = async () => {
    const typed = name.trim()
    if (!typed) return
    try {
      const created = await createAisle(storeId, typed)
      add(created)
      choose(created.id)
      setName('')
      setCreating(false)
    } catch (error) {
      notify(error instanceof AisleError ? error.message : `Le rayon « ${typed} » n'a pas été ajouté`, {
        type: 'error',
      })
    }
  }

  return (
    <>
      <TextField
        select
        size="small"
        label={label}
        value={creating ? NEW_AISLE : value}
        onChange={(e) => {
          if (e.target.value === NEW_AISLE) return setCreating(true)
          setCreating(false)
          choose(e.target.value || null)
        }}
        sx={halfSx}
      >
        <MenuItem value="">{NO_AISLE}</MenuItem>
        {aisles.map((aisle) => (
          <MenuItem key={aisle.id} value={aisle.id}>
            {aisle.name}
          </MenuItem>
        ))}
        <MenuItem value={NEW_AISLE}>Nouveau rayon…</MenuItem>
      </TextField>
      {creating && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            size="small"
            autoFocus
            label="Nom du nouveau rayon"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return
              e.preventDefault()
              void create()
            }}
            sx={halfSx}
          />
          <Button variant="contained" size="small" disabled={!name.trim()} onClick={() => void create()}>
            Créer le rayon
          </Button>
          <Button size="small" onClick={() => setCreating(false)}>
            Annuler
          </Button>
        </Box>
      )}
    </>
  )
}

/**
 * A store of the product and, right under it, the product's aisle there. The
 * aisle waits for a store, and changing the store empties it: an aisle belongs
 * to one store.
 */
const StoreWithAisle = ({
  source,
  label,
  aisleLabel,
  storeId,
  otherStoreId,
  control,
}: {
  source: 'preferredStore' | 'fallbackStore'
  label: string
  aisleLabel: string
  storeId: string | null
  otherStoreId: string | null
  control?: AisleControl
}) => (
  <Box sx={columnSx}>
    <ReferenceInput source={source} reference="stores">
      <AutocompleteInput
        label={label}
        optionText="name"
        sx={halfSx}
        onChange={(value: unknown) => {
          const next = typeof value === 'string' && value ? bareId(value) : null
          if (control && storeId && next !== storeId && storeId !== otherStoreId) control.setAisles(withAisleIn(control.aisles, storeId, null))
        }}
      />
    </ReferenceInput>
    {control && storeId && <AisleChoice key={storeId} storeId={storeId} label={aisleLabel} control={control} />}
  </Box>
)

/** The aisles in the stores other than the two above, kept or added one by one. */
const OtherStoreAisles = ({ excluded, control }: { excluded: (string | null)[]; control: AisleControl }) => {
  const stores = useStores()
  const [extra, setExtra] = useState<string[]>(() =>
    control.aisles.map((a) => bareId(a.store)).filter((id) => !excluded.includes(id)),
  )
  const [adding, setAdding] = useState(false)

  const shown = extra.filter((id) => !excluded.includes(id))
  const nameOf = (id: string) => stores.find((s) => bareId(String(s.id)) === id)?.name ?? id
  const candidates = stores.filter((s) => {
    const id = bareId(String(s.id))
    return !excluded.includes(id) && !shown.includes(id)
  })

  return (
    <Box sx={columnSx}>
      {shown.map((id) => (
        <AisleChoice key={id} storeId={id} label={nameOf(id)} control={control} />
      ))}
      {adding && (
        <TextField
          select
          size="small"
          label="Autre magasin"
          value=""
          onChange={(e) => {
            setExtra((prev) => [...prev, e.target.value])
            setAdding(false)
          }}
          sx={halfSx}
        >
          {candidates.map((s) => (
            <MenuItem key={s.id} value={bareId(String(s.id))}>
              {s.name}
            </MenuItem>
          ))}
        </TextField>
      )}
      {!adding && candidates.length > 0 && (
        <Box>
          <Button size="small" onClick={() => setAdding(true)}>
            + Rayon dans un autre magasin
          </Button>
        </Box>
      )}
    </Box>
  )
}

const StoreInputs = ({ control, preferred, fallback }: { control?: AisleControl; preferred: string | null; fallback: string | null }) => (
  <>
    <FormSection title="Où l'acheter" />
    <StoreWithAisle
      source="preferredStore"
      label="Magasin habituel"
      aisleLabel="Rayon du magasin habituel"
      storeId={preferred}
      otherStoreId={fallback}
      control={control}
    />
    <StoreWithAisle
      source="fallbackStore"
      label="À défaut"
      aisleLabel="Rayon du magasin à défaut"
      storeId={fallback}
      otherStoreId={preferred}
      control={control}
    />
    {control && <OtherStoreAisles excluded={[preferred, fallback]} control={control} />}
  </>
)

const iriToId = (value: unknown): string | null => (typeof value === 'string' && value ? bareId(value) : null)

const WithAisles = () => {
  const { field } = useInput<ProductAisle[] | undefined>({ source: 'aisles' })
  const preferred = iriToId(useWatch({ name: 'preferredStore' }))
  const fallback = iriToId(useWatch({ name: 'fallbackStore' }))
  const control: AisleControl = {
    aisles: Array.isArray(field.value) ? field.value : [],
    setAisles: field.onChange,
  }
  return <StoreInputs control={control} preferred={preferred} fallback={fallback} />
}

const WithoutAisles = () => {
  const preferred = iriToId(useWatch({ name: 'preferredStore' }))
  const fallback = iriToId(useWatch({ name: 'fallbackStore' }))
  return <StoreInputs preferred={preferred} fallback={fallback} />
}

/**
 * « Où l'acheter » (MAG-604): the usual store and the fallback store, each with
 * the product's aisle in it, and the other stores where it has one (MAG-493).
 * The aisle only once the product exists: the API sets aisles on an update,
 * not on a creation.
 */
export const ProductStoreInputs = () => {
  const record = useRecordContext()
  return record?.id ? <WithAisles /> : <WithoutAisles />
}
