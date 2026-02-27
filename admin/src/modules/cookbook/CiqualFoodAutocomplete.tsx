import { useCallback, useEffect, useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import TextField from '@mui/material/TextField'
import { useFormContext } from 'react-hook-form'

interface CiqualFood {
  alim_code: string
  alim_name_fr: string
  alim_group_name_fr: string | null
  kcal_per100g: number | null
  protein_per100g: number | null
  carbs_per100g: number | null
  fat_per100g: number | null
}

const CIQUAL_BASE_URL = '/ciqual'

export const CiqualFoodAutocomplete = ({ source = 'ciqualAlimCode' }: { source?: string }) => {
  const { setValue, watch } = useFormContext()
  const currentValue = watch(source) as string | null | undefined
  const [options, setOptions] = useState<CiqualFood[]>([])
  const [inputValue, setInputValue] = useState('')
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<CiqualFood | null>(null)

  const searchFoods = useCallback(async (query: string) => {
    if (query.length < 2) {
      setOptions([])
      return
    }
    setLoading(true)
    try {
      const res = await fetch(`${CIQUAL_BASE_URL}/foods?q=${encodeURIComponent(query)}&limit=20`)
      if (res.ok) {
        const data = (await res.json()) as CiqualFood[]
        setOptions(data)
      }
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const timeout = setTimeout(() => {
      if (inputValue) {
        searchFoods(inputValue)
      }
    }, 300)
    return () => clearTimeout(timeout)
  }, [inputValue, searchFoods])

  // Load selected food name on initial render if we have an alim code
  useEffect(() => {
    if (currentValue && !selected) {
      fetch(`${CIQUAL_BASE_URL}/foods/${currentValue}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((data: CiqualFood | null) => {
          if (data) {
            setSelected(data)
          }
        })
    }
  }, [currentValue, selected])

  return (
    <Autocomplete
      fullWidth
      options={options}
      loading={loading}
      value={selected}
      inputValue={inputValue}
      getOptionLabel={(option) => option.alim_name_fr}
      isOptionEqualToValue={(option, value) => option.alim_code === value.alim_code}
      onInputChange={(_e, value) => setInputValue(value)}
      onChange={(_e, food) => {
        setSelected(food)
        setValue(source, food?.alim_code ?? null, { shouldDirty: true })
      }}
      renderInput={(params) => <TextField {...params} label="Aliment Ciqual" />}
      renderOption={({ key, ...props }, option) => (
        <li key={key} {...props}>
          <div>
            <div>{option.alim_name_fr}</div>
            {option.alim_group_name_fr && (
              <div style={{ fontSize: '0.8em', color: '#888' }}>{option.alim_group_name_fr}</div>
            )}
          </div>
        </li>
      )}
      noOptionsText={inputValue.length < 2 ? 'Tapez au moins 2 caractères' : 'Aucun résultat'}
      filterOptions={(x) => x}
    />
  )
}

export const CiqualAutoFill = () => {
  const { watch, setValue } = useFormContext()
  const ciqualAlimCode = watch('ciqualAlimCode') as string | null | undefined
  const [foodData, setFoodData] = useState<CiqualFood | null>(null)

  useEffect(() => {
    if (!ciqualAlimCode) {
      setFoodData(null)
      return
    }
    fetch(`${CIQUAL_BASE_URL}/foods/${ciqualAlimCode}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((data: CiqualFood | null) => setFoodData(data))
  }, [ciqualAlimCode])

  const handleAutoFill = useCallback(() => {
    if (!foodData) return
    if (foodData.kcal_per100g != null) setValue('kcalPer100g', foodData.kcal_per100g, { shouldDirty: true })
    if (foodData.protein_per100g != null) setValue('proteinPer100g', foodData.protein_per100g, { shouldDirty: true })
    if (foodData.carbs_per100g != null) setValue('carbsPer100g', foodData.carbs_per100g, { shouldDirty: true })
    if (foodData.fat_per100g != null) setValue('fatPer100g', foodData.fat_per100g, { shouldDirty: true })
  }, [foodData, setValue])

  if (!ciqualAlimCode || !foodData) return null

  return (
    <span
      style={{ cursor: 'pointer', textDecoration: 'underline', color: '#1976d2', fontSize: '0.875rem' }}
      onClick={handleAutoFill}
    >
      Remplir les macros depuis Ciqual
    </span>
  )
}
