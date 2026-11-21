import type { ReactNode } from 'react'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
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

const COLUMNS = ['Heure', 'Aliment', 'Quantité', 'kcal', 'P', 'G', 'L', 'Source', ''] as const
const RIGHT = new Set<string>(['Quantité', 'kcal', 'P', 'G', 'L'])

const cellSx = { py: 1.125, px: 1.25, fontSize: 13, borderBottom: (theme: Theme) => `1px solid ${theme.palette.divider}`, textAlign: 'left' } as const
const grey = { color: 'text.secondary' } as const
const num = { ...cellSx, textAlign: 'right' } as const

/** « 10:30 » → « 10 h 30 »: no leading zero on the hour. */
const journalClock = (instant: Date): string => {
  const [hour, minute] = parisClock(instant).split(':')

  return `${Number(hour)} h ${minute}`
}

/** « 0,4 g » → « 0,4 »: the column's title carries the unit. */
const bare = (grams: number): string => formatGrams(grams).replace(/\s*g$/, '')

const Tag = ({ children, ...rest }: { children: ReactNode; 'data-testid'?: string }) => (
  <Box
    component="span"
    {...rest}
    sx={(theme) => ({
      display: 'inline-flex',
      alignItems: 'center',
      height: 22,
      px: 1,
      borderRadius: 999,
      bgcolor: alpha(theme.palette.text.primary, 0.06),
      color: 'text.secondary',
      fontSize: 11,
    })}
  >
    {children}
  </Box>
)

interface NutritionJournalProps {
  intakes: Loaded<FoodIntake[]>
  planned: PlannedMeal[]
  /** The meal whose « Marquer mangé » is on its way: its button waits. */
  eating: string | null
  onEaten: (mealId: string) => void
  onDelete: (intake: FoodIntake) => void
  /** The addition, at the foot of the journal. */
  children?: ReactNode
}

/** « Journal »: what was eaten, then the planned meals not eaten yet, each with « Marquer mangé ». */
export const NutritionJournal = ({ intakes, planned, eating, onEaten, onDelete, children }: NutritionJournalProps) => {
  const rows = intakes.status === 'ready' ? intakes.data : []

  return (
    <Box component="section" aria-label="Journal" sx={cardSx}>
      {intakes.status === 'loading' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Chargement…</Typography>}
      {intakes.status === 'error' && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Erreur : le journal n’a pas pu être chargé.</Typography>
      )}
      {intakes.status === 'ready' && rows.length === 0 && planned.length === 0 && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Rien de noté ce jour.</Typography>
      )}
      <Box component="table" sx={{ borderCollapse: 'collapse', width: '100%', fontVariantNumeric: 'tabular-nums' }}>
        <thead>
          <tr>
            {COLUMNS.map((name, index) => (
              <Box
                component="th"
                key={index}
                scope="col"
                sx={{
                  ...cellSx,
                  fontSize: 12,
                  fontWeight: 500,
                  color: (theme: Theme) => theme.palette.veilleuse.caption,
                  textAlign: RIGHT.has(name) ? 'right' : 'left',
                }}
              >
                {name}
              </Box>
            ))}
          </tr>
        </thead>
        {rows.map((intake) => (
          <tbody key={intake.id}>
            <tr data-testid={`intake-row-${intake.id}`}>
              <Box component="td" sx={{ ...cellSx, ...grey }}>
                {journalClock(new Date(intake.eatenAt))}
              </Box>
              <Box component="td" sx={{ ...cellSx, overflowWrap: 'anywhere' }}>
                {intake.label}
              </Box>
              <Box component="td" sx={num}>
                {intake.quantityGrams !== undefined ? formatGrams(intake.quantityGrams) : '—'}
              </Box>
              <Box component="td" sx={num}>
                {formatInteger(intake.kcal)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(intake.proteinG)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(intake.carbsG)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(intake.fatG)}
              </Box>
              <Box component="td" sx={cellSx}>
                <Tag data-testid="intake-source">{SOURCE_LABELS[intake.source]}</Tag>
              </Box>
              <Box component="td" sx={cellSx}>
                <IconButton size="small" aria-label={`Supprimer ${intake.label}`} onClick={() => onDelete(intake)}>
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              </Box>
            </tr>
          </tbody>
        ))}
        {planned.map((meal) => (
          <Box component="tbody" key={meal.mealId} data-testid={`planned-row-${meal.mealId}`} sx={{ opacity: 0.55 }}>
            <tr>
              <Box component="td" sx={{ ...cellSx, ...grey }}>
                {SLOT_LABELS[meal.slot]}
              </Box>
              <Box component="td" sx={{ ...cellSx, overflowWrap: 'anywhere' }}>
                {meal.label}
              </Box>
              <Box component="td" sx={num}>
                —
              </Box>
              <Box component="td" sx={num}>
                {meal.kcal === null ? '—' : formatInteger(meal.kcal)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(meal.proteinG)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(meal.carbsG)}
              </Box>
              <Box component="td" sx={{ ...num, ...grey }}>
                {bare(meal.fatG)}
              </Box>
              <Box component="td" sx={cellSx}>
                <Tag>Prévu</Tag>
              </Box>
              <Box component="td" sx={cellSx} />
            </tr>
            <tr>
              <Box component="td" colSpan={9} sx={{ ...cellSx, textAlign: 'right' }}>
                <Button
                  size="small"
                  disabled={eating === meal.mealId}
                  aria-label={`Marquer mangé : ${meal.label}`}
                  onClick={() => onEaten(meal.mealId)}
                  sx={(theme) => ({
                    height: 32,
                    borderRadius: 2,
                    fontSize: 13,
                    color: 'text.primary',
                    bgcolor: alpha(theme.palette.text.primary, 0.08),
                  })}
                >
                  Marquer mangé
                </Button>
              </Box>
            </tr>
          </Box>
        ))}
      </Box>
      {children}
    </Box>
  )
}
