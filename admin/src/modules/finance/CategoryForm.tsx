import { useEffect } from 'react'
import { useFormContext, useWatch } from 'react-hook-form'
import {
  SimpleForm,
  BooleanInput,
  TextInput,
  AutocompleteInput,
  required,
  useGetList,
  useRecordContext,
} from 'react-admin'
import type { RaRecord } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { ColorInput } from './ColorInput'
import { CategoryIconPicker } from './CategoryIconPicker'
import { categoryLabel, parentChoices } from './categoryTree'
import type { CategoryNode } from './categoryTree'

const formSx = { maxWidth: 680 }

/**
 * The rente flag: a category of recettes that come in without being worked.
 * The API still ties it to its deprecated `obligation: income` flag, so ticking
 * the box sets it — nothing else about the category says a nature any more.
 */
const RenteInput = () => {
  const passiveIncome = useWatch({ name: 'passiveIncome' })
  const { setValue } = useFormContext()

  useEffect(() => {
    if (passiveIncome === true) {
      setValue('obligation', 'income')
    }
  }, [passiveIncome, setValue])

  return (
    <BooleanInput
      source="passiveIncome"
      label="Rente"
    />
  )
}

/**
 * The parent is chosen by its path. Level-3 categories are not offered (nothing
 * goes under them), nor the category itself or what sits under it; the server
 * has the last word and its refusal is shown by the screen.
 */
const ParentInput = () => {
  const edited = useRecordContext()
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  const choices = parentChoices(data, edited?.id)

  return (
    <AutocompleteInput
      source="parent"
      label="Rattacher à une catégorie"
      choices={choices}
      optionText={categoryLabel}
      fullWidth
    />
  )
}

export const CategoryForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="La catégorie"
    />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
    />

    <ParentInput />

    <RenteInput />

    <FormSection title="Repères visuels" />

    <ColorInput
      source="color"
      label="Couleur"
    />

    <CategoryIconPicker
      source="icon"
      label="Icône"
    />
  </SimpleForm>
)
