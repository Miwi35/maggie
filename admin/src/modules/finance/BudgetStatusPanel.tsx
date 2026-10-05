import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import { useNotify, useRefresh } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { DailyScoreBanner } from './DailyScoreBanner'
import { PeriodPicker } from './PeriodPicker'
import { formatCents } from './accountTypes'
import { consumedPercent, formatPeriod } from './budgetModes'
import { useBudgetStatus } from './useBudgetStatus'
import { useRollOverEnvelopes } from './useRollOverEnvelopes'
import type { BudgetLine } from './useBudgetStatus'

const BUDGET_TOPICS = ['/api/envelopes/{id}', '/api/transactions/{id}']

/** "250,00 € dépensés · 300,00 € engagés · 800,00 € planifiés" */
const breakdown = (line: BudgetLine): string =>
  [
    line.spentCents > 0 ? `${formatCents(line.spentCents, line.currency)} dépensés` : null,
    line.committedCents > 0 ? `${formatCents(line.committedCents, line.currency)} engagés` : null,
    line.plannedCents > 0 ? `${formatCents(line.plannedCents, line.currency)} planifiés` : null,
    line.toArbitrateCents > 0
      ? `${formatCents(line.toArbitrateCents, line.currency)} à arbitrer`
      : null,
  ]
    .filter(Boolean)
    .join(' · ')

const BudgetGauge = ({ line }: { line: BudgetLine }) => {
  const consumed = consumedPercent(line.consumedCents, line.amountCents)
  const withPlanned = consumedPercent(line.consumedCents + line.plannedCents, line.amountCents)
  const detail = breakdown(line)

  return (
    // The handle the e2e journey uses (MAG-102): one gauge per category, and
    // every amount on it is computed from the transactions, so "this category
    // consumed that much" has to be read as one line rather than as two texts
    // that happen to be on the same screen.
    <Box sx={{ mb: 2 }} data-testid="budget-gauge" data-category={line.categoryName}>
      <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
        <Typography variant="body2">
          {line.categoryName}
          <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 1 }}>
            {formatPeriod(line.mode, line.year, line.month)}
          </Typography>
        </Typography>
        <Typography variant="body2" color={line.isOverspent ? 'error.main' : 'text.secondary'}>
          {formatCents(line.consumedCents, line.currency)} /{' '}
          {formatCents(line.amountCents, line.currency)}
        </Typography>
      </Stack>

      {/* Solid bar: money already gone. Buffer: what plans would take on top. */}
      <Tooltip title={detail || 'Aucun mouvement sur cette enveloppe'} placement="top" arrow>
        <LinearProgress
          variant="buffer"
          value={consumed}
          valueBuffer={withPlanned}
          color={line.isOverspent ? 'error' : line.isOvercommitted ? 'warning' : 'primary'}
          sx={{ height: 8, borderRadius: 4 }}
        />
      </Tooltip>

      <Stack direction="row" justifyContent="space-between" sx={{ mt: 0.25 }}>
        <Typography variant="caption" color="text.secondary">
          {detail}
        </Typography>
        <Typography
          variant="caption"
          color={
            line.isOverspent ? 'error.main' : line.isOvercommitted ? 'warning.main' : 'text.secondary'
          }
        >
          {line.isOverspent
            ? `Dépassement de ${formatCents(-line.remainingCents, line.currency)}`
            : line.plannedCents > 0
              ? `${formatCents(line.availableCents, line.currency)} encore libres`
              : `Reste ${formatCents(line.remainingCents, line.currency)}`}
        </Typography>
      </Stack>
    </Box>
  )
}

/** Consumed-vs-budget gauges for a period, shown above the envelope list. */
export const BudgetStatusPanel = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { status, loading, refresh } = useBudgetStatus(year, month)
  const { rollOver, running } = useRollOverEnvelopes()
  const notify = useNotify()
  const refreshList = useRefresh()

  useMercure(BUDGET_TOPICS, () => { refresh() })

  const previous = new Date(year, month - 2, 1)

  const onRollOver = async () => {
    const result = await rollOver({
      fromYear: previous.getFullYear(),
      fromMonth: previous.getMonth() + 1,
      year,
      month,
    })
    if (result === null) {
      notify('Impossible de reconduire les enveloppes', { type: 'error' })

      return
    }
    notify(
      result.created > 0
        ? `${result.created} enveloppe(s) reconduite(s), ${result.skipped} déjà en place`
        : 'Rien à reconduire : les enveloppes sont déjà en place',
      { type: 'info' },
    )
    refresh()
    refreshList()
  }

  return (
    <>
      <DailyScoreBanner year={year} month={month} />
      <Card sx={{ mb: 2 }}>
      <CardContent>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          alignItems={{ xs: 'stretch', sm: 'center' }}
          sx={{ mb: 2 }}
        >
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            Budgets
          </Typography>
          <PeriodPicker
            year={year}
            month={month}
            onYearChange={setYear}
            onMonthChange={setMonth}
          />
          <Button
            size="small"
            startIcon={<ContentCopyIcon />}
            onClick={onRollOver}
            disabled={running}
            sx={{ flexShrink: 0 }}
          >
            Reconduire le mois précédent
          </Button>
        </Stack>

        {loading && <LinearProgress sx={{ mb: 2 }} />}

        {status?.budgets.map((line) => (
          <BudgetGauge key={line.id} line={line} />
        ))}

        {status && status.budgets.length > 0 && (
          <Typography variant="body2" sx={{ mt: 1 }}>
            Total : {formatCents(status.totalConsumedCents)} consommés sur{' '}
            {formatCents(status.totalBudgetedCents)}
            {status.totalPlannedCents > 0 && (
              <> — {formatCents(status.totalPlannedCents)} planifiés</>
            )}{' '}
            — {formatCents(status.totalAvailableCents)} encore libres
          </Typography>
        )}
      </CardContent>
      </Card>
    </>
  )
}
