import { useCallback, useMemo, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import { parisDay } from './dailyEnergySeries'
import { FastingCard } from './FastingCard'
import { FAST_DAYS, useFasting } from './fasting'
import { HEALTH_DAY_TOPICS, useHealthDays } from './healthDaysApi'
import { useHealthProfile } from './healthProfileApi'
import type { HealthProfilePatch } from './healthProfileApi'
import { NutritionAddForm } from './NutritionAddForm'
import { NutritionBalanceCard } from './NutritionBalanceCard'
import { NutritionJournal } from './NutritionJournal'
import { NutritionWeekCard } from './NutritionWeekCard'
import { createIntake, deleteIntake, NUTRITION_TOPICS, useBalanceRange, useDailyBalance, useFoodIntakes } from './nutritionApi'
import type { FoodIntake, IntakePayload, WriteResult } from './nutritionApi'
import { cardSx, dayLabel, formatGrams, shiftDay, weekRange } from './nutritionBalance'
import type { DailyBalance } from './nutritionBalance'

/** The page's topics besides the profile's, on its one stream. */
const PAGE_TOPICS = [...NUTRITION_TOPICS, ...HEALTH_DAY_TOPICS]

const LOAD_ERROR = 'Erreur : le bilan du jour n’a pas pu être chargé.'
const ADD_ERROR = 'Erreur : l’aliment n’a pas été ajouté.'
const EATEN_ERROR = 'Erreur : le repas n’a pas été noté.'
const DELETE_ERROR = 'Erreur : la ligne n’a pas été supprimée.'

interface DayPickerProps {
  day: string
  today: string
  onChange: (day: string) => void
}

/** ‹ « Aujourd’hui » ›: the day shown, never after today. */
const DayPicker = ({ day, today, onChange }: DayPickerProps) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
    <IconButton size="small" aria-label="Jour précédent" onClick={() => onChange(shiftDay(day, -1))}>
      <ChevronLeftIcon fontSize="small" />
    </IconButton>
    <Typography component="span" data-testid="nutrition-day" sx={{ fontSize: 14, minWidth: 110, textAlign: 'center' }}>
      {dayLabel(day, today)}
    </Typography>
    <IconButton size="small" aria-label="Jour suivant" disabled={day >= today} onClick={() => onChange(shiftDay(day, 1))}>
      <ChevronRightIcon fontSize="small" />
    </IconButton>
  </Box>
)

/** « Macros »: the day's proteins, carbohydrates and fat, eaten and planned. */
const MacrosCard = ({ balance }: { balance: DailyBalance }) => (
  <Box component="section" aria-label="Macros" sx={cardSx}>
    <Box sx={{ fontSize: 15, fontWeight: 500 }}>Macros</Box>
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 1.5 }}>
      {(
        [
          ['Protéines', balance.proteinG, 'macro-protein'],
          ['Glucides', balance.carbsG, 'macro-carbs'],
          ['Lipides', balance.fatG, 'macro-fat'],
        ] as const
      ).map(([label, grams, testId]) => (
        <Box key={label} sx={{ display: 'flex', flexDirection: 'column' }}>
          <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
            {label}
          </Typography>
          <Typography component="span" data-testid={testId} sx={{ fontSize: 20, fontWeight: 300 }}>
            {formatGrams(grams)}
          </Typography>
        </Box>
      ))}
    </Box>
  </Box>
)

/**
 * Santé › Nutrition (MAG-748): the day's balance first, the 7 days, the
 * macros, then the journal — what was eaten and the planned meals not eaten
 * yet, with « Mangé » — and the free addition. Every figure is the API's; a
 * write, here or elsewhere (Maggie, the phone), reads the day again. The
 * « Jeûne » card (MAG-770) sits by the macros.
 */
export const NutritionPage = () => {
  const notify = useNotify()
  const [today] = useState(() => parisDay(new Date()))
  const [day, setDay] = useState(today)
  const [eating, setEating] = useState<string | null>(null)
  const { from, to } = useMemo(() => weekRange(day), [day])

  const balance = useDailyBalance(day)
  const week = useBalanceRange(from, to)
  const intakes = useFoodIntakes(day)
  const { refresh: refreshBalance } = balance
  const { refresh: refreshWeek } = week
  const { refresh: refreshIntakes } = intakes

  // The fast (MAG-770): the profile holds the protocol, the API computes the day's window and the night fasts.
  const fasting = useFasting(day)
  const fastDays = useHealthDays(shiftDay(day, -(FAST_DAYS - 1)), day)
  const { refresh: refreshFasting } = fasting
  const { refresh: refreshFastDays } = fastDays

  const refreshDay = useCallback(() => {
    refreshBalance()
    refreshWeek()
    refreshIntakes()
    refreshFasting()
    refreshFastDays()
  }, [refreshBalance, refreshWeek, refreshIntakes, refreshFasting, refreshFastDays])

  // One stream for the page, the profile's: the journal is served by Doctrine (an event may be read
  // back at once), a protocol changed elsewhere moves the window, a day's entry carries its night fast.
  const { profile, save } = useHealthProfile({ topics: PAGE_TOPICS, onEvent: refreshDay })

  const saveFasting = useCallback(
    async (patch: HealthProfilePatch): Promise<boolean> => {
      const saved = await save(patch)
      if (saved) refreshFasting()

      return saved
    },
    [save, refreshFasting],
  )

  const after = useCallback(
    (result: WriteResult, fallback: string): boolean => {
      refreshDay()
      if (!result.ok) notify(result.detail ?? fallback, { type: 'error' })

      return result.ok
    },
    [notify, refreshDay],
  )

  const eat = useCallback(
    async (mealId: string) => {
      setEating(mealId)
      after(await createIntake({ mealId }), EATEN_ERROR)
      setEating(null)
    },
    [after],
  )

  const remove = useCallback(async (intake: FoodIntake) => after(await deleteIntake(intake.id), DELETE_ERROR), [after])

  const add = useCallback(
    async (payload: IntakePayload): Promise<WriteResult> => {
      const result = await createIntake(payload)
      after(result, ADD_ERROR)

      return result
    },
    [after],
  )

  return (
    <Box data-testid="nutrition-board" sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, px: 2, py: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
        <DayPicker day={day} today={today} onChange={setDay} />
      </Box>
      {balance.status === 'loading' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Chargement…</Typography>}
      {balance.status === 'error' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{LOAD_ERROR}</Typography>}
      {balance.status === 'ready' && (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 3fr) minmax(0, 2fr)' }, gap: 1.75, minWidth: 0 }}>
          <NutritionBalanceCard day={balance.data} />
          <NutritionWeekCard state={week} day={day} today={today} />
          <MacrosCard balance={balance.data} />
          <FastingCard profile={profile} fasting={fasting} days={fastDays} day={day} today={today} onSave={saveFasting} />
        </Box>
      )}
      <NutritionJournal
        intakes={intakes}
        planned={balance.status === 'ready' ? balance.data.plannedMeals : []}
        eating={eating}
        onEaten={eat}
        onDelete={remove}
      />
      <NutritionAddForm day={day} onAdd={add} />
    </Box>
  )
}
