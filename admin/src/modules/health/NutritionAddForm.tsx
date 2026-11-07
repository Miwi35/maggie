import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { CiqualFoodPicker } from './CiqualFoodPicker'
import type { CiqualFood } from './CiqualFoodPicker'
import { cardSx, parisClock } from './nutritionBalance'
import type { IntakePayload, WriteResult } from './nutritionApi'
import { decimal } from './bodyMeasurementPayload'
import { toOffsetIso } from './workoutPayload'

type Mode = 'ciqual' | 'free'

interface FreeValues {
  label: string
  kcal: string
  proteinG: string
  carbsG: string
  fatG: string
}

const EMPTY_FREE: FreeValues = { label: '', kcal: '', proteinG: '', carbsG: '', fatG: '' }

interface NutritionAddFormProps {
  /** The day the page shows: the intake is eaten on it, at the time typed. */
  day: string
  onAdd: (payload: IntakePayload) => Promise<WriteResult>
}

const isBlank = (value: string) => value.trim() === ''

/** A macro typed or not: undefined when blank, NaN when it is not a figure. */
const optionalGrams = (value: string): number | undefined => (isBlank(value) ? undefined : decimal(value))

/**
 * « Ajouter »: a Ciqual food and its grams — the API computes the figures and
 * the label — or a dish typed with its kcal, macros if known.
 */
export const NutritionAddForm = ({ day, onAdd }: NutritionAddFormProps) => {
  const [mode, setMode] = useState<Mode>('ciqual')
  const [time, setTime] = useState(() => parisClock())
  const [food, setFood] = useState<CiqualFood | null>(null)
  const [grams, setGrams] = useState('')
  const [free, setFree] = useState<FreeValues>(EMPTY_FREE)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  const changeFree = (name: keyof FreeValues) => (event: { target: { value: string } }) => {
    setFree((previous) => ({ ...previous, [name]: event.target.value }))
    setError(null)
  }

  const payload = (): IntakePayload | string => {
    if (!/^\d{2}:\d{2}$/.test(time)) return 'Indiquez l’heure du repas.'
    const eatenAt = toOffsetIso(`${day}T${time}`)
    if (mode === 'ciqual') {
      if (!food) return 'Choisissez un aliment Ciqual.'
      const quantityGrams = decimal(grams)
      if (Number.isNaN(quantityGrams) || quantityGrams <= 0) return 'Indiquez la quantité en grammes.'

      return { eatenAt, ciqualCode: food.alim_code, quantityGrams }
    }
    if (isBlank(free.label)) return 'Indiquez ce qui a été mangé.'
    const kcal = /^\d+$/.test(free.kcal.trim()) ? Number(free.kcal.trim()) : Number.NaN
    if (Number.isNaN(kcal) || kcal > 10000) return 'Indiquez les kcal, un nombre entier de 0 à 10 000.'
    const macros = { proteinG: optionalGrams(free.proteinG), carbsG: optionalGrams(free.carbsG), fatG: optionalGrams(free.fatG) }
    if (Object.values(macros).some((value) => value !== undefined && Number.isNaN(value))) return 'Les macros sont des grammes.'

    return {
      eatenAt,
      label: free.label.trim(),
      kcal,
      ...Object.fromEntries(Object.entries(macros).filter(([, value]) => value !== undefined)),
    }
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const built = payload()
    if (typeof built === 'string') {
      setError(built)

      return
    }
    setSending(true)
    const result = await onAdd(built)
    setSending(false)
    if (result.ok) {
      setFood(null)
      setGrams('')
      setFree(EMPTY_FREE)
      setError(null)
    }
  }

  return (
    <Box component="section" aria-label="Ajouter" sx={cardSx}>
      <Box sx={{ fontSize: 15, fontWeight: 500 }}>Ajouter</Box>
      <Box component="form" noValidate onSubmit={submit} sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          aria-label="Type d’ajout"
          onChange={(_event, next: Mode | null) => {
            if (next) {
              setMode(next)
              setError(null)
            }
          }}
        >
          <ToggleButton value="ciqual">Aliment Ciqual</ToggleButton>
          <ToggleButton value="free">Saisie libre</ToggleButton>
        </ToggleButtonGroup>
        {mode === 'ciqual' ? (
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(0, 1fr) 120px' }, gap: 1.5 }}>
            <CiqualFoodPicker
              value={food}
              onChange={(next) => {
                setFood(next)
                setError(null)
              }}
            />
            <TextField
              size="small"
              label="Grammes"
              value={grams}
              inputProps={{ inputMode: 'decimal' }}
              onChange={(event) => {
                setGrams(event.target.value)
                setError(null)
              }}
            />
          </Box>
        ) : (
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', sm: 'minmax(0, 2fr) repeat(4, minmax(0, 1fr))' }, gap: 1.5 }}>
            <TextField size="small" label="Libellé" value={free.label} onChange={changeFree('label')} sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' } }} />
            <TextField size="small" label="kcal" value={free.kcal} inputProps={{ inputMode: 'numeric' }} onChange={changeFree('kcal')} />
            <TextField size="small" label="Protéines (g)" value={free.proteinG} inputProps={{ inputMode: 'decimal' }} onChange={changeFree('proteinG')} />
            <TextField size="small" label="Glucides (g)" value={free.carbsG} inputProps={{ inputMode: 'decimal' }} onChange={changeFree('carbsG')} />
            <TextField size="small" label="Lipides (g)" value={free.fatG} inputProps={{ inputMode: 'decimal' }} onChange={changeFree('fatG')} />
          </Box>
        )}
        <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center' }}>
          <TextField
            size="small"
            type="time"
            label="Heure"
            value={time}
            onChange={(event) => {
              setTime(event.target.value)
              setError(null)
            }}
            InputLabelProps={{ shrink: true }}
            sx={{ width: 130 }}
          />
          <Button type="submit" variant="contained" disabled={sending}>
            Ajouter
          </Button>
        </Box>
        {error && (
          <Typography role="alert" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {error}
          </Typography>
        )}
      </Box>
    </Box>
  )
}
