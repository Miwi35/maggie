import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import { useFormContext, useFormState } from 'react-hook-form'
import {
  Edit,
  SimpleForm,
  TextInput,
  NumberInput,
  ArrayInput,
  SimpleFormIterator,
  SelectInput,
  required,
  SaveButton,
  Toolbar,
  useRecordContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { CiqualFoodAutocomplete } from './CiqualFoodAutocomplete'
import { RecipeDeleteButton } from './RecipeDeleteButton'
import { TagsInput } from './TagsInput'

const unitChoices = [
  { id: 'g', name: 'g' },
  { id: 'kg', name: 'kg' },
  { id: 'ml', name: 'ml' },
  { id: 'l', name: 'l' },
  { id: 'cl', name: 'cl' },
  { id: 'piece', name: 'pièce' },
  { id: 'bunch', name: 'botte' },
  { id: 'can', name: 'boîte' },
  { id: 'bottle', name: 'bouteille' },
  { id: 'pack', name: 'paquet' },
  { id: 'sachet', name: 'sachet' },
  { id: 'jar', name: 'bocal' },
]

const positive = (value?: number | null) =>
  typeof value === 'number' && value > 0 ? undefined : 'La quantité doit être supérieure à 0'

const RECIPE_TOPICS = ['/api/recipes/{id}']

// The parts of the sheet the API publishes when they change.
const LIVE_FIELDS = ['name', 'servings', 'tags', 'notes', 'ingredients'] as const

const sameValue = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

// Keeps the open sheet in step with what other clients and Maggie do to the recipe. A field with
// unsaved changes keeps what the user typed; the others follow, and a banner offers to take the
// published version of the ones that disagree.
const RecipeLiveSync = () => {
  const record = useRecordContext<{ id: string }>()
  const { getValues, reset } = useFormContext()
  const { dirtyFields } = useFormState()
  const dirty = useRef(dirtyFields)
  useEffect(() => {
    dirty.current = dirtyFields
  }, [dirtyFields])
  const [elsewhere, setElsewhere] = useState<Record<string, unknown> | null>(null)

  // A saved or reloaded record is the new baseline: nothing is left to warn about.
  useEffect(() => setElsewhere(null), [record])

  const onMessage = useCallback(
    (data?: string) => {
      if (!data || !record) return
      let payload: Record<string, unknown>
      try {
        payload = JSON.parse(data)
      } catch {
        return
      }
      if (payload['@id'] !== `/api/recipes/${record.id}` || payload.deleted) return

      const incoming: Record<string, unknown> = {}
      const kept: Record<string, unknown> = {}
      for (const field of LIVE_FIELDS) {
        if (!(field in payload)) continue
        incoming[field] = payload[field]
        if (dirty.current[field] && !sameValue(payload[field], getValues(field))) kept[field] = payload[field]
      }
      if (Object.keys(incoming).length === 0) return

      reset({ ...getValues(), ...incoming }, { keepDirtyValues: true })
      setElsewhere((previous) => {
        const next = { ...previous, ...kept }
        return Object.keys(next).length > 0 ? next : null
      })
    },
    [record, getValues, reset],
  )
  useMercure(RECIPE_TOPICS, onMessage)

  if (!elsewhere) return null

  return (
    <Alert
      severity="info"
      sx={{ mb: 2, width: '100%' }}
      action={
        <Button
          color="inherit"
          size="small"
          onClick={() => {
            reset({ ...getValues(), ...elsewhere })
            setElsewhere(null)
          }}
        >
          Recharger
        </Button>
      }
    >
      Recette modifiée ailleurs : vos changements en cours sont conservés.
    </Alert>
  )
}

const RecipeEditToolbar = () => (
  <Toolbar sx={{ display: 'flex', justifyContent: 'space-between' }}>
    <SaveButton />
    <RecipeDeleteButton />
  </Toolbar>
)

// Pessimistic: the form only shows the new quantity once the API has kept it, and
// a refusal stays on screen instead of silently putting the old value back.
export const RecipeEdit = () => (
  <Edit mutationMode="pessimistic">
    <SimpleForm toolbar={<RecipeEditToolbar />}>
      <RecipeLiveSync />
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <NumberInput source="servings" label="Portions" />
      <TagsInput source="tags" label="Tags (séparés par des virgules)" />
      <TextInput source="notes" label="Notes" multiline rows={3} fullWidth />
      <ArrayInput source="ingredients" label="Ingrédients">
        <SimpleFormIterator inline>
          <CiqualFoodAutocomplete source="ciqualAlimCode" />
          <NumberInput source="quantity" label="Quantité" validate={[required(), positive]} sx={{ maxWidth: 120 }} />
          <SelectInput
            source="unit"
            label="Unité"
            choices={unitChoices}
            defaultValue="g"
            validate={required()}
            sx={{ minWidth: 120 }}
          />
        </SimpleFormIterator>
      </ArrayInput>
    </SimpleForm>
  </Edit>
)
