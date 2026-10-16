import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import { Show, useRecordContext } from 'react-admin'
import { nameOf } from './recipeSteps'
import type { NameRef } from './recipeSteps'

interface Step {
  id?: string
  position?: number
  text: string
  durationMinutes: number | null
  background: boolean
  ingredients?: NameRef[]
  utensils?: NameRef[]
  waitsFor?: number[]
}

interface Recipe {
  name: string
  servings?: number
  tags?: string[]
  notes?: string | null
  ingredients?: { id?: string; ingredientName: string; quantity: number; unit: string }[]
  utensils?: NameRef[]
  steps?: Step[]
}

const unitLabels: Record<string, string> = { piece: 'pièce', bunch: 'botte', can: 'boîte', bottle: 'bouteille', jar: 'bocal' }

const durationLabel = (minutes: number): string => {
  if (minutes < 60) return `${minutes} min`
  const rest = minutes % 60
  return `${Math.floor(minutes / 60)} h${rest === 0 ? '' : ` ${String(rest).padStart(2, '0')}`}`
}

const Section = ({ title, children }: { title: string; children: ReactNode }) => (
  <Box component="section" sx={{ mb: 3 }}>
    <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
      {title}
    </Typography>
    {children}
  </Box>
)

const Names = ({ names, label }: { names: NameRef[]; label: string }) => (
  <Box component="ul" aria-label={label} sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, listStyle: 'none', m: 0, p: 0 }}>
    {names.map((ref) => (
      <li key={nameOf(ref)}>
        <Chip label={nameOf(ref)} size="small" variant="outlined" />
      </li>
    ))}
  </Box>
)

const StepRow = ({ step, position }: { step: Step; position: number }) => {
  const ingredients = step.ingredients ?? []
  const utensils = step.utensils ?? []
  const waitsFor = step.waitsFor ?? []

  return (
    <Box component="li" sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, mb: 1.5 }}>
      <Typography component="div">
        <strong>{position}.</strong> {step.text}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, alignItems: 'center' }}>
        {step.durationMinutes !== null && <Chip size="small" label={`⏱ ${durationLabel(step.durationMinutes)}`} />}
        {step.background && <Chip size="small" color="info" label="en fond" />}
        {waitsFor.length > 0 && <Chip size="small" variant="outlined" label={`attend ${waitsFor.join(', ')}`} />}
      </Box>
      {ingredients.length > 0 && <Names names={ingredients} label={`Ingrédients de l'étape ${position}`} />}
      {utensils.length > 0 && <Names names={utensils} label={`Ustensiles de l'étape ${position}`} />}
    </Box>
  )
}

const RecipeSheet = () => {
  const recipe = useRecordContext<Recipe>()
  if (!recipe) return null

  const ingredients = recipe.ingredients ?? []
  const utensils = recipe.utensils ?? []
  const steps = [...(recipe.steps ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0))

  return (
    <Box sx={{ p: 2, width: '100%' }}>
      <Typography variant="h5" component="h1" sx={{ mb: 1 }}>
        {recipe.name}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, alignItems: 'center', mb: 2 }}>
        {recipe.servings !== undefined && (
          <Typography color="text.secondary" sx={{ mr: 1 }}>
            {recipe.servings} portion{recipe.servings > 1 ? 's' : ''}
          </Typography>
        )}
        {(recipe.tags ?? []).map((tag) => (
          <Chip key={tag} label={tag} size="small" />
        ))}
      </Box>
      {recipe.notes && (
        <Typography sx={{ mb: 3, whiteSpace: 'pre-wrap' }} color="text.secondary">
          {recipe.notes}
        </Typography>
      )}
      {ingredients.length > 0 && (
        <Section title="Ingrédients">
          <Box component="ul" sx={{ m: 0, pl: 3 }}>
            {ingredients.map((line, index) => (
              <li key={line.id ?? index}>
                {line.quantity} {unitLabels[line.unit] ?? line.unit} {line.ingredientName}
              </li>
            ))}
          </Box>
        </Section>
      )}
      {utensils.length > 0 && (
        <Section title="Matériel">
          <Names names={utensils} label="Matériel" />
        </Section>
      )}
      {steps.length > 0 && (
        <Section title="Étapes">
          <Box component="ol" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {steps.map((step, index) => (
              <StepRow key={step.id ?? index} step={step} position={index + 1} />
            ))}
          </Box>
        </Section>
      )}
    </Box>
  )
}

export const RecipeShow = () => (
  <Show title="Recette">
    <RecipeSheet />
  </Show>
)
