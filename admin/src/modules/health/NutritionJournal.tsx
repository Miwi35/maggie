import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { formatInteger } from './healthProfileApi'
import { cardSx, formatGrams, parisClock } from './nutritionBalance'
import type { PlannedMeal } from './nutritionBalance'
import { SOURCE_LABELS } from './nutritionApi'
import type { FoodIntake, Loaded } from './nutritionApi'

const SLOT_LABELS: Record<PlannedMeal['slot'], string> = { lunch: 'Déjeuner', dinner: 'Dîner' }

/** « P 38 g · G 53 g · L 16 g ». */
const macros = (row: { proteinG: number; carbsG: number; fatG: number }) =>
  `P ${formatGrams(row.proteinG)} · G ${formatGrams(row.carbsG)} · L ${formatGrams(row.fatG)}`

const rowSx = {
  display: 'grid',
  gridTemplateColumns: 'minmax(0, 1fr) auto auto',
  alignItems: 'center',
  gap: 1.5,
  py: 1,
  borderTop: 1,
  borderColor: 'divider',
} as const

interface NutritionJournalProps {
  intakes: Loaded<FoodIntake[]>
  planned: PlannedMeal[]
  /** The meal whose « Mangé » is on its way: its button waits. */
  eating: string | null
  onEaten: (mealId: string) => void
  onDelete: (intake: FoodIntake) => void
}

/** « Journal »: what was eaten, then the planned meals not eaten yet, each with « Mangé ». */
export const NutritionJournal = ({ intakes, planned, eating, onEaten, onDelete }: NutritionJournalProps) => {
  const rows = intakes.status === 'ready' ? intakes.data : []

  return (
    <Box component="section" aria-label="Journal" sx={cardSx}>
      <Box sx={{ fontSize: 15, fontWeight: 500 }}>Journal</Box>
      {intakes.status === 'loading' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Chargement…</Typography>}
      {intakes.status === 'error' && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Erreur : le journal n’a pas pu être chargé.</Typography>
      )}
      {intakes.status === 'ready' && rows.length === 0 && planned.length === 0 && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Rien de noté ce jour.</Typography>
      )}
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {rows.map((intake) => (
          <Box component="li" key={intake.id} data-testid={`intake-row-${intake.id}`} sx={rowSx}>
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 14, overflowWrap: 'anywhere' }}>{intake.label}</Typography>
              <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                {parisClock(new Date(intake.eatenAt))}
                {intake.quantityGrams !== undefined && ` · ${formatGrams(intake.quantityGrams)}`} · {macros(intake)} ·{' '}
                <span data-testid="intake-source">{SOURCE_LABELS[intake.source]}</span>
              </Typography>
            </Box>
            <Typography sx={{ fontSize: 14 }}>{formatInteger(intake.kcal)} kcal</Typography>
            <IconButton size="small" aria-label={`Supprimer ${intake.label}`} onClick={() => onDelete(intake)}>
              <DeleteOutlineIcon fontSize="small" />
            </IconButton>
          </Box>
        ))}
        {planned.map((meal) => (
          <Box component="li" key={meal.mealId} data-testid={`planned-row-${meal.mealId}`} sx={rowSx}>
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 14, overflowWrap: 'anywhere' }}>{meal.label}</Typography>
              <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                Prévu · {SLOT_LABELS[meal.slot]} · {macros(meal)}
              </Typography>
            </Box>
            <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>{meal.kcal === null ? '—' : formatInteger(meal.kcal)} kcal</Typography>
            <Button
              size="small"
              variant="outlined"
              disabled={eating === meal.mealId}
              aria-label={`Mangé : ${meal.label}`}
              onClick={() => onEaten(meal.mealId)}
            >
              Mangé
            </Button>
          </Box>
        ))}
      </Box>
    </Box>
  )
}
