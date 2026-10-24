import { useEffect, useMemo, useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useGetList } from 'react-admin'
import { packagingLabel } from '../grocery/packaging'

/** A product of the owner's catalogue, or a Ciqual food the API turns into one when the recipe is saved. */
export interface PickedProduct {
  source: 'catalogue' | 'ciqual'
  name: string
  ingredientId?: string
  ciqualAlimCode?: string
  packaging?: string | null
}

interface CatalogueIngredient {
  id: string
  name: string
  ciqualAlimCode?: string | null
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
}

interface CiqualFood {
  alim_code: string
  alim_name_fr: string
  alim_group_name_fr: string | null
}

const GROUPS = { catalogue: 'Mon catalogue', ciqual: 'Ciqual' } as const

const fold = (text: string) => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()

const idOf = (value: unknown) => String(value).split('/').pop() ?? ''

/**
 * One search over the owner's catalogue and Ciqual: the catalogue first, then the Ciqual foods the
 * catalogue does not already hold. Picking a Ciqual food adds nothing by itself — the product is
 * created with the recipe.
 */
export const IngredientPicker = ({
  value,
  onChange,
  error,
}: {
  value: PickedProduct | null
  onChange: (product: PickedProduct | null) => void
  error?: boolean
}) => {
  const [input, setInput] = useState(value?.name ?? '')
  const [foods, setFoods] = useState<CiqualFood[]>([])
  const [loading, setLoading] = useState(false)
  const { data: catalogue } = useGetList<CatalogueIngredient>('ingredients', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })

  useEffect(() => {
    const query = input.trim()
    if (query.length < 2 || query === value?.name) {
      setFoods([])
      return
    }
    const controller = new AbortController()
    const timeout = setTimeout(() => {
      setLoading(true)
      fetch(`/ciqual/foods?q=${encodeURIComponent(query)}&limit=10`, { signal: controller.signal })
        .then((response) => (response.ok ? (response.json() as Promise<CiqualFood[]>) : []))
        .then((data) => setFoods(data))
        .catch(() => {})
        .finally(() => setLoading(false))
    }, 300)

    return () => {
      clearTimeout(timeout)
      controller.abort()
    }
  }, [input, value?.name])

  const options = useMemo(() => {
    const query = fold(input.trim())
    const known = (catalogue ?? []).filter((product) => query === '' || fold(product.name).includes(query))
    const held = new Set((catalogue ?? []).map((product) => product.ciqualAlimCode).filter(Boolean))
    const fromCatalogue: PickedProduct[] = known.map((product) => ({
      source: 'catalogue',
      name: product.name,
      ingredientId: idOf(product.id),
      ciqualAlimCode: product.ciqualAlimCode ?? undefined,
      packaging: packagingLabel(product.packagingUnit, product.packagingSize, product.packagingSizeUnit),
    }))
    const fromCiqual: PickedProduct[] = foods
      .filter((food) => !held.has(food.alim_code))
      .map((food) => ({ source: 'ciqual', name: food.alim_name_fr, ciqualAlimCode: food.alim_code }))

    return [...fromCatalogue, ...fromCiqual]
  }, [catalogue, foods, input])

  return (
    <Autocomplete<PickedProduct>
      fullWidth
      autoHighlight
      options={options}
      loading={loading}
      value={value}
      inputValue={input}
      groupBy={(option) => GROUPS[option.source]}
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(option, selected) =>
        option.source === selected.source && option.name === selected.name && option.ingredientId === selected.ingredientId
      }
      filterOptions={(all) => all}
      onInputChange={(_event, next) => setInput(next)}
      onChange={(_event, next) => onChange(next)}
      noOptionsText={input.trim().length < 2 ? 'Tapez au moins 2 caractères' : 'Aucun résultat'}
      renderInput={(params) => <TextField {...params} label="Ingrédient" error={error} autoFocus />}
      renderOption={({ key, ...props }, option) => (
        <Box component="li" key={key} {...props}>
          <Box>
            <Typography>{option.name}</Typography>
            {option.packaging && (
              <Typography variant="caption" color="text.secondary">
                {option.packaging}
              </Typography>
            )}
          </Box>
        </Box>
      )}
    />
  )
}
