import { useState } from 'react'
import Autocomplete, { createFilterOptions } from '@mui/material/Autocomplete'
import TextField from '@mui/material/TextField'
import { sameName } from './recipeSteps'

interface Option {
  name: string
  isNew?: boolean
}

const filter = createFilterOptions<Option>()

const unique = (names: string[]) => names.filter((name, index) => name.trim() !== '' && names.findIndex((other) => sameName(other, name)) === index)

/**
 * Several names picked from a list. With `create`, a name the list does not have can be typed and
 * added — the API creates it on save.
 */
export const NameChipsInput = ({
  label,
  value,
  options,
  onChange,
  create = false,
  error,
}: {
  label: string
  value: string[]
  options: string[]
  onChange: (names: string[]) => void
  create?: boolean
  error?: boolean
}) => {
  const [input, setInput] = useState('')
  const known = unique([...options, ...value])

  return (
    <Autocomplete<Option, true, false, boolean>
      multiple
      freeSolo={create}
      size="small"
      fullWidth
      options={known.map((name) => ({ name }))}
      value={value.map((name) => ({ name }))}
      inputValue={input}
      onInputChange={(_event, next) => setInput(next)}
      getOptionLabel={(option) => (typeof option === 'string' ? option : option.isNew ? `Ajouter « ${option.name} »` : option.name)}
      isOptionEqualToValue={(option, selected) => sameName(option.name, selected.name)}
      filterOptions={(all, params) => {
        const filtered = filter(all, params)
        const typed = params.inputValue.trim()
        if (create && typed !== '' && !known.some((name) => sameName(name, typed))) filtered.push({ name: typed, isNew: true })
        return filtered
      }}
      onChange={(_event, next) => onChange(unique(next.map((option) => (typeof option === 'string' ? option.trim() : option.name.trim()))))}
      noOptionsText="Aucun choix"
      renderInput={(params) => <TextField {...params} label={label} error={error} />}
    />
  )
}
