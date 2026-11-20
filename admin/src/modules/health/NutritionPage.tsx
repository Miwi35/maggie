import { useCallback, useMemo, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
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
import { formatGrams, shiftDay, weekRange } from './nutritionBalance'
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

const LONG_DAY = new Intl.DateTimeFormat('fr-FR', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long' })

/** « Mardi 21 octobre ». */
const longDay = (day: string): string => {
  const text = LONG_DAY.format(new Date(`${day}T12:00:00Z`))

  return text.charAt(0).toUpperCase() + text.slice(1)
}

const stepSx = (theme: Theme) => ({
  width: 32,
  height: 32,
  borderRadius: 2,
  bgcolor: alpha(theme.palette.text.primary, 0.08),
})

/** ‹ « Mardi 21 octobre » ›: the day shown, never after today. */
const DayPicker = ({ day, today, onChange }: DayPickerProps) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
    <IconButton size="small" aria-label="Jour précédent" sx={stepSx} onClick={() => onChange(shiftDay(day, -1))}>
      <ChevronLeftIcon fontSize="small" />
    </IconButton>
    <Typography component="span" data-testid="nutrition-day" sx={{ fontSize: 14, minWidth: 150, textAlign: 'center' }}>
      {longDay(day)}
    </Typography>
    <IconButton size="small" aria-label="Jour suivant" sx={stepSx} disabled={day >= today} onClick={() => onChange(shiftDay(day, 1))}>
      <ChevronRightIcon fontSize="small" />
    </IconButton>
  </Box>
)

/** « Protéines 38,4 g  Glucides 71,5 g  Lipides 16,3 g »: the day's macros, eaten and planned. */
const MacrosLine = ({ balance }: { balance: DailyBalance }) => (
  <Box
    component="section"
    aria-label="Macros"
    sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.75, fontSize: 12, color: (theme) => theme.palette.veilleuse.caption, fontVariantNumeric: 'tabular-nums' }}
  >
    {(
      [
        ['Protéines', balance.proteinG, 'macro-protein'],
        ['Glucides', balance.carbsG, 'macro-carbs'],
        ['Lipides', balance.fatG, 'macro-fat'],
      ] as const
    ).map(([label, grams, testId]) => (
      <span key={label}>
        {label} <span data-testid={testId}>{formatGrams(grams)}</span>
      </span>
    ))}
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
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 2fr) minmax(0, 1fr)' },
          gridTemplateRows: { md: 'auto minmax(0, 1fr)' },
          gap: 1.75,
          alignItems: 'start',
        }}
      >
        <Box sx={{ gridColumn: { md: 1 }, minWidth: 0 }}>
          {balance.status === 'loading' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>Chargement…</Typography>}
          {balance.status === 'error' && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{LOAD_ERROR}</Typography>}
          {balance.status === 'ready' && (
            <NutritionBalanceCard day={balance.data}>
              <NutritionWeekCard embedded state={week} day={day} today={today} />
              <MacrosLine balance={balance.data} />
            </NutritionBalanceCard>
          )}
        </Box>
        <Box sx={{ gridColumn: { md: 1 }, minWidth: 0 }}>
          <NutritionJournal
            intakes={intakes}
            planned={balance.status === 'ready' ? balance.data.plannedMeals : []}
            eating={eating}
            onEaten={eat}
            onDelete={remove}
          >
            <NutritionAddForm embedded day={day} onAdd={add} />
          </NutritionJournal>
        </Box>
        <Box data-testid="nutrition-fasting-cell" sx={{ gridColumn: { md: 2 }, gridRow: { md: '1 / 3' }, minWidth: 0 }}>
          {balance.status === 'ready' && (
            <FastingCard profile={profile} fasting={fasting} days={fastDays} day={day} today={today} onSave={saveFasting} />
          )}
        </Box>
      </Box>
    </Box>
  )
}
