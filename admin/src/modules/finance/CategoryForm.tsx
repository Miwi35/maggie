import { useEffect } from 'react'
import { useFormContext, useWatch } from 'react-hook-form'
import {
  SimpleForm,
  BooleanInput,
  TextInput,
  SelectInput,
  AutocompleteInput,
  required,
  useGetList,
  useRecordContext,
} from 'react-admin'
import type { RaRecord } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { OBLIGATION_CHOICES } from './obligationFlags'
import { ColorInput } from './ColorInput'
import { CategoryIconPicker } from './CategoryIconPicker'
import { categoryLabel, parentChoices } from './categoryTree'
import type { CategoryNode } from './categoryTree'

const formSx = { maxWidth: 680 }

interface CategoryFormProps {
  withDefaults?: boolean
}

/**
 * The rente flag, asked only about a recette — the API refuses it anywhere
 * else, so the box on a dépense would be a trap.
 *
 * And cleared on the way out: react-hook-form keeps the value of an unmounted
 * input, so a box ticked on a recette and then reclassified would still submit
 * `passiveIncome: true` — a 422 on a field no longer on screen, with nothing
 * for the user to correct.
 */
const RenteInput = () => {
  const obligation = useWatch({ name: 'obligation' })
  const { setValue } = useFormContext()
  const isIncome = 'income' === obligation

  useEffect(() => {
    if (!isIncome) {
      setValue('passiveIncome', false)
    }
  }, [isIncome, setValue])

  if (!isIncome) {
    return null
  }

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

export const CategoryForm = ({ withDefaults = false }: CategoryFormProps) => (
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

    <FormSection
      title="Nature de la dépense"
      description="C'est ce qui distingue un dépassement sur l'essentiel d'un dépassement sur le reste, dans le score comme dans la revue mensuelle."
    />

    <SelectInput
      source="obligation"
      label="Obligation"
      choices={OBLIGATION_CHOICES}
      defaultValue={withDefaults ? 'optional' : undefined}
      validate={required()}
      fullWidth
      helperText="Obligatoire : loyer, courses. Non-obligatoire : loisirs. Épargne et investissement ne sont pas des dépenses. Recette : ce qui rentre."
    />

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
