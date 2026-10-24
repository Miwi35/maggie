import { useState } from 'react'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { IngredientPicker } from './IngredientPicker'
import type { PickedProduct } from './IngredientPicker'
import { EditDialog, useDialogSave } from './RecipeDialogs'
import { UNIT_CHOICES, linesPayload, orderedSteps, productIdOf } from './recipeSheet'
import type { RecipeLine, RecipeRecord } from './recipeSheet'
import { sameName, stepsPayload } from './recipeSteps'

/** A step whose text still cites the old product: what it says now, and what it would say. */
export interface TextSuggestion {
  stepId: string
  position: number
  before: string
  after: string
}

export interface ReplaceResult {
  recipe: RecipeRecord
  textSuggestions: TextSuggestion[]
}

export type ReplaceOutcome = { result: ReplaceResult } | { error: string }

type Save = (data: Record<string, unknown>) => Promise<string | null>

export interface ReplaceBody {
  ingredientId: string
  with: { ingredientId?: string; ciqualAlimCode?: string }
  quantity: number
  unit: string
}

const pickedFrom = (line: RecipeLine): PickedProduct => ({
  source: 'catalogue',
  name: line.ingredientName,
  ingredientId: productIdOf(line) ?? undefined,
  ciqualAlimCode: line.ciqualAlimCode ?? undefined,
})

const sameProduct = (picked: PickedProduct, line: RecipeLine): boolean =>
  picked.ingredientId !== undefined && picked.ingredientId === productIdOf(line)

const Suggestions = ({ suggestions }: { suggestions: TextSuggestion[] }) => (
  <Box component="ul" aria-label="Étapes à mettre à jour" sx={{ m: 0, p: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 1.5 }}>
    {suggestions.map((suggestion) => (
      <li key={suggestion.stepId}>
        <Typography variant="subtitle2">Étape {suggestion.position}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ textDecoration: 'line-through' }}>
          {suggestion.before}
        </Typography>
        <Typography variant="body2">{suggestion.after}</Typography>
      </li>
    ))}
  </Box>
)

/**
 * Adds an ingredient to the recipe, or edits a line: its quantity and unit, or its product. A
 * different product goes through the API's « replace » action, which moves the steps' links to the
 * new product; the steps whose text still cites the old one are shown before and after, and
 * rewritten only when the owner says so.
 */
export const RecipeIngredientDialog = ({
  recipe,
  line,
  onSave,
  onReplace,
  onClose,
}: {
  recipe: RecipeRecord
  line: RecipeLine | null
  onSave: Save
  onReplace: (body: ReplaceBody) => Promise<ReplaceOutcome>
  onClose: () => void
}) => {
  const [product, setProduct] = useState<PickedProduct | null>(line ? pickedFrom(line) : null)
  const [quantity, setQuantity] = useState(line ? String(line.quantity) : '')
  const [unit, setUnit] = useState(line?.unit ?? 'g')
  const [review, setReview] = useState<ReplaceResult | null>(null)
  const { error, setError, saving, run } = useDialogSave(onClose)
  const lines = recipe.ingredients ?? []
  const amount = Number.parseFloat(quantity.replace(',', '.'))
  const valid = product !== null && Number.isFinite(amount) && amount > 0

  if (review) {
    const rewrite = () => {
      const texts = new Map(review.textSuggestions.map((suggestion) => [suggestion.stepId, suggestion.after]))
      const steps = orderedSteps(review.recipe.steps).map((step) => ({ ...step, text: (step.id && texts.get(step.id)) ?? step.text }))

      return run(() => onSave({ steps: stepsPayload(steps) }))
    }

    return (
      <EditDialog
        title="Mettre à jour les étapes ?"
        onClose={onClose}
        onSubmit={rewrite}
        error={error}
        saving={saving}
        submitLabel="Mettre à jour"
        cancelLabel="Laisser"
      >
        <Typography>
          Des étapes citent encore l’ancien ingrédient. Rien n’est réécrit sans votre accord : les liens de ces étapes pointent déjà vers le nouveau.
        </Typography>
        <Suggestions suggestions={review.textSuggestions} />
      </EditDialog>
    )
  }

  const submit = () => {
    if (!product) return Promise.resolve()

    if (line && !sameProduct(product, line)) {
      return run(async () => {
        const outcome = await onReplace({
          ingredientId: productIdOf(line) ?? '',
          with: product.ingredientId ? { ingredientId: product.ingredientId } : { ciqualAlimCode: product.ciqualAlimCode },
          quantity: amount,
          unit,
        })
        if ('error' in outcome) return outcome.error
        if (outcome.result.textSuggestions.length > 0) {
          setReview(outcome.result)
          // The popin stays open on the suggestions: the replacement itself is done.
          return false
        }
        return null
      })
    }

    if (line) {
      const next = lines.map((other) => (other === line ? { ...other, quantity: amount, unit } : other))
      return run(() => onSave({ ingredients: linesPayload(next) }))
    }

    const already = lines.some((other) => sameProduct(product, other) || sameName(other.ingredientName, product.name))
    if (already) {
      setError(`« ${product.name} » est déjà dans la recette : changez sa quantité.`)
      return Promise.resolve()
    }

    const added = { ...(product.ingredientId ? { ingredientId: product.ingredientId } : { ciqualAlimCode: product.ciqualAlimCode }), quantity: amount, unit }

    return run(() => onSave({ ingredients: [...linesPayload(lines), added] }))
  }

  return (
    <EditDialog
      title={line ? `Modifier « ${line.ingredientName} »` : 'Ajouter un ingrédient'}
      onClose={onClose}
      onSubmit={submit}
      error={error}
      saving={saving}
      submitDisabled={!valid}
    >
      <IngredientPicker value={product} onChange={setProduct} />
      <Box sx={{ display: 'flex', gap: 2 }}>
        <TextField label="Quantité" type="number" value={quantity} onChange={(event) => setQuantity(event.target.value)} slotProps={{ htmlInput: { min: 0, step: 'any' } }} sx={{ flex: 1 }} />
        <TextField label="Unité" select value={unit} onChange={(event) => setUnit(event.target.value)} sx={{ flex: 1 }}>
          {UNIT_CHOICES.map((choice) => (
            <MenuItem key={choice.id} value={choice.id}>
              {choice.name}
            </MenuItem>
          ))}
        </TextField>
      </Box>
      {line && product && !sameProduct(product, line) && (
        <Typography variant="body2" color="text.secondary">
          Les étapes qui utilisent « {line.ingredientName} » passeront à « {product.name} ».
        </Typography>
      )}
    </EditDialog>
  )
}
