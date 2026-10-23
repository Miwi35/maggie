import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { fetchRepaymentFigures, monthLabel, nImpactLabel } from './scenariosApi'
import type { RepaymentFigures, Scenario } from './scenariosApi'

const EFFECT_LABEL = { duration: 'réduire la durée', payment: 'réduire la mensualité' } as const

const gainLabel = (cents: number): string => `${cents < 0 ? '−' : ''}${formatCents(Math.abs(cents))}`

/** The early repayments of a scenario as elements of its list, each with what the API figures it comes to: real gain, effect on N, freed payment. */
export const ScenarioRepaymentFigures = ({ scenario }: { scenario: Scenario }) => {
  const [figures, setFigures] = useState<Record<string, RepaymentFigures>>({})
  const [error, setError] = useState<string | null>(null)
  const version = JSON.stringify(scenario.repayments)

  useEffect(() => {
    let live = true
    fetchRepaymentFigures(scenario.id)
      .then((loaded) => {
        if (!live) return
        setFigures(loaded)
        setError(null)
      })
      .catch((reason: Error) => live && setError(reason.message))

    return () => {
      live = false
    }
  }, [scenario.id, version])

  return (
    <Stack spacing={1} sx={{ mt: 1.5 }} data-testid="scenario-repayment-list">
      {error && (
        <Alert severity="warning" variant="outlined">
          {error}
        </Alert>
      )}
      {scenario.repayments.map((repayment) => {
        const figure = repayment.id ? figures[repayment.id] : undefined
        const name = figure?.loanName ?? ''

        return (
          <Stack
            key={repayment.id ?? `${repayment.loanId}-${repayment.month}`}
            data-testid="scenario-repayment-item"
            spacing={0.5}
            sx={{ borderLeft: 3, borderColor: 'info.main', pl: 1.5 }}
          >
            <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
              <Chip size="small" color="info" label="Remboursement anticipé" />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {name} · {monthLabel(repayment.month)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {repayment.amountCents === null ? 'Total' : `Partiel ${formatCents(repayment.amountCents)}`}
                {figure?.effect ? ` · ${EFFECT_LABEL[figure.effect]}` : ''}
              </Typography>
            </Stack>
            {figure && (
              <Typography variant="body2" color="text.secondary" data-testid="scenario-repayment-figures">
                Capital immobilisé {formatCents(figure.immobilizedCents)} · IRA {formatCents(figure.penaltyCents)} · Gain réel{' '}
                <strong>{gainLabel(figure.realGainCents)}</strong> · {nImpactLabel(figure.nImpactMonths)} · Mensualité libérée{' '}
                {formatCents(figure.freedMonthlyCents)}
              </Typography>
            )}
          </Stack>
        )
      })}
    </Stack>
  )
}
