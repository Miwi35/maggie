import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { MONTH_CHOICES, consumedPercent, formatPeriod } from './budgetModes'
import { useBudgetStatus } from './useBudgetStatus'
import type { BudgetLine } from './useBudgetStatus'

const BUDGET_TOPICS = ['/api/envelopes/{id}', '/api/transactions/{id}']

const BudgetGauge = ({ line }: { line: BudgetLine }) => {
  const percent = consumedPercent(line.spentCents, line.amountCents)

  return (
    <Box sx={{ mb: 2 }}>
      <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
        <Typography variant="body2">
          {line.categoryName}
          <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 1 }}>
            {formatPeriod(line.mode, line.year, line.month)}
          </Typography>
        </Typography>
        <Typography variant="body2" color={line.isOverspent ? 'error.main' : 'text.secondary'}>
          {formatCents(line.spentCents, line.currency)} / {formatCents(line.amountCents, line.currency)}
        </Typography>
      </Stack>
      <LinearProgress
        variant="determinate"
        value={percent}
        color={line.isOverspent ? 'error' : 'primary'}
        sx={{ height: 8, borderRadius: 4 }}
      />
      <Typography variant="caption" color={line.isOverspent ? 'error.main' : 'text.secondary'}>
        {line.isOverspent
          ? `Dépassement de ${formatCents(-line.remainingCents, line.currency)}`
          : `Reste ${formatCents(line.remainingCents, line.currency)}`}
      </Typography>
    </Box>
  )
}

/** Consumed-vs-budget gauges for a period, shown above the envelope list. */
export const BudgetStatusPanel = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { status, loading, refresh } = useBudgetStatus(year, month)

  useMercure(BUDGET_TOPICS, () => { refresh() })

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            Budgets
          </Typography>
          <TextField
            select
            size="small"
            label="Mois"
            value={month}
            onChange={(e) => setMonth(Number(e.target.value))}
            sx={{ minWidth: 140 }}
          >
            {MONTH_CHOICES.map((c) => (
              <MenuItem key={c.id} value={c.id}>
                {c.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            type="number"
            size="small"
            label="Année"
            value={year}
            onChange={(e) => setYear(Number(e.target.value))}
            sx={{ width: 110 }}
          />
        </Stack>

        {loading && <LinearProgress sx={{ mb: 2 }} />}

        {status && status.budgets.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            Aucune enveloppe pour cette période.
          </Typography>
        )}

        {status?.budgets.map((line) => (
          <BudgetGauge key={line.id} line={line} />
        ))}

        {status && status.budgets.length > 0 && (
          <Typography variant="body2" sx={{ mt: 1 }}>
            Total : {formatCents(status.totalSpentCents)} dépensés sur{' '}
            {formatCents(status.totalBudgetedCents)} — reste{' '}
            {formatCents(status.totalRemainingCents)}
          </Typography>
        )}
      </CardContent>
    </Card>
  )
}
