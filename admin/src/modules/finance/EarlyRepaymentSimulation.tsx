import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { penaltyModeLabel } from './loans'
import { useEarlyRepayment } from './useLoanSimulation'

interface EarlyRepaymentSimulationProps {
  loanId: string
  principalRemainingCents: number
  currency?: string
  /** Changes whenever the loan's conditions do: the simulation is asked again. */
  version: string
}

const Bar = ({ label, cents, max, color, testId, currency }: {
  label: string
  cents: number
  max: number
  color: string
  testId: string
  currency: string
}) => (
  <Box>
    <Stack direction="row" justifyContent="space-between">
      <Typography variant="body2">{label}</Typography>
      <Typography variant="body2" data-testid={testId} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {formatCents(cents, currency)}
      </Typography>
    </Stack>
    <Box sx={{ height: 12, borderRadius: 1, backgroundColor: 'action.hover' }}>
      <Box sx={{ height: '100%', borderRadius: 1, backgroundColor: color, width: `${max > 0 ? Math.max(1, (cents / max) * 100) : 0}%` }} />
    </Box>
  </Box>
)

/**
 * What an early repayment costs and saves, from the API's own figures: the
 * interest left on the amount against the penalty, and the gap between them.
 * Facts only.
 */
export const EarlyRepaymentSimulation = ({ loanId, principalRemainingCents, currency = 'EUR', version }: EarlyRepaymentSimulationProps) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const [typed, setTyped] = useState('')
  const parsed = typed.trim() === '' ? null : Math.round(Number(typed.replace(',', '.')) * 100)
  const amountCents = parsed !== null && Number.isNaN(parsed) ? null : parsed
  const { repayment, error } = useEarlyRepayment(loanId, amountCents, version)

  const max = repayment ? Math.max(repayment.remainingInterestCents, repayment.penaltyCents) : 0

  return (
    <Card sx={{ mt: 3, maxWidth: 680 }} data-testid="early-repayment">
      <CardContent>
        <Typography variant="h6" sx={{ mb: 2 }}>
          Remboursement anticipé
        </Typography>

        <TextField
          label="Montant remboursé (€)"
          type="number"
          size="small"
          value={typed}
          placeholder={String(principalRemainingCents / 100)}
          onChange={(event) => setTyped(event.target.value)}
          sx={{ mb: 2 }}
        />

        {error && <Alert severity="error" role="alert">{error}</Alert>}

        {repayment && (
          <Stack spacing={1.5}>
            <Bar label="Intérêts restants" cents={repayment.remainingInterestCents} max={max} color={chartColor(1, mode)} testId="ira-interest" currency={currency} />
            <Bar label={`IRA — ${penaltyModeLabel(repayment.penaltyMode)}`} cents={repayment.penaltyCents} max={max} color={chartColor(2, mode)} testId="ira-penalty" currency={currency} />
            <Stack direction="row" justifyContent="space-between">
              <Typography variant="subtitle2">Gain réel</Typography>
              <Typography variant="subtitle2" data-testid="ira-gain" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {formatCents(repayment.realGainCents, currency)}
              </Typography>
            </Stack>
          </Stack>
        )}
      </CardContent>
    </Card>
  )
}
