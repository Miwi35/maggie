import { useState } from 'react'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import TextField from '@mui/material/TextField'
import { NameChipsInput } from './NameChipsInput'
import { EditDialog, useDialogSave } from './RecipeDialogs'
import { utensilNames } from './recipeSheet'
import type { RecipeRecord, RecipeStep } from './recipeSheet'
import { nameOf, newStep, setDuration, sameName, stepsPayload } from './recipeSteps'
import type { NameRef } from './recipeSteps'

type Save = (data: Record<string, unknown>) => Promise<string | null>

const label = (position: number) => `Étape ${position}`

/**
 * Adds or edits one step: its text, duration, whether it runs in the background, the earlier steps
 * it waits for, and the recipe's ingredients and utensils it uses. The whole list goes back to the
 * API, in order — the positions the other steps wait for are not touched.
 */
export const RecipeStepDialog = ({
  recipe,
  steps,
  index,
  onSave,
  onClose,
}: {
  recipe: RecipeRecord
  steps: RecipeStep[]
  /** The step to edit, or `steps.length` to add one at the end. */
  index: number
  onSave: Save
  onClose: () => void
}) => {
  const adding = index >= steps.length
  const [step, setStep] = useState<RecipeStep>(() => (adding ? newStep() : steps[index]))
  const { error, saving, run } = useDialogSave(onClose)

  const products = (recipe.ingredients ?? []).flatMap((line) => (line.ingredient ? [{ id: line.ingredient.id, name: line.ingredient.name }] : []))
  const refFor = (name: string): NameRef => {
    const product = products.find((candidate) => sameName(candidate.name, name))
    return product ?? name
  }
  const position = index + 1

  return (
    <EditDialog
      title={adding ? 'Ajouter une étape' : `Étape ${position}`}
      onClose={onClose}
      error={error}
      saving={saving}
      submitDisabled={step.text.trim() === ''}
      onSubmit={() => run(() => onSave({ steps: stepsPayload(adding ? [...steps, step] : steps.map((other, i) => (i === index ? step : other))) }))}
    >
      <TextField label="Texte" value={step.text} onChange={(event) => setStep({ ...step, text: event.target.value })} multiline minRows={3} autoFocus fullWidth />
      <TextField
        label="Durée (min)"
        type="number"
        value={step.durationMinutes ?? ''}
        slotProps={{ htmlInput: { min: 1 } }}
        sx={{ maxWidth: 180 }}
        onChange={(event) => {
          const minutes = Number.parseInt(event.target.value, 10)
          setStep(setDuration(step, Number.isNaN(minutes) ? null : minutes))
        }}
      />
      <FormControlLabel
        label="En fond"
        control={<Checkbox checked={step.background} disabled={step.durationMinutes === null} onChange={(event) => setStep({ ...step, background: event.target.checked })} />}
      />
      <NameChipsInput
        label="Attend"
        value={step.waitsFor.map(label)}
        options={Array.from({ length: Math.min(index, steps.length) }, (_, earlier) => label(earlier + 1))}
        onChange={(names) => setStep({ ...step, waitsFor: names.map((name) => Number(name.replace(/\D/g, ''))).sort((a, b) => a - b) })}
      />
      <NameChipsInput
        label="Ingrédients"
        value={step.ingredients.map(nameOf)}
        options={products.map((product) => product.name)}
        onChange={(names) => setStep({ ...step, ingredients: names.map(refFor) })}
      />
      <NameChipsInput
        label="Ustensiles"
        create
        value={step.utensils.map(nameOf)}
        options={utensilNames(recipe.utensils)}
        onChange={(names) => setStep({ ...step, utensils: names })}
      />
    </EditDialog>
  )
}
