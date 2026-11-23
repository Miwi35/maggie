import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { formatCents, formatCentsRounded } from '../../../finance/accountTypes'
import { useBilanFinance } from '../useBilanFinance'
import type { DaySpending, PeriodRhythm } from '../useBilanFinance'
import { BilanCard, BilanError } from './BilanCard'
import type { BilanSlot } from './slot'

export const FINANCE_LOAD_FAILED = 'Les comptes n’ont pas pu être chargés.'

const TAIL = 'Voilà où en sont la semaine et le mois.'

const ordinal = (n: number): string => (n === 1 ? '1er' : `${n}e`)

/** « 4e jour sur 7 » for the week, « 26 jours sur 31 » for the month. */
export const elapsedLabel = (name: 'Semaine' | 'Mois', rhythm: PeriodRhythm): string =>
  name === 'Semaine' ? `${ordinal(rhythm.elapsed)} jour sur ${rhythm.length}` : `${rhythm.elapsed} ${rhythm.elapsed > 1 ? 'jours' : 'jour'} sur ${rhythm.length}`

export const financeSay = (day: DaySpending): string =>
  day.totalCents === 0 ? `Aucune dépense aujourd’hui. ${TAIL}` : `Aujourd’hui, ${formatCents(day.totalCents)} de dépenses. ${TAIL}`

const RhythmLine = ({ name, rhythm }: { name: 'Semaine' | 'Mois'; rhythm: PeriodRhythm }) => {
  const planned = rhythm.plannedCents > 0
  const elapsed = elapsedLabel(name, rhythm)
  const spent = rhythm.spentCents
  const share = planned ? Math.min(1, Math.max(0, spent / rhythm.plannedCents)) : 0
  const time = rhythm.length > 0 ? Math.min(1, rhythm.elapsed / rhythm.length) : 0

  return (
    <Box data-testid={`bilan-rhythm-${name.toLowerCase()}`} sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Typography component="span" sx={{ fontSize: 15 }}>
          {name}
        </Typography>
        <Typography component="span" sx={{ fontSize: 15, fontVariantNumeric: 'tabular-nums' }}>
          {planned ? `${formatCentsRounded(spent)} / ${formatCentsRounded(rhythm.plannedCents)}` : formatCentsRounded(spent)}
        </Typography>
      </Box>
      {planned && (
        <Box
          role="img"
          aria-label={`${name} : ${formatCentsRounded(spent)} dépensés sur ${formatCentsRounded(rhythm.plannedCents)}, ${elapsed}`}
          sx={{ position: 'relative', height: 10, borderRadius: 999, bgcolor: 'action.hover', overflow: 'visible' }}
        >
          <Box sx={{ width: `${share * 100}%`, height: '100%', borderRadius: 999, bgcolor: 'primary.main' }} />
          <Box aria-hidden data-testid="bilan-elapsed-mark" sx={{ position: 'absolute', top: -3, bottom: -3, left: `${time * 100}%`, width: 2, bgcolor: 'text.primary' }} />
        </Box>
      )}
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{elapsed}</Typography>
    </Box>
  )
}

/** The Finance step: the day's debits, the week and the month against the time gone — curves to form an opinion, never a verdict. */
export const useFinanceSlot = (today: string, active: boolean): BilanSlot => {
  const { finance, error, retry } = useBilanFinance(today, active)

  if (error) return { content: <BilanError message={FINANCE_LOAD_FAILED} onRetry={retry} /> }
  if (!finance) return { content: null }

  return {
    content: (
      <>
        <BilanCard label="Dépenses du jour" testId="bilan-day-spending">
          <Typography data-testid="bilan-day-total" sx={{ fontSize: 44, fontWeight: 300, lineHeight: 1.1, fontVariantNumeric: 'tabular-nums' }}>
            {formatCents(finance.day.totalCents)}
          </Typography>
          {finance.day.parts.length > 0 && (
            <Typography sx={{ fontSize: 14, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
              {finance.day.parts.map((part) => `${part.label} ${formatCents(part.cents)}`).join(' · ')}
            </Typography>
          )}
        </BilanCard>
        <BilanCard label="Rythme">
          <RhythmLine name="Semaine" rhythm={finance.week} />
          <RhythmLine name="Mois" rhythm={finance.month} />
        </BilanCard>
      </>
    ),
    say: financeSay(finance.day),
  }
}
