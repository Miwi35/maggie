import { AutocompleteInput, useGetList } from 'react-admin'
import type { RaRecord, Validator } from 'react-admin'
import { categoryLabel, orderedCategories } from './categoryTree'
import type { CategoryNode } from './categoryTree'

interface CategoryInputProps {
  source: string
  label: string
  validate?: Validator
  fullWidth?: boolean
  helperText?: string
}

/** Picks a category by its path (« Loisirs › Bars › Obsydian »), the tree in order. */
export const CategoryInput = (props: CategoryInputProps) => {
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })

  return <AutocompleteInput {...props} choices={orderedCategories(data)} optionText={categoryLabel} />
}
