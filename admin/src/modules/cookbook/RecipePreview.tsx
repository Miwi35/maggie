import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import { useCreatePath } from 'react-admin'
import { Link as RouterLink } from 'react-router-dom'
import { quantityLabel } from '../grocery/packaging'
import { durationLabel, orderedSteps, servingsLabel } from './recipeSheet'
import type { RecipeRecord } from './recipeSheet'
import { stepsMinutes } from './recipeTable'
import type { RecipeFacts } from './useRecipeFacts'

export const PREVIEW_WIDTH = 360

/** The recipe the table points at, on its right: what it is made with and how, to read before opening the sheet. */
export const RecipePreview = ({ recipe, facts }: { recipe: RecipeRecord | null; facts?: RecipeFacts }) => {
  const createPath = useCreatePath()

  return (
    <Paper
      component="aside"
      aria-label="Aperçu de la recette"
      variant="outlined"
      sx={{ width: PREVIEW_WIDTH, flexShrink: 0, p: 2, position: 'sticky', top: 16, alignSelf: 'flex-start', maxHeight: 'calc(100vh - 96px)', overflow: 'auto' }}
    >
      {recipe === null ? (
        <Typography color="text.secondary">Survolez une recette pour la voir ici.</Typography>
      ) : (
        <Preview recipe={recipe} facts={facts} href={createPath({ resource: 'recipes', type: 'show', id: recipe.id })} />
      )}
    </Paper>
  )
}

const Preview = ({ recipe, facts, href }: { recipe: RecipeRecord; facts?: RecipeFacts; href: string }) => {
  const minutes = stepsMinutes(recipe.steps)
  const steps = orderedSteps(recipe.steps)
  const lines = recipe.ingredients ?? []
  const meta = [
    servingsLabel(recipe.servings ?? 1),
    minutes !== null ? durationLabel(minutes) : null,
    facts?.kcalPerServing != null ? `≈ ${facts.kcalPerServing} kcal / pers.` : null,
  ].filter(Boolean)

  return (
    <>
      <Typography variant="h6" component="h2">
        {recipe.name}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {meta.join(' · ')}
      </Typography>
      {(recipe.tags ?? []).length > 0 && (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mt: 1 }}>
          {recipe.tags?.map((tag) => <Chip key={tag} label={tag} size="small" />)}
        </Box>
      )}

      <Typography variant="subtitle2" component="h3" sx={{ mt: 2, mb: 0.5 }}>
        Ingrédients
      </Typography>
      {lines.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucun ingrédient pour l’instant.
        </Typography>
      ) : (
        <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {lines.map((line) => (
            <Box component="li" key={line.id ?? line.ingredientName} sx={{ display: 'flex', gap: 1, py: 0.25 }}>
              <Typography variant="body2" color="text.secondary" sx={{ width: 84, flexShrink: 0, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {quantityLabel(line.quantity, line.unit)}
              </Typography>
              <Typography variant="body2">{line.ingredientName}</Typography>
            </Box>
          ))}
        </Box>
      )}

      {steps.length > 0 && (
        <>
          <Typography variant="subtitle2" component="h3" sx={{ mt: 2, mb: 0.5 }}>
            Étapes
          </Typography>
          <Box component="ol" sx={{ m: 0, pl: 3 }}>
            {steps.map((step) => (
              <Typography key={step.position} component="li" variant="body2" sx={{ py: 0.25 }}>
                {step.text}
              </Typography>
            ))}
          </Box>
        </>
      )}

      <Button component={RouterLink} to={href} size="small" sx={{ mt: 2 }}>
        Ouvrir la fiche
      </Button>
    </>
  )
}
