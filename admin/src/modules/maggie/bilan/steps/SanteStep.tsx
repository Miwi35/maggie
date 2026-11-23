import { useCallback, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { formatInteger } from '../../../health/healthProfileApi'
import { MAX_SCORE, MIN_SCORE, patchHealthDay, useHealthDay } from '../../../health/healthDaysApi'
import type { HealthDay } from '../../../health/healthDaysApi'
import { formatBalance, formatClock, formatSteps, journalIntake } from '../../../health/healthJournal'
import { balanceZone } from '../../../health/nutritionBalance'
import { NONE } from '../../../health/suiviSeries'
import { useHealthCardState } from '../../../home/useHealthCardState'
import { answerInt } from '../bilanSession'
import type { BilanAnswers, BilanSession } from '../bilanSession'
import { BilanCard } from './BilanCard'
import type { BilanSlot } from './slot'

const SCORES = Array.from({ length: MAX_SCORE - MIN_SCORE + 1 }, (_, index) => MIN_SCORE + index)
const WRITE_FAILED = 'Impossible d’enregistrer pour l’instant. Réessayez.'

interface Figure {
  key: string
  label: string
  value: string
  detail?: string
}

/** The four figures of the day, « — » where the day has no source; a detail only when its data exists. */
export const santeFigures = (day: HealthDay | null, goal: number | null, session: string | null): Figure[] => [
  { key: 'balance', label: 'Bilan calorique', value: day?.balanceKcal == null ? NONE : formatBalance(day.balanceKcal), detail: day ? journalIntake(day) : undefined },
  { key: 'steps', label: 'Pas', value: day?.steps == null ? NONE : formatSteps(day.steps), detail: goal ? `objectif ${formatInteger(goal)}` : undefined },
  { key: 'sport', label: 'Sport', value: day?.sportMinutes ? `${formatInteger(day.sportMinutes)} min` : NONE, detail: day?.sportMinutes && session ? session : undefined },
  { key: 'weight', label: 'Poids', value: day?.weightKg == null ? NONE : `${day.weightKg.toFixed(1).replace('.', ',')} kg`, detail: day?.weighedAt ? `pesée · ${formatClock(day.weighedAt)}` : undefined },
]

/** Maggie's line: the day's figures in plain words, never a verdict, then the two questions. */
export const santeSay = (day: HealthDay | null): string => {
  const balance = day?.balanceKcal ?? null
  const facts = [
    day?.sportMinutes && day.sportMinutes > 0 ? 'Séance faite' : null,
    day?.steps != null ? `${formatInteger(day.steps)} pas` : null,
    balance === null
      ? null
      : balanceZone(balance) === 'deficit'
        ? `un déficit de ${formatInteger(-balance)} kcal`
        : balanceZone(balance) === 'surplus'
          ? `un excédent de ${formatInteger(balance)} kcal`
          : 'une journée équilibrée en calories',
  ].filter((fact): fact is string => fact !== null)
  const questions = 'Et votre moral, sur 10 ? Votre fatigue ?'
  if (facts.length === 0) return questions
  const head = facts.length === 1 ? facts[0] : `${facts.slice(0, -1).join(', ')} et ${facts[facts.length - 1]}`

  return `${head}. ${questions}`
}

const Scale = ({ label, value, onChange }: { label: 'Moral' | 'Fatigue'; value: number | null; onChange: (value: number) => void }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
      <Typography component="span" sx={{ fontSize: 15 }}>
        {label}
      </Typography>
      <Typography component="span" sx={{ fontSize: 15, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
        {value === null ? NONE : `${value} / 10`}
      </Typography>
    </Box>
    <Box role="radiogroup" aria-label={label} sx={{ display: 'flex', gap: 0.75 }}>
      {SCORES.map((score) => {
        const checked = score === value

        return (
          <Box
            key={score}
            component="button"
            type="button"
            role="radio"
            aria-checked={checked}
            aria-label={String(score)}
            onClick={() => onChange(score)}
            sx={(theme) => ({
              appearance: 'none',
              border: 0,
              m: 0,
              font: 'inherit',
              cursor: 'pointer',
              flex: '1 1 0',
              minWidth: 0,
              height: 48,
              borderRadius: 2,
              fontSize: 15,
              fontVariantNumeric: 'tabular-nums',
              bgcolor: checked ? theme.palette.primary.main : theme.palette.veilleuse.raised,
              color: checked ? theme.palette.primary.contrastText : theme.palette.text.secondary,
            })}
          >
            {score}
          </Box>
        )
      })}
    </Box>
  </Box>
)

/** The Santé step: the day's figures, then mood and fatigue written to the journal before they are recorded in the bilan. */
export const useSanteSlot = (today: string, session: BilanSession | null, record: (answers: BilanAnswers) => void): BilanSlot => {
  const read = useHealthDay(today)
  const card = useHealthCardState('today')
  const [message, setMessage] = useState<string | null>(null)
  const writes = useRef<Promise<unknown>>(Promise.resolve())
  const day = read.status === 'ready' ? read.data : null
  const replace = read.replace

  const pick = useCallback(
    (key: 'mood' | 'fatigue', value: number) => {
      if (day?.[key] === value) return
      writes.current = writes.current.then(async () => {
        const result = await patchHealthDay(today, { [key]: value })
        if (!result.ok) {
          setMessage(WRITE_FAILED)

          return
        }
        setMessage(null)
        replace(result.day)
        record({ [key]: value })
      })
    },
    [day, today, replace, record],
  )

  const answered = (key: 'mood' | 'fatigue') => day?.[key] ?? (session ? answerInt(session, key) : null)
  const figures = santeFigures(day, card.state?.steps?.goal ?? null, card.state?.workout?.title ?? null)

  const content = (
    <>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1.5 }}>
        {figures.map((figure) => (
          <BilanCard key={figure.key} label={figure.label} testId={`bilan-figure-${figure.key}`}>
            <Typography sx={{ fontSize: 28, fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>{figure.value}</Typography>
            {figure.detail && <Typography sx={{ fontSize: 13, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>{figure.detail}</Typography>}
          </BilanCard>
        ))}
      </Box>
      <BilanCard label="Moral · fatigue">
        <Scale label="Moral" value={answered('mood')} onChange={(value) => pick('mood', value)} />
        <Scale label="Fatigue" value={answered('fatigue')} onChange={(value) => pick('fatigue', value)} />
        {message && (
          <Typography role="alert" sx={{ fontSize: 13, color: 'error.main' }}>
            {message}
          </Typography>
        )}
      </BilanCard>
      {day?.note && <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>{`Note · « ${day.note} »`}</Typography>}
    </>
  )

  return { content, say: santeSay(day) }
}
