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
      helperText="Un revenu qui rentre sans être travaillé — loyers perçus, dividendes, intérêts, redevances. C'est ce que le compteur d'indépendance compare à votre train de vie."
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
      helperText="Laissez vide pour une catégorie principale. Trois niveaux au maximum : Loisirs › Bars › Obsydian."
    />
  )
}

export const CategoryForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="La catégorie"
      description="Les catégories classent vos transactions : elles portent les budgets, les règles automatiques et la revue mensuelle."
    />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
      helperText="Alimentation, Loisirs, Transport…"
    />

    <ParentInput />

    <RenteInput />

    <FormSection title="Repères visuels" description="Facultatif, pour repérer la catégorie d'un coup d'œil." />

    <ColorInput
      source="color"
      label="Couleur"
      helperText="Cliquez sur une couleur, ou sur la pastille de droite pour en choisir une autre."
    />

    <CategoryIconPicker
      source="icon"
      label="Icône"
      helperText="Choisissez une icône dans la grille, ou cherchez-la par son nom : courses, voiture, restaurant…"
    />
  </SimpleForm>
)
