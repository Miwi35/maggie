import { useGetList, useInput } from 'react-admin'
import { NameChipsInput } from './NameChipsInput'
import { nameOf } from './recipeSteps'
import type { NameRef } from './recipeSteps'

// The recipe's utensils, picked from the user's catalogue; a new name is created when the recipe is saved.
export const RecipeUtensilsInput = () => {
  const { field } = useInput<NameRef[]>({ source: 'utensils', defaultValue: [] })
  const { data } = useGetList<{ id: string; name: string }>('utensils', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })

  return (
    <NameChipsInput
      label="Matériel"
      create
      value={(field.value ?? []).map(nameOf)}
      options={(data ?? []).map((utensil) => utensil.name)}
      onChange={(names) => field.onChange(names)}
    />
  )
}
