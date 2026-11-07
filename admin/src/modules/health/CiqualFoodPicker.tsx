import { useEffect, useState } from 'react'
import Autocomplete from '@mui/material/Autocomplete'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'

/** A food of the Ciqual table, as `/ciqual/foods` lists it. */
export interface CiqualFood {
  alim_code: string
  alim_name_fr: string
  alim_group_name_fr: string | null
  kcal_per100g: number | null
  protein_per100g: number | null
  carbs_per100g: number | null
  fat_per100g: number | null
}

const CIQUAL_FOODS = '/ciqual/foods'

interface CiqualFoodPickerProps {
  value: CiqualFood | null
  onChange: (food: CiqualFood | null) => void
}

/**
 * The Ciqual dropdown of the food journal: the same search as a recipe's
 * ingredient (`cookbook/CiqualFoodAutocomplete`), outside a react-admin form.
 */
export const CiqualFoodPicker = ({ value, onChange }: CiqualFoodPickerProps) => {
  const [options, setOptions] = useState<CiqualFood[]>([])
  const [inputValue, setInputValue] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (inputValue.length < 2 || inputValue === value?.alim_name_fr) {
      return
    }
    let cancelled = false
    const timeout = setTimeout(() => {
      setLoading(true)
      fetch(`${CIQUAL_FOODS}?q=${encodeURIComponent(inputValue)}&limit=20`)
        .then((res) => (res.ok ? (res.json() as Promise<CiqualFood[]>) : []))
        .then((foods) => {
          if (!cancelled) setOptions(foods)
        })
        .catch(() => undefined)
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }, 300)

    return () => {
      cancelled = true
      clearTimeout(timeout)
    }
  }, [inputValue, value])

  return (
    <Autocomplete
      fullWidth
      size="small"
      options={options}
      loading={loading}
      value={value}
      inputValue={inputValue}
      getOptionLabel={(option) => option.alim_name_fr}
      isOptionEqualToValue={(option, chosen) => option.alim_code === chosen.alim_code}
      onInputChange={(_event, typed) => setInputValue(typed)}
      onChange={(_event, food) => onChange(food)}
      renderInput={(params) => <TextField {...params} label="Aliment Ciqual" />}
      renderOption={({ key, ...props }, option) => (
        <li key={key} {...props}>
          <div>
            <div>{option.alim_name_fr}</div>
            {option.alim_group_name_fr && (
              <Typography component="div" sx={{ fontSize: 12, color: 'text.secondary' }}>
                {option.alim_group_name_fr}
              </Typography>
            )}
          </div>
        </li>
      )}
      noOptionsText={inputValue.length < 2 ? 'Tapez au moins 2 caractères' : 'Aucun résultat'}
      filterOptions={(x) => x}
    />
  )
}
