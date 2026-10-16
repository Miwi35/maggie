import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { useInput, useNotify } from 'react-admin'
import { useWatch } from 'react-hook-form'
import { NameChipsInput } from './NameChipsInput'
import { dragId, moveStep, nameOf, newStep, removeStep, setDuration, stepOfError } from './recipeSteps'
import type { RecipeStepDraft } from './recipeSteps'

/** An API refusal of the steps, and the step it is about (null: the list as a whole). */
export interface StepsError {
  position: number | null
  message: string
}

export const StepsErrorContext = createContext<{ error: StepsError | null; clear: () => void }>({ error: null, clear: () => {} })

/** The 400 the API answers to a refused list of steps, if that is what `error` is. */
export const stepsErrorOf = (error: unknown): StepsError | null => {
  const { status, body, message } = (error ?? {}) as { status?: number; body?: Record<string, unknown>; message?: string }
  if (status !== 400) return null
  const detail = body?.['hydra:description'] ?? body?.detail ?? message
  if (typeof detail !== 'string' || detail === '') return null
  return { position: stepOfError(detail), message: detail }
}

/** The refusal of the steps by the API, to hand to the form (context) and to the save (onError). */
export const useStepsErrors = () => {
  const notify = useNotify()
  const [error, setError] = useState<StepsError | null>(null)
  const clear = useCallback(() => setError(null), [])
  const onError = useCallback(
    (failure: unknown) => {
      const refused = stepsErrorOf(failure)
      setError(refused)
      notify(refused?.message ?? (failure as Error)?.message ?? 'ra.notification.http_error', { type: 'error' })
    },
    [notify],
  )
  const value = useMemo(() => ({ error, clear }), [error, clear])

  return { value, onError }
}

const stepLabel = (position: number) => `Étape ${position}`

interface StepProps {
  step: RecipeStepDraft
  index: number
  ingredientNames: string[]
  utensilNames: string[]
  error?: string
  onChange: (step: RecipeStepDraft) => void
  onRemove: () => void
}

const SortableStep = ({ step, index, ingredientNames, utensilNames, error, onChange, onRemove }: StepProps) => {
  const position = index + 1
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: dragId(step, index) })

  return (
    <Paper
      ref={setNodeRef}
      variant="outlined"
      role="group"
      aria-label={stepLabel(position)}
      sx={{ p: 1, mb: 1, display: 'flex', flexDirection: 'column', gap: 1, opacity: isDragging ? 0.5 : 1 }}
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
        <IconButton size="small" aria-label={`Déplacer l'étape ${position}`} sx={{ cursor: 'grab', mt: 0.5 }} {...attributes} {...listeners}>
          <DragIndicatorIcon fontSize="small" />
        </IconButton>
        <Typography sx={{ minWidth: 20, pt: 1 }} color="text.secondary">
          {position}
        </Typography>
        <TextField
          label="Texte"
          size="small"
          fullWidth
          multiline
          value={step.text}
          error={Boolean(error)}
          onChange={(event) => onChange({ ...step, text: event.target.value })}
        />
        <IconButton size="small" aria-label={`Supprimer l'étape ${position}`} onClick={onRemove} sx={{ mt: 0.5 }}>
          <DeleteOutlineIcon fontSize="small" />
        </IconButton>
      </Box>
      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, gap: 1, alignItems: { md: 'center' }, pl: { md: 6 } }}>
        <TextField
          label="Durée (min)"
          type="number"
          size="small"
          sx={{ width: { md: 130 }, flexShrink: 0 }}
          slotProps={{ htmlInput: { min: 1 } }}
          value={step.durationMinutes ?? ''}
          onChange={(event) => {
            const minutes = Number.parseInt(event.target.value, 10)
            onChange(setDuration(step, Number.isNaN(minutes) ? null : minutes))
          }}
        />
        <FormControlLabel
          sx={{ flexShrink: 0, m: 0 }}
          label="En fond"
          control={
            <Checkbox
              size="small"
              checked={step.background}
              disabled={step.durationMinutes === null}
              onChange={(event) => onChange({ ...step, background: event.target.checked })}
            />
          }
        />
        <NameChipsInput
          label="Attend"
          value={step.waitsFor.map(stepLabel)}
          options={Array.from({ length: index }, (_, earlier) => stepLabel(earlier + 1))}
          onChange={(names) => onChange({ ...step, waitsFor: names.map((name) => Number(name.replace(/\D/g, ''))).sort((a, b) => a - b) })}
        />
      </Box>
      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, gap: 1, pl: { md: 6 } }}>
        <NameChipsInput
          label="Ingrédients"
          value={step.ingredients.map(nameOf)}
          options={ingredientNames}
          onChange={(names) => onChange({ ...step, ingredients: names })}
        />
        <NameChipsInput
          label="Ustensiles"
          value={step.utensils.map(nameOf)}
          options={utensilNames}
          onChange={(names) => onChange({ ...step, utensils: names })}
        />
      </Box>
      {error && (
        <Alert severity="error" sx={{ ml: { md: 6 } }}>
          {error}
        </Alert>
      )}
    </Paper>
  )
}

// The steps of the recipe, in order, reorderable. The list is one value: moving a step rewrites the
// positions the others wait for, so what is sent is always consistent.
export const RecipeStepsInput = () => {
  const { field } = useInput<RecipeStepDraft[]>({ source: 'steps', defaultValue: [] })
  const lines = useWatch({ name: 'ingredients' }) as { ingredientName?: string | null }[] | undefined
  const utensils = useWatch({ name: 'utensils' }) as Parameters<typeof nameOf>[0][] | undefined
  const { error, clear } = useContext(StepsErrorContext)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const steps: RecipeStepDraft[] = field.value ?? []
  const ingredientNames = (lines ?? []).map((line) => line.ingredientName ?? '').filter((name) => name !== '')
  const utensilNames = (utensils ?? []).map(nameOf)

  const update = (next: RecipeStepDraft[]) => {
    clear()
    field.onChange(next)
  }

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const from = steps.findIndex((step, index) => dragId(step, index) === active.id)
    const to = steps.findIndex((step, index) => dragId(step, index) === over.id)
    if (from !== -1 && to !== -1) update(moveStep(steps, from, to))
  }

  return (
    <Box sx={{ width: '100%', mb: 2 }}>
      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Étapes
      </Typography>
      {error && error.position === null && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error.message}
        </Alert>
      )}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={steps.map(dragId)} strategy={verticalListSortingStrategy}>
          {steps.map((step, index) => (
            <SortableStep
              key={dragId(step, index)}
              step={step}
              index={index}
              ingredientNames={ingredientNames}
              utensilNames={utensilNames}
              error={error && error.position === index + 1 ? error.message : undefined}
              onChange={(changed) => update(steps.map((other, i) => (i === index ? changed : other)))}
              onRemove={() => update(removeStep(steps, index))}
            />
          ))}
        </SortableContext>
      </DndContext>
      <Button startIcon={<AddIcon />} onClick={() => update([...steps, newStep()])}>
        Ajouter une étape
      </Button>
    </Box>
  )
}
