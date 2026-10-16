import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { AccountsTile } from './AccountsTile'
import { BudgetTile } from './BudgetTile'
import { FinanceScoresCard } from './FinanceScoresSection'
import { NextProjectCard } from './NextProjectCard'
import { PeriodPicker } from './PeriodPicker'
import { PlanningTile } from './PlanningTile'
import { ToQualifyPill } from './ToQualifyPill'
import { useFinanceDashboard } from './useFinanceDashboard'

const DASHBOARD_TOPICS = [
  '/api/transactions/{id}',
  '/api/accounts/{id}',
  '/api/envelopes/{id}',
]

/**
 * The finance home: the two rings, the month's curve against its rhythm, the
 * next project, then three tiles — Comptes, Budget, Planification — each
 * opening its menu. One read (`/api/finance/dashboard`) feeds all of it, and
 * one Mercure refresh reloads all of it.
 */
export const FinanceDashboardPage = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { dashboard, loading, error, refresh } = useFinanceDashboard(year, month)

  useMercure(DASHBOARD_TOPICS, () => {
    refresh()
  })

  return (
    <>
      <Title title="Finance" />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', sm: 'center' }}
        sx={{ mb: 2 }}
      >
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Vue d'ensemble
        </Typography>
        <PeriodPicker
          year={year}
          month={month}
          onYearChange={setYear}
          onMonthChange={setMonth}
        />
      </Stack>

      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => { refresh() }}>
              Réessayer
            </Button>
          }
        >
          Le tableau de bord n'a pas pu être chargé.
        </Alert>
      )}

      <ToQualifyPill />

      {loading && !dashboard && <LinearProgress />}

      {dashboard && (
        <>
          <FinanceScoresCard scores={dashboard.scores} />

          <NextProjectCard />

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
            <AccountsTile tile={dashboard.tiles.accounts} />
            <BudgetTile tile={dashboard.tiles.budget} />
            <PlanningTile tile={dashboard.tiles.planning} />
          </Stack>
        </>
      )}
    </>
  )
}
